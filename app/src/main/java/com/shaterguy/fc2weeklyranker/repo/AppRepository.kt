package com.shaterguy.fc2weeklyranker.repo

import android.content.Context
import com.shaterguy.fc2weeklyranker.data.AppDatabase
import com.shaterguy.fc2weeklyranker.data.DownloadEntity
import com.shaterguy.fc2weeklyranker.data.DownloadStatus
import com.shaterguy.fc2weeklyranker.data.FavoriteEntity
import com.shaterguy.fc2weeklyranker.data.PostEntity
import com.shaterguy.fc2weeklyranker.data.SettingsStore
import com.shaterguy.fc2weeklyranker.data.VideoEntity
import com.shaterguy.fc2weeklyranker.domain.RankCandidate
import com.shaterguy.fc2weeklyranker.domain.rank
import com.shaterguy.fc2weeklyranker.domain.windowFor
import com.shaterguy.fc2weeklyranker.download.DownloadScheduler
import com.shaterguy.fc2weeklyranker.download.DownloadTransferRunner
import com.shaterguy.fc2weeklyranker.download.VideoDownloadWorker
import com.shaterguy.fc2weeklyranker.network.AvseeClient
import com.shaterguy.fc2weeklyranker.network.BaseUrlPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

class AppRepository(private val context: Context, private val db: AppDatabase, val settings: SettingsStore, private val source: AvseeClient) {
    private data class ProbeKey(val postId: String, val resolverOrdinal: Int)
    private data class ProbeSession(
        val candidates: LinkedHashMap<String, String> = linkedMapOf(),
        var activeVideoIds: Set<String> = emptySet(),
    )

    private val videoMutationMutex = Mutex()
    private val probeSessions = mutableMapOf<ProbeKey, ProbeSession>()
    private val downloadScheduler = DownloadScheduler(context)

    fun posts(snapshotKey: String): Flow<List<PostEntity>> = db.postDao().postsForSnapshot(snapshotKey)
    fun favorites(): Flow<List<PostEntity>> = db.postDao().favorites()
    fun post(postId: String): Flow<PostEntity?> = db.postDao().observeById(postId)
    fun isFavorite(postId: String): Flow<Boolean> = db.postDao().observeFavorite(postId)
    fun videos(postId: String): Flow<List<VideoEntity>> = db.videoDao().forPost(postId)
    fun download(videoId: String): Flow<DownloadEntity?> = db.downloadDao().observe(videoId)
    fun visitedPostIds(): Flow<Set<String>> = settings.visitedPostIds
    suspend fun ensureAnchor(): Long = settings.ensureAnchor()

    suspend fun ensurePage(pageIndex: Int) {
        val anchor = settings.ensureAnchor()
        if (db.postDao().snapshotCount(snapshotKey(anchor, pageIndex)) == 0) refreshPage(pageIndex)
    }

    suspend fun refreshPage(pageIndex: Int) {
        val anchorMillis = settings.ensureAnchor()
        val anchor = Instant.ofEpochMilli(anchorMillis)
        val remote = source.crawlWindow(settings.baseUrl.first(), windowFor(anchor, pageIndex))
        val ranked = rank(anchor, remote.map { RankCandidate(it, it.postedAt, it.recommendationCount, it.id) })
        val now = System.currentTimeMillis()
        db.postDao().upsert(ranked.map { (candidate, rate) ->
            val post = candidate.value
            PostEntity(post.id, post.url, post.title, post.postedAt.toEpochMilli(), post.recommendationCount, rate, snapshotKey(anchorMillis, pageIndex), now)
        })
    }

    suspend fun manualRefresh(): Long {
        source.clearCrawlCache()
        val anchor = settings.refreshAnchor()
        refreshPage(0)
        return anchor
    }

    suspend fun setBaseUrl(input: String): Result<String> {
        val normalized = BaseUrlPolicy.normalize(input).getOrElse { return Result.failure(it) }
        source.testConnection(normalized).getOrElse { return Result.failure(it) }
        settings.setBaseUrl(normalized)
        return Result.success(normalized)
    }

