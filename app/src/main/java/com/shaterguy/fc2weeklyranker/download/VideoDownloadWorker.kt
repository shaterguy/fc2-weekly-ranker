package com.shaterguy.fc2weeklyranker.download

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shaterguy.fc2weeklyranker.AppGraph
import com.shaterguy.fc2weeklyranker.data.DownloadEntity
import com.shaterguy.fc2weeklyranker.network.AvseeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI

class VideoDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return@withContext Result.failure()
        val video = AppGraph.database.videoDao().byId(videoId) ?: return@withContext Result.failure()
        val dao = AppGraph.database.downloadDao()
        val previous = dao.byVideoId(videoId)
        val unsupported = when {
            video.sourceKind != "DIRECT" -> "NOT_DIRECT"
            AvseeClient.isHlsUrl(video.url) -> "HLS_UNSUPPORTED"
            !AvseeClient.isDownloadableMediaUrl(video.url) -> "UNSUPPORTED_MEDIA"
            else -> null
        }
        if (unsupported != null) {
            dao.upsert(state(videoId, "FAILED", previous?.contentUri, previous?.downloadedBytes ?: 0L, previous?.totalBytes, unsupported))
            return@withContext Result.failure(workDataOf("code" to unsupported))
        }

        var uri = previous?.contentUri?.let(android.net.Uri::parse)
        var existingBytes = previous?.downloadedBytes ?: 0L
        if (uri == null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(video.postId, video.ordinal, video.url))
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType(video.url))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FC2 Weekly Ranker")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = applicationContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext Result.retry()
            existingBytes = 0L
        }
        dao.upsert(state(videoId, "RUNNING", uri.toString(), existingBytes, previous?.totalBytes, null))
        try {
            val request = Request.Builder().url(video.url).header("User-Agent", video.userAgent).header("Referer", video.referer).apply {
                CookieManager.getInstance().getCookie(video.url)?.takeIf(String::isNotBlank)?.let { header("Cookie", it) }
                if (existingBytes > 0L) header("Range", "bytes=$existingBytes-")
            }.build()
            AppGraph.httpClient.newCall(request).execute().use { response ->
                if (response.code !in listOf(200, 206)) throw IOException("HTTP_${response.code}")
                val body = response.body
                val append = response.code == 206 && existingBytes > 0L
                if (!append) existingBytes = 0L
                val bodyLength = body.contentLength()
                val total: Long? = when {
                    response.code == 206 && bodyLength >= 0L -> existingBytes + bodyLength
                    response.code == 200 && bodyLength >= 0L -> bodyLength
                    else -> null
                }
                val descriptor = applicationContext.contentResolver.openFileDescriptor(uri!!, "rw") ?: throw IOException("OPEN_FAILED")
                descriptor.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { output ->
                        val channel = output.channel
                        if (append) channel.position(existingBytes) else channel.truncate(0L)
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        var downloaded = existingBytes
                        var lastReported = downloaded
                        body.byteStream().use { input ->
                            while (true) {
                                val read = input.read(buffer); if (read < 0) break
                                output.write(buffer, 0, read); downloaded += read
                                if (downloaded - lastReported >= 1_048_576L) {
                                    dao.upsert(state(videoId, "RUNNING", uri.toString(), downloaded, total, null))
                                    setProgress(workDataOf("downloaded" to downloaded, "total" to (total ?: -1L))); lastReported = downloaded
                                }
                            }
                        }
                        existingBytes = downloaded
                    }
                }
                applicationContext.contentResolver.update(uri!!, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                dao.upsert(state(videoId, "COMPLETED", uri.toString(), existingBytes, total, null))
                Result.success(workDataOf("contentUri" to uri.toString()))
            }
        } catch (error: IOException) {
            dao.upsert(state(videoId, "FAILED", uri.toString(), existingBytes, previous?.totalBytes, error.message?.take(40)))
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("code" to "NETWORK"))
        }
    }

    private fun state(videoId: String, status: String, contentUri: String?, bytes: Long, total: Long?, error: String?) = DownloadEntity(videoId, status, contentUri, bytes, total, error, System.currentTimeMillis())
    private fun fileName(postId: String, ordinal: Int, url: String): String {
        val ext = runCatching { URI(url).path.substringAfterLast('.', "mp4").lowercase() }.getOrDefault("mp4").takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "mp4"
        return "fc2_${postId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)}_${ordinal + 1}.$ext"
    }
    private fun mimeType(url: String): String = if (url.substringBefore('?').lowercase().endsWith(".webm")) "video/webm" else "video/mp4"
    companion object { const val KEY_VIDEO_ID = "video_id" }
}
