package com.shaterguy.fc2weeklyranker.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import com.shaterguy.fc2weeklyranker.AppGraph
import com.shaterguy.fc2weeklyranker.data.DownloadDao
import com.shaterguy.fc2weeklyranker.data.DownloadEntity
import com.shaterguy.fc2weeklyranker.data.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal enum class DownloadRunResult { SUCCESS, RETRY, NOOP, FAILURE }

private object DownloadTransferGate {
    private val permits = Semaphore(DownloadRuntimePolicy.MAX_CONCURRENT_TRANSFERS)
    private val mutex = Mutex()
    private val activeVideoIds = mutableSetOf<String>()

    suspend fun <T> withSlot(videoId: String, block: suspend () -> T): T? = permits.withPermit {
        val claimed = mutex.withLock { activeVideoIds.add(videoId) }
        if (!claimed) return@withPermit null
        try {
            block()
        } finally {
            mutex.withLock { activeVideoIds.remove(videoId) }
        }
    }
}

internal object DownloadTransferRunner {
    private const val PROGRESS_STEP_BYTES = 1_048_576L
    private const val MAX_RETRIES = 3
    private val activeCalls = ConcurrentHashMap<String, Call>()

    fun cancel(videoId: String) {
        activeCalls[videoId]?.cancel()
    }

    suspend fun run(
        context: Context,
        videoId: String,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): DownloadRunResult = withContext(Dispatchers.IO) {
        DownloadTransferGate.withSlot(videoId) {
            runInSlot(context, videoId, onProgress)
        } ?: DownloadRunResult.NOOP
    }

    private suspend fun runInSlot(
        context: Context,
        videoId: String,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
    ): DownloadRunResult {
        val video = AppGraph.database.videoDao().byId(videoId) ?: return DownloadRunResult.FAILURE
        val dao = AppGraph.database.downloadDao()
        var previous = dao.byVideoId(videoId) ?: return DownloadRunResult.FAILURE

        if (previous.status == DownloadStatus.FINALIZING) return finalizePending(context, dao, previous)
        if (previous.status !in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING)) return DownloadRunResult.NOOP
        if (video.sourceKind != "DIRECT") {
            dao.transitionStatus(
                videoId,
                listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
                DownloadStatus.FAILED,
                "NOT_DIRECT",
                System.currentTimeMillis(),
            )
            return DownloadRunResult.FAILURE
        }
        if (!VideoDownloadWorker.supportsFileDownload(video.url)) {
            dao.transitionStatus(
                videoId,
                listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
                DownloadStatus.FAILED,
                "HLS_OFFLINE_UNSUPPORTED",
                System.currentTimeMillis(),
            )
            return DownloadRunResult.FAILURE
        }

        val started = dao.transitionStatus(
            videoId,
            listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
            DownloadStatus.RUNNING,
            null,
            System.currentTimeMillis(),
        )
        if (started == 0) return DownloadRunResult.NOOP
        previous = dao.byVideoId(videoId) ?: return DownloadRunResult.FAILURE