    suspend fun testCurrentBaseUrl(): Result<Unit> = source.testConnection(settings.baseUrl.first())

    suspend fun toggleFavorite(postId: String) {
        if (db.postDao().isFavorite(postId)) db.postDao().removeFavorite(postId) else db.postDao().addFavorite(FavoriteEntity(postId, System.currentTimeMillis()))
    }

    suspend fun markPostVisited(postId: String) = settings.markPostVisited(postId)

    suspend fun loadVideos(postId: String) {
        val post = db.postDao().byId(postId) ?: return
        videoMutationMutex.withLock { clearProbeSessions(postId) }
        val detailUrl = rebaseDetailUrl(post.url, settings.baseUrl.first())
        val detail = source.loadDetail(detailUrl)
        val now = System.currentTimeMillis()
        val seenMedia = linkedSetOf<String>()
        val entities = detail.media
            .filter { it.url.startsWith("https://") }
            .filter { seenMedia.add(mediaSourceKey(it.kind, it.url)) }
            .map { media ->
                val id = if (media.kind == SOURCE_IFRAME) stableResolverId(postId, media.url) else stableVideoId(postId, media.url)
                VideoEntity(
                    id,
                    postId,
                    media.url,
                    media.referer,
                    AvseeClient.USER_AGENT,
                    media.kind,
                    media.ordinal,
                    now,
                )
            }
        videoMutationMutex.withLock {
            val existing = db.videoDao().currentForPost(postId)
            val activeProbeIds = probeSessions
                .filterKeys { it.postId == postId }
                .values
                .flatMapTo(linkedSetOf()) { it.activeVideoIds }
            db.videoDao().reconcileForPost(postId, reconcileVideoRows(existing, entities, detailUrl, activeProbeIds))
        }
    }

    suspend fun registerProbedVideo(postId: String, url: String, referer: String, ordinal: Int) {
        if (!url.startsWith("https://")) return
        videoMutationMutex.withLock {
            val key = ProbeKey(postId, ordinal)
            val session = probeSessions.getOrPut(key) { ProbeSession() }
            val candidateKey = canonicalMediaKey(url)
            session.candidates[candidateKey] = url
            val existing = db.videoDao().currentForPost(postId)
            val legacyRows = existing.filter { isLegacyProbeCandidate(it, ordinal, referer) }
            val preferredLegacyIds = linkedSetOf<String>()
            for (row in legacyRows) {
                if (db.downloadDao().byVideoId(row.id) != null) preferredLegacyIds += row.id
            }
            val updates = reconcileProbeRows(
                existing = existing,
                preferredLegacyIds = preferredLegacyIds,
                postId = postId,
                referer = referer,
                resolverOrdinal = ordinal,
                candidates = session.candidates.values.toList(),
                now = System.currentTimeMillis(),
            )
            if (updates.isNotEmpty()) db.videoDao().upsert(updates)
            session.activeVideoIds = updates.filter { it.sourceKind == SOURCE_DIRECT }.mapTo(linkedSetOf()) { it.id }
        }
    }

