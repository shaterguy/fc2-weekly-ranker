package com.shaterguy.fc2weeklyranker.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shaterguy.fc2weeklyranker.AppGraph
import com.shaterguy.fc2weeklyranker.data.DownloadDao
import com.shaterguy.fc2weeklyranker.data.DownloadEntity
import com.shaterguy.fc2weeklyranker.data.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

class VideoDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return@withContext Result.failure()
        val video = AppGraph.database.videoDao().byId(videoId) ?: return@withContext Result.failure()
        val dao = AppGraph.database.downloadDao()
        var previous = dao.byVideoId(videoId) ?: return@withContext Result.failure()

        if (previous.status == DownloadStatus.FINALIZING) {
            return@withContext finalizePending(dao, previous)
        }
        if (video.sourceKind != "DIRECT") {
            dao.transitionStatus(
                videoId,
                listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
                DownloadStatus.FAILED,
                "NOT_DIRECT",
                System.currentTimeMillis(),
            )
            return@withContext Result.failure(workDataOf("code" to "NOT_DIRECT"))
        }
        if (!supportsFileDownload(video.url)) {
            dao.transitionStatus(
                videoId,
                listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
                DownloadStatus.FAILED,
                "HLS_OFFLINE_UNSUPPORTED",
                System.currentTimeMillis(),
            )
            return@withContext Result.failure(workDataOf("code" to "HLS_OFFLINE_UNSUPPORTED"))
        }
        if (isStopped) return@withContext Result.success()

        val started = dao.transitionStatus(
            videoId,
            listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
            DownloadStatus.RUNNING,
            null,
            System.currentTimeMillis(),
        )
        if (started == 0) return@withContext Result.success()
        previous = dao.byVideoId(videoId) ?: return@withContext Result.failure()

        var uri = previous.contentUri?.let(Uri::parse)
        var existingBytes = previous.downloadedBytes
        if (uri == null) {
            if (isStopped || dao.byVideoId(videoId)?.status != DownloadStatus.RUNNING) return@withContext Result.success()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, outputFileName(video.postId, video.ordinal, video.url))
                put(MediaStore.MediaColumns.MIME_TYPE, mediaMimeType(video.url))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Weekly Ranker")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val createdUri = applicationContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext retryOrFail(dao, videoId, "MEDIASTORE_INSERT_FAILED")
            uri = createdUri
            existingBytes = 0L
            val persisted = dao.updateProgressIfStatus(
                videoId,
                DownloadStatus.RUNNING,
                createdUri.toString(),
                0L,
                previous.totalBytes,
                System.currentTimeMillis(),
            )
            if (persisted == 0) {
                runCatching { applicationContext.contentResolver.delete(createdUri, null, null) }
                return@withContext Result.success()
            }
        }
        val targetUri = uri ?: return@withContext retryOrFail(dao, videoId, "MISSING_CONTENT_URI")

        try {
            val request = Request.Builder()
                .url(video.url)
                .header("User-Agent", video.userAgent)
                .header("Referer", video.referer)
                .apply {
                    CookieManager.getInstance().getCookie(video.url)?.takeIf(String::isNotBlank)?.let { header("Cookie", it) }
                    if (existingBytes > 0L) header("Range", "bytes=$existingBytes-")
                }
                .build()
            AppGraph.httpClient.newCall(request).execute().use { response ->
                if (response.code !in listOf(200, 206)) throw IOException("HTTP_${response.code}")
                val body = response.body
                val append = response.code == 206 && existingBytes > 0L
                if (!append) existingBytes = 0L
                val total: Long? = when {
                    response.code == 206 -> existingBytes + body.contentLength().coerceAtLeast(0L)
                    body.contentLength() >= 0L -> body.contentLength()
                    else -> null
                }
                val descriptor = applicationContext.contentResolver.openFileDescriptor(targetUri, "rw") ?: throw IOException("OPEN_FAILED")
                var interrupted = false
                descriptor.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { output ->
                        val channel = output.channel
                        if (append) {
                            channel.truncate(existingBytes)
                            channel.position(existingBytes)
                        } else {
                            channel.truncate(0L)
                            val reset = dao.updateProgressIfStatus(
                                videoId,
                                DownloadStatus.RUNNING,
                                targetUri.toString(),
                                0L,
                                total,
                                System.currentTimeMillis(),
                            )
                            if (reset == 0) interrupted = true
                        }
                        if (!interrupted) {
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                            var downloaded = existingBytes
                            var lastReported = downloaded
                            body.byteStream().use { input ->
                                while (!interrupted) {
                                    if (isStopped) {
                                        interrupted = true
                                        break
                                    }
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                                        val saved = dao.updateProgressIfStatus(
                                            videoId,
                                            DownloadStatus.RUNNING,
                                            targetUri.toString(),
                                            downloaded,
                                            total,
                                            System.currentTimeMillis(),
                                        )
                                        if (saved == 0) {
                                            interrupted = true
                                            break
                                        }
                                        setProgress(workDataOf("downloaded" to downloaded, "total" to (total ?: -1L)))
                                        lastReported = downloaded
                                    }
                                }
                            }
                            existingBytes = downloaded
                        }
                    }
                }
                if (interrupted) return@withContext Result.success()

                val finalProgress = dao.updateProgressIfStatus(
                    videoId,
                    DownloadStatus.RUNNING,
                    targetUri.toString(),
                    existingBytes,
                    total,
                    System.currentTimeMillis(),
                )
                if (finalProgress == 0) return@withContext Result.success()
                val finalizing = dao.transitionStatus(
                    videoId,
                    listOf(DownloadStatus.RUNNING),
                    DownloadStatus.FINALIZING,
                    null,
                    System.currentTimeMillis(),
                )
                if (finalizing == 0) return@withContext Result.success()
                val finalState = dao.byVideoId(videoId) ?: return@withContext Result.failure()
                return@withContext finalizePending(dao, finalState)
            }
        } catch (error: IOException) {
            return@withContext retryOrFail(dao, videoId, error.message?.take(40) ?: "NETWORK")
        }
    }

    private suspend fun finalizePending(dao: DownloadDao, state: DownloadEntity): Result {
        val uri = state.contentUri?.let(Uri::parse) ?: run {
            dao.transitionStatus(
                state.videoId,
                listOf(DownloadStatus.FINALIZING),
                DownloadStatus.FAILED,
                "MISSING_CONTENT_URI",
                System.currentTimeMillis(),
            )
            return Result.failure(workDataOf("code" to "MISSING_CONTENT_URI"))
        }
        if (isStopped) return Result.success()
        return try {
            val updated = applicationContext.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            if (updated <= 0) throw IOException("PUBLISH_FAILED")
            val completed = dao.transitionStatus(
                state.videoId,
                listOf(DownloadStatus.FINALIZING),
                DownloadStatus.COMPLETED,
                null,
                System.currentTimeMillis(),
            )
            if (completed == 0) Result.success() else Result.success(workDataOf("contentUri" to uri.toString()))
        } catch (error: IOException) {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                dao.transitionStatus(
                    state.videoId,
                    listOf(DownloadStatus.FINALIZING),
                    DownloadStatus.FAILED,
                    error.message?.take(40) ?: "PUBLISH_FAILED",
                    System.currentTimeMillis(),
                )
                Result.failure(workDataOf("code" to "PUBLISH_FAILED"))
            }
        }
    }

    private suspend fun retryOrFail(dao: DownloadDao, videoId: String, code: String): Result {
        val current = dao.byVideoId(videoId) ?: return Result.failure(workDataOf("code" to code))
        return when (current.status) {
            DownloadStatus.PAUSED, DownloadStatus.STOPPED -> Result.success()
            DownloadStatus.FINALIZING -> {
                if (runAttemptCount < MAX_RETRIES) Result.retry() else {
                    dao.transitionStatus(
                        videoId,
                        listOf(DownloadStatus.FINALIZING),
                        DownloadStatus.FAILED,
                        code,
                        System.currentTimeMillis(),
                    )
                    Result.failure(workDataOf("code" to code))
                }
            }
            DownloadStatus.RUNNING -> {
                if (runAttemptCount < MAX_RETRIES) {
                    dao.transitionStatus(
                        videoId,
                        listOf(DownloadStatus.RUNNING),
                        DownloadStatus.QUEUED,
                        code,
                        System.currentTimeMillis(),
                    )
                    Result.retry()
                } else {
                    dao.transitionStatus(
                        videoId,
                        listOf(DownloadStatus.RUNNING),
                        DownloadStatus.FAILED,
                        code,
                        System.currentTimeMillis(),
                    )
                    Result.failure(workDataOf("code" to code))
                }
            }
            DownloadStatus.QUEUED -> Result.retry()
            else -> Result.failure(workDataOf("code" to code))
        }
    }

    companion object {
        const val KEY_VIDEO_ID = "video_id"
        private const val PROGRESS_STEP_BYTES = 262_144L
        private const val MAX_RETRIES = 3

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