        var uri = previous.contentUri?.let(Uri::parse)
        var existingBytes = previous.downloadedBytes
        if (uri == null) {
            if (dao.byVideoId(videoId)?.status != DownloadStatus.RUNNING) return DownloadRunResult.NOOP
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, VideoDownloadWorker.outputFileName(video.postId, video.ordinal, video.url))
                put(MediaStore.MediaColumns.MIME_TYPE, VideoDownloadWorker.mediaMimeType(video.url))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Weekly Ranker")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val createdUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return retryOrFail(dao, videoId, "MEDIASTORE_INSERT_FAILED")
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
                runCatching { context.contentResolver.delete(createdUri, null, null) }
                return DownloadRunResult.NOOP
            }
        }
        val targetUri = uri ?: return retryOrFail(dao, videoId, "MISSING_CONTENT_URI")

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
            val call = AppGraph.httpClient.newCall(request)
            activeCalls[videoId] = call
            try {
                call.execute().use { response ->
                    if (response.code !in listOf(200, 206)) throw IOException("HTTP_${response.code}")
                    val body = response.body
                    val append = response.code == 206 && existingBytes > 0L
                    if (!append) existingBytes = 0L
                    val responseLength = body.contentLength()
                    val total: Long? = when {
                        response.code == 206 && responseLength >= 0L -> existingBytes + responseLength
                        response.code == 206 -> null
                        responseLength >= 0L -> responseLength
                        else -> null
                    }
                    val descriptor = context.contentResolver.openFileDescriptor(targetUri, "rw") ?: throw IOException("OPEN_FAILED")
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
                                            onProgress(downloaded, total)
                                            lastReported = downloaded
                                        }
                                    }
                                }
                                existingBytes = downloaded
                            }
                        }
                    }
                    if (interrupted) return DownloadRunResult.NOOP

                    val finalProgress = dao.updateProgressIfStatus(
                        videoId,
                        DownloadStatus.RUNNING,
                        targetUri.toString(),
                        existingBytes,
                        total,
                        System.currentTimeMillis(),
                    )
                    if (finalProgress == 0) return DownloadRunResult.NOOP
                    onProgress(existingBytes, total)
                    val finalizing = dao.transitionStatus(
                        videoId,
                        listOf(DownloadStatus.RUNNING),
                        DownloadStatus.FINALIZING,
                        null,
                        System.currentTimeMillis(),
                    )
                    if (finalizing == 0) return DownloadRunResult.NOOP
                    val finalState = dao.byVideoId(videoId) ?: return DownloadRunResult.FAILURE
                    return finalizePending(context, dao, finalState)
                }
            } finally {
                activeCalls.remove(videoId, call)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { markInterrupted(dao, videoId) }
            throw cancelled
        } catch (error: IOException) {
            if (!currentCoroutineContext().isActive) {
                withContext(NonCancellable) { markInterrupted(dao, videoId) }
                throw CancellationException("download interrupted").also { it.initCause(error) }
            }
            return retryOrFail(dao, videoId, error.message?.take(40) ?: "NETWORK")
        } catch (error: Exception) {
            dao.transitionStatus(
                videoId,
                listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.FINALIZING),
                DownloadStatus.FAILED,
                error.message?.take(40) ?: "DOWNLOAD_ERROR",
                System.currentTimeMillis(),
            )
            return DownloadRunResult.FAILURE
        }
    }

    private suspend fun finalizePending(context: Context, dao: DownloadDao, state: DownloadEntity): DownloadRunResult {
        val uri = state.contentUri?.let(Uri::parse) ?: run {
            dao.transitionStatus(
                state.videoId,
                listOf(DownloadStatus.FINALIZING),
                DownloadStatus.FAILED,
                "MISSING_CONTENT_URI",
                System.currentTimeMillis(),
            )
            return DownloadRunResult.FAILURE
        }
        return try {
            val updated = context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            if (updated <= 0) throw IOException("PUBLISH_FAILED")
            dao.transitionStatus(
                state.videoId,
                listOf(DownloadStatus.FINALIZING),
                DownloadStatus.COMPLETED,
                null,
                System.currentTimeMillis(),
            )
            DownloadRunResult.SUCCESS
        } catch (error: IOException) {
            retryOrFail(dao, state.videoId, error.message?.take(40) ?: "PUBLISH_FAILED")
        }
    }

    private suspend fun retryOrFail(dao: DownloadDao, videoId: String, code: String): DownloadRunResult {
        val current = dao.byVideoId(videoId) ?: return DownloadRunResult.FAILURE
        return when (current.status) {
            DownloadStatus.PAUSED, DownloadStatus.STOPPED -> DownloadRunResult.NOOP
            DownloadStatus.RUNNING, DownloadStatus.FINALIZING -> {
                val retryStatus = if (current.status == DownloadStatus.RUNNING) DownloadStatus.QUEUED else DownloadStatus.FINALIZING
                val recorded = dao.recordRetryIfStatus(
                    videoId = videoId,
                    fromStatus = current.status,
                    toStatus = retryStatus,
                    errorCode = code,
                    updatedAt = System.currentTimeMillis(),
                    maxRetries = MAX_RETRIES,
                )
                if (recorded > 0) {
                    DownloadRunResult.RETRY
                } else {
                    val refreshed = dao.byVideoId(videoId)
                    if (refreshed?.status == current.status && refreshed.retryCount >= MAX_RETRIES) {
                        dao.transitionStatus(
                            videoId,
                            listOf(current.status),
                            DownloadStatus.FAILED,
                            code,
                            System.currentTimeMillis(),
                        )
                        DownloadRunResult.FAILURE
                    } else {
                        DownloadRunResult.NOOP
                    }
                }
            }
            DownloadStatus.QUEUED -> DownloadRunResult.RETRY
            else -> DownloadRunResult.FAILURE
        }
    }

    private suspend fun markInterrupted(dao: DownloadDao, videoId: String) {
        dao.transitionStatus(
            videoId,
            listOf(DownloadStatus.RUNNING),
            DownloadStatus.QUEUED,
            "INTERRUPTED",
            System.currentTimeMillis(),
        )
    }
}
