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
    fun posts(anchorMillis: Long, pageIndex: Int): Flow<List<PostEntity>> {
        val window = windowFor(Instant.ofEpochMilli(anchorMillis), pageIndex)
        return db.postDao().postsInWindow(window.startInclusive.toEpochMilli(), window.upperInclusive.toEpochMilli())
    }

    fun favorites(): Flow<List<PostEntity>> = db.postDao().favorites()
    fun videos(postId: String): Flow<List<VideoEntity>> = db.videoDao().forPost(postId)
    fun download(videoId: String): Flow<DownloadEntity?> = db.downloadDao().observe(videoId)
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

    suspend fun manualRefresh(): Long { val anchor = settings.refreshAnchor(); refreshPage(0); return anchor }

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

    suspend fun loadVideos(postId: String) {
        val post = db.postDao().byId(postId) ?: return
        val detailUrl = rebaseDetailUrl(post.url, settings.baseUrl.first())
        val detail = source.loadDetail(detailUrl)
        val now = System.currentTimeMillis()
        val entities = detail.media.map { media ->
            val canonical = AvseeClient.canonicalMediaUrl(media.url)
            VideoEntity(stableVideoId(postId, canonical), postId, canonical, media.referer, AvseeClient.USER_AGENT, media.kind, media.ordinal, now)
        }
        db.videoDao().replaceForPost(postId, entities)
    }

    suspend fun registerProbedVideo(postId: String, wrapperVideoId: String, url: String, referer: String, ordinal: Int) {
        if (!url.startsWith("https://", ignoreCase = true)) return
        val canonical = AvseeClient.canonicalMediaUrl(url)
        val directId = stableVideoId(postId, canonical)
        db.videoDao().upsert(listOf(VideoEntity(directId, postId, canonical, referer, AvseeClient.USER_AGENT, "DIRECT", ordinal, System.currentTimeMillis())))
        if (wrapperVideoId != directId) db.videoDao().deleteById(wrapperVideoId)
    }

    suspend fun queueDownload(videoId: String) {
        val video = db.videoDao().byId(videoId) ?: return
        val dao = db.downloadDao()
        val previous = dao.byVideoId(videoId)
        if (previous?.status == "COMPLETED") return
        val unsupported = when {
            video.sourceKind != "DIRECT" -> "NOT_DIRECT"
            AvseeClient.isHlsUrl(video.url) -> "HLS_UNSUPPORTED"
            !AvseeClient.isDownloadableMediaUrl(video.url) -> "UNSUPPORTED_MEDIA"
            else -> null
        }
        if (unsupported != null) {
            dao.upsert(DownloadEntity(videoId, "FAILED", previous?.contentUri, previous?.downloadedBytes ?: 0L, previous?.totalBytes, unsupported, System.currentTimeMillis()))
            return
        }
        dao.upsert(DownloadEntity(videoId, "ENQUEUED", previous?.contentUri, previous?.downloadedBytes ?: 0L, previous?.totalBytes, null, System.currentTimeMillis()))
        val request = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(VideoDownloadWorker.KEY_VIDEO_ID to videoId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("video-download-$videoId", ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        fun snapshotKey(anchorMillis: Long, pageIndex: Int): String = "$anchorMillis:$pageIndex"
        fun stableVideoId(postId: String, url: String): String {
            val canonical = AvseeClient.canonicalMediaUrl(url)
            return MessageDigest.getInstance("SHA-256").digest("$postId|$canonical".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        }

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
