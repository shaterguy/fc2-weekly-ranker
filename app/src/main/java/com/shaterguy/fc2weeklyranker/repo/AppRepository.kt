package com.shaterguy.fc2weeklyranker.repo

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.shaterguy.fc2weeklyranker.data.AppDatabase
import com.shaterguy.fc2weeklyranker.data.DownloadEntity
import com.shaterguy.fc2weeklyranker.data.FavoriteEntity
import com.shaterguy.fc2weeklyranker.data.PostEntity
import com.shaterguy.fc2weeklyranker.data.SettingsStore
import com.shaterguy.fc2weeklyranker.data.VideoEntity
import com.shaterguy.fc2weeklyranker.domain.RankCandidate
import com.shaterguy.fc2weeklyranker.domain.rank
import com.shaterguy.fc2weeklyranker.domain.windowFor
import com.shaterguy.fc2weeklyranker.download.VideoDownloadWorker
import com.shaterguy.fc2weeklyranker.network.AvseeClient
import com.shaterguy.fc2weeklyranker.network.BaseUrlPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

class AppRepository(private val context: Context, private val db: AppDatabase, val settings: SettingsStore, private val source: AvseeClient) {
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
        val detailUrl = rebaseDetailUrl(post.url, settings.baseUrl.first())
        val detail = source.loadDetail(detailUrl)
        val now = System.currentTimeMillis()
        val seenMedia = linkedSetOf<String>()
        val entities = detail.media
            .filter { it.url.startsWith("https://") }
            .filter { seenMedia.add(canonicalMediaKey(it.url)) }
            .map { media ->
                VideoEntity(
                    stableVideoId(postId, media.url),
                    postId,
                    media.url,
                    media.referer,
                    AvseeClient.USER_AGENT,
                    media.kind,
                    media.ordinal,
                    now,
                )
            }
        db.videoDao().replaceForPost(postId, entities)
    }

    suspend fun registerProbedVideo(postId: String, url: String, referer: String, ordinal: Int) {
        if (!url.startsWith("https://")) return
        db.videoDao().upsert(
            listOf(
                VideoEntity(
                    stableVideoId(postId, url),
                    postId,
                    url,
                    referer,
                    AvseeClient.USER_AGENT,
                    "DIRECT",
                    ordinal,
                    System.currentTimeMillis(),
                ),
            ),
        )
    }

    suspend fun queueDownload(videoId: String) {
        val video = db.videoDao().byId(videoId) ?: return
        val dao = db.downloadDao()
        val previous = dao.byVideoId(videoId)
        if (previous?.status in setOf("QUEUED", "RUNNING", "COMPLETED")) return
        if (!VideoDownloadWorker.supportsFileDownload(video.url)) {
            dao.upsert(downloadState(videoId, "FAILED", previous?.contentUri, previous?.downloadedBytes ?: 0L, previous?.totalBytes, "HLS_OFFLINE_UNSUPPORTED"))
            return
        }
        dao.upsert(downloadState(videoId, "QUEUED", previous?.contentUri, previous?.downloadedBytes ?: 0L, previous?.totalBytes, null))
        val request = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(VideoDownloadWorker.KEY_VIDEO_ID to videoId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("video-download-$videoId", ExistingWorkPolicy.KEEP, request)
    }

    private fun downloadState(videoId: String, status: String, contentUri: String?, downloadedBytes: Long, totalBytes: Long?, errorCode: String?) =
        DownloadEntity(videoId, status, contentUri, downloadedBytes, totalBytes, errorCode, System.currentTimeMillis())

    companion object {
        fun snapshotKey(anchorMillis: Long, pageIndex: Int): String = "ranking-v4:$anchorMillis:$pageIndex"

        fun stableVideoId(postId: String, url: String): String =
            MessageDigest.getInstance("SHA-256").digest("$postId|${canonicalMediaKey(url)}".toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        fun canonicalMediaKey(url: String): String = runCatching {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: "https"
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.rawPath.orEmpty().trimEnd('/')
            "$scheme://$host$path"
        }.getOrElse { url.substringBefore('?') }

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
