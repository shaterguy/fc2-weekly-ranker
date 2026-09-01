package com.shaterguy.fc2weeklyranker.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLDecoder

class VideoDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return@withContext Result.failure()
        try {
            setForeground(DownloadNotifications.foregroundInfo(applicationContext, videoId))
        } catch (_: IllegalStateException) {
            return@withContext Result.retry()
        }

        when (
            DownloadTransferRunner.run(applicationContext, videoId) { downloaded, total ->
                setProgress(workDataOf("downloaded" to downloaded, "total" to (total ?: -1L)))
                setForeground(DownloadNotifications.foregroundInfo(applicationContext, videoId, downloaded, total))
            }
        ) {
            DownloadRunResult.SUCCESS, DownloadRunResult.NOOP -> Result.success()
            DownloadRunResult.RETRY -> Result.retry()
            DownloadRunResult.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val KEY_VIDEO_ID = "video_id"

        fun workName(videoId: String): String = "video-download-$videoId"

        fun supportsFileDownload(url: String): Boolean =
            !runCatching { URI(url).path.orEmpty().lowercase().endsWith(".m3u8") }.getOrDefault(false)

        fun outputFileName(postId: String, ordinal: Int, url: String): String {
            originalBasename(url)?.let { return it }
            val ext = runCatching { URI(url).path.substringAfterLast('.', "mp4").lowercase() }
                .getOrDefault("mp4")
                .takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
                ?: "mp4"
            return "weekly_ranker_${postId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)}_${ordinal + 1}.$ext"
        }

        fun mediaMimeType(url: String): String =
            if (url.substringBefore('?').lowercase().endsWith(".webm")) "video/webm" else "video/mp4"

        private fun originalBasename(url: String): String? = runCatching {
            val rawName = URI(url).rawPath.orEmpty().substringAfterLast('/')
            if (rawName.isBlank()) return@runCatching null
            URLDecoder.decode(rawName.replace("+", "%2B"), "UTF-8")
        }.getOrNull()?.takeIf(::isSafeBasename)

        private fun isSafeBasename(name: String): Boolean =
            name.isNotBlank() &&
                name != "." && name != ".." && name.length <= 240 &&
                name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }
    }
}