    suspend fun queueDownload(videoId: String) {
        val video = db.videoDao().byId(videoId) ?: return
        val dao = db.downloadDao()
        val previous = dao.byVideoId(videoId)
        if (previous?.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.FINALIZING, DownloadStatus.COMPLETED)) return
        if (!VideoDownloadWorker.supportsFileDownload(video.url)) {
            dao.upsert(
                DownloadEntity(
                    videoId = videoId,
                    status = DownloadStatus.FAILED,
                    contentUri = previous?.contentUri,
                    downloadedBytes = previous?.downloadedBytes ?: 0L,
                    totalBytes = previous?.totalBytes,
                    errorCode = "HLS_OFFLINE_UNSUPPORTED",
                    updatedAtEpochMillis = System.currentTimeMillis(),
                    enqueueOrder = previous?.enqueueOrder ?: 0L,
                    retryCount = previous?.retryCount ?: 0,
                ),
            )
            return
        }
        val state = dao.prepareQueue(videoId, System.currentTimeMillis()) ?: return
        if (!downloadScheduler.schedule(state)) {
            dao.transitionStatus(
                videoId,
                listOf(DownloadStatus.QUEUED, DownloadStatus.FINALIZING),
                DownloadStatus.FAILED,
                "SCHEDULE_FAILED",
                System.currentTimeMillis(),
            )
        }
    }

    suspend fun recoverDownloads() {
        db.downloadDao().currentSchedulableDownloads().forEach { state ->
            downloadScheduler.recover(state)
        }
    }

    suspend fun pauseDownload(videoId: String) {
        val dao = db.downloadDao()
        val previous = dao.byVideoId(videoId) ?: return
        val changed = dao.transitionStatus(
            videoId,
            listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
            DownloadStatus.PAUSED,
            null,
            System.currentTimeMillis(),
        )
        if (changed > 0) {
            DownloadTransferRunner.cancel(videoId)
            downloadScheduler.cancel(previous)
        }
    }

    suspend fun stopDownload(videoId: String) {
        val dao = db.downloadDao()
        val previous = dao.byVideoId(videoId) ?: return
        val changed = dao.transitionStatus(
            videoId,
            listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.PAUSED, DownloadStatus.FAILED),
            DownloadStatus.STOPPED,
            null,
            System.currentTimeMillis(),
        )
        if (changed == 0) return
        DownloadTransferRunner.cancel(videoId)
        downloadScheduler.cancel(previous)
        val stopped = dao.byVideoId(videoId) ?: previous
        stopped.contentUri?.let { value -> runCatching { context.contentResolver.delete(android.net.Uri.parse(value), null, null) } }
        dao.upsert(
            stopped.copy(
                status = DownloadStatus.STOPPED,
                contentUri = null,
                downloadedBytes = 0L,
                totalBytes = null,
                errorCode = null,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun clearProbeSessions(postId: String) {
        probeSessions.keys.removeAll { it.postId == postId }
    }

    companion object {
        const val SOURCE_DIRECT = "DIRECT"
        const val SOURCE_IFRAME = "IFRAME"
        const val SOURCE_HISTORICAL = "HISTORICAL"
        private const val PROBE_ORDINAL_BASE = 1_000_000
        private const val PROBE_SLOT_STRIDE = 1_000

        fun snapshotKey(anchorMillis: Long, pageIndex: Int): String = "ranking-v4:$anchorMillis:$pageIndex"

        fun stableVideoId(postId: String, url: String): String =
            MessageDigest.getInstance("SHA-256").digest("$postId|${canonicalMediaKey(url)}".toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        fun stableResolverId(postId: String, url: String): String =
            MessageDigest.getInstance("SHA-256").digest("$postId|resolver|${resolverMediaKey(url)}".toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        fun stableProbedVideoId(postId: String, resolverOrdinal: Int, slot: Int): String =
            MessageDigest.getInstance("SHA-256").digest("$postId|probe|$resolverOrdinal|$slot".toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        internal fun mediaSourceKey(kind: String, url: String): String =
            if (kind == SOURCE_IFRAME) "$SOURCE_IFRAME|${resolverMediaKey(url)}" else canonicalMediaKey(url)

        fun canonicalMediaKey(url: String): String = runCatching {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: "https"
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.rawPath.orEmpty().trimEnd('/')
            "$scheme://$host$path"
        }.getOrElse { url.substringBefore('?') }

        private fun resolverMediaKey(url: String): String = runCatching {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: "https"
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            "$scheme://$host$path$query"
        }.getOrElse { url.substringBefore('#') }

        internal fun reconcileVideoRows(
            existing: List<VideoEntity>,
            fresh: List<VideoEntity>,
            detailUrl: String,
            activeProbeIds: Set<String> = emptySet(),
        ): List<VideoEntity> {
            val freshIds = fresh.mapTo(hashSetOf()) { it.id }
            val reconciled = linkedMapOf<String, VideoEntity>()
            existing.forEach { row ->
                when {
                    row.sourceKind == SOURCE_IFRAME -> Unit
                    row.sourceKind == SOURCE_DIRECT && row.id in activeProbeIds -> reconciled[row.id] = row
                    row.sourceKind == SOURCE_DIRECT && row.referer == detailUrl && row.id in freshIds -> Unit
                    row.sourceKind == SOURCE_DIRECT -> reconciled[row.id] = row.copy(sourceKind = SOURCE_HISTORICAL)
                    else -> reconciled[row.id] = row
                }
            }
            fresh.forEach { reconciled[it.id] = it }
            return reconciled.values.sortedWith(compareBy<VideoEntity> { it.ordinal }.thenBy { it.discoveredAtEpochMillis })
        }

        internal fun reconcileProbeRows(
            existing: List<VideoEntity>,
            preferredLegacyIds: Set<String>,
            postId: String,
            referer: String,
            resolverOrdinal: Int,
            candidates: List<String>,
            now: Long,
        ): List<VideoEntity> {
            val normalized = linkedMapOf<String, String>()
            candidates.filter { it.startsWith("https://") }.forEach { normalized[canonicalMediaKey(it)] = it }
            if (normalized.isEmpty()) return emptyList()

            val resolverRows = existing.filter { probeResolverOrdinal(it.ordinal) == resolverOrdinal }
            val legacyRows = existing
                .filter { isLegacyProbeCandidate(it, resolverOrdinal, referer) }
                .sortedWith(compareByDescending<VideoEntity> { it.id in preferredLegacyIds }.thenBy { it.discoveredAtEpochMillis })
            val usedIds = linkedSetOf<String>()
            val updates = linkedMapOf<String, VideoEntity>()

            normalized.values.forEachIndexed { slot, candidate ->
                val ordinal = probeOrdinal(resolverOrdinal, slot)
                val expectedId = stableProbedVideoId(postId, resolverOrdinal, slot)
                val existingSlot = resolverRows.firstOrNull { probeSlot(it.ordinal) == slot }
                    ?: existing.firstOrNull { it.id == expectedId }
                    ?: if (slot == 0) legacyRows.firstOrNull() else null
                val id = existingSlot?.id ?: expectedId
                usedIds += id
                updates[id] = VideoEntity(
                    id = id,
                    postId = postId,
                    url = candidate,
                    referer = referer,
                    userAgent = AvseeClient.USER_AGENT,
                    sourceKind = SOURCE_DIRECT,
                    ordinal = ordinal,
                    discoveredAtEpochMillis = now,
                )
            }

            (resolverRows + legacyRows).distinctBy { it.id }
                .filter { it.id !in usedIds }
                .forEach { row -> updates[row.id] = row.copy(sourceKind = SOURCE_HISTORICAL, discoveredAtEpochMillis = now) }
            return updates.values.toList()
        }

        private fun isLegacyProbeCandidate(row: VideoEntity, resolverOrdinal: Int, referer: String): Boolean {
            if (row.ordinal != resolverOrdinal || row.sourceKind !in setOf(SOURCE_DIRECT, SOURCE_HISTORICAL)) return false
            return canonicalMediaKey(row.referer) == canonicalMediaKey(referer)
        }

        private fun probeOrdinal(resolverOrdinal: Int, slot: Int): Int =
            PROBE_ORDINAL_BASE + resolverOrdinal * PROBE_SLOT_STRIDE + slot

        private fun probeResolverOrdinal(ordinal: Int): Int? =
            if (ordinal >= PROBE_ORDINAL_BASE) (ordinal - PROBE_ORDINAL_BASE) / PROBE_SLOT_STRIDE else null

        private fun probeSlot(ordinal: Int): Int? =
            if (ordinal >= PROBE_ORDINAL_BASE) (ordinal - PROBE_ORDINAL_BASE) % PROBE_SLOT_STRIDE else null

        internal fun rebaseDetailUrl(originalUrl: String, baseUrl: String): String {
            val original = URI(originalUrl)
            val base = URI(baseUrl)
            require(original.scheme.equals("https", ignoreCase = true))
            require(base.scheme.equals("https", ignoreCase = true))
            val path = original.rawPath?.takeIf { it.startsWith("/") } ?: "/"
            val query = original.rawQuery?.let { "?$it" }.orEmpty()
            return "https://${base.host}$path$query"
        }
    }
}
