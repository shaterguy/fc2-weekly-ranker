package com.shaterguy.fc2weeklyranker.media

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Production-sized proxy reads can be much wider than the decoder's immediate demand.
 * This adapter opens one bounded streaming Range window for normal small reads, returns
 * only the decoder's immediate demand, and reuses the same response for contiguous reads.
 * Near the end of a window it pre-opens exactly one following bounded window so Range
 * connection setup stays off the decoder's rollover read. Any unusual server behavior
 * falls back permanently to the proven SeekableExternalHttpResource path.
 */
internal class DemandFirstExternalHttpResource(
    private val url: String,
    private val context: ExternalVideoRequestContext,
    private val transport: ExternalHttpTransport,
    private val chunkSize: Int,
    maxCachedChunks: Int,
) : AutoCloseable {
    init {
        require(chunkSize > 0)
        require(maxCachedChunks > 0)
    }

    private data class ReadAheadWindow(
        val generation: Long,
        val startOffset: Long,
        val requestedBytes: Int,
        val future: CompletableFuture<ExternalRangeHttpStream?>,
    )

    private val delegate = SeekableExternalHttpResource(
        url = url,
        context = context,
        transport = transport,
        chunkSize = chunkSize,
        maxCachedChunks = maxCachedChunks,
    )

    @Volatile
    private var generation = 0L
    private var lastReadEnd: Long? = null
    private var activeWindow: ExternalRangeHttpStream? = null
    private var activeWindowEndExclusive: Long? = null
    private var readAheadWindow: ReadAheadWindow? = null
    private var delegateOnly = false
    private var closed = false

    @Synchronized
    fun size(): Long {
        ensureOpen()
        return delegate.size()
    }

    @Synchronized
    fun read(offset: Long, size: Int): ByteArray {
        require(offset >= 0L)
        require(size > 0)
        ensureOpen()

        val length = delegate.size()
        if (offset >= length) return ByteArray(0)
        val wanted = min(size.toLong(), length - offset).toInt()

        if (lastReadEnd != null && lastReadEnd != offset) {
            invalidateStreamingState()
        }

        if (delegateOnly || chunkSize < DEMAND_FIRST_MIN_CHUNK_SIZE || wanted >= chunkSize) {
            abandonReadAhead()
            closeActiveWindow()
            val bytes = delegate.read(offset, wanted)
            lastReadEnd = offset + bytes.size.toLong()
            return bytes
        }

        val bytes = readActiveWindow(offset, wanted, length)
            ?: openAndReadWindow(offset, wanted, length)
        if (bytes == null) {
            delegateOnly = true
            abandonReadAhead()
            closeActiveWindow()
            val fallback = delegate.read(offset, wanted)
            lastReadEnd = offset + fallback.size.toLong()
            return fallback
        }

        lastReadEnd = offset + bytes.size.toLong()
        return bytes
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        lastReadEnd = null
        abandonReadAhead()
        closeActiveWindow()
        delegate.close()
    }

    private fun readActiveWindow(
        offset: Long,
        requestedBytes: Int,
        length: Long,
    ): ByteArray? {
        val window = activeWindow ?: return null
        if (window.position != offset) {
            invalidateStreamingState()
            return null
        }

        val bytes = try {
            window.readExact(requestedBytes)
        } catch (_: IOException) {
            abandonReadAhead()
            closeActiveWindow()
            return null
        }
        if (bytes.size != requestedBytes) {
            abandonReadAhead()
            closeActiveWindow()
            return null
        }

        scheduleReadAhead(length)
        if (window.exhausted) {
            closeActiveWindow()
        }
        return bytes
    }

    private fun openAndReadWindow(
        offset: Long,
        requestedBytes: Int,
        length: Long,
    ): ByteArray? {
        val windowBytes = min(chunkSize.toLong(), length - offset).toInt()
        if (windowBytes <= 0 || requestedBytes > windowBytes) return null

        val window = takeReadAhead(offset, windowBytes, length) ?: try {
            transport.openRangeStream(
                url = url,
                context = context,
                offset = offset,
                size = windowBytes,
            )
        } catch (_: IOException) {
            null
        } ?: return null

        if (window.totalLength != null && window.totalLength != length) {
            window.close()
            return null
        }

        activeWindow = window
        activeWindowEndExclusive = offset + windowBytes.toLong()
        return readActiveWindow(offset, requestedBytes, length)
    }

    private fun scheduleReadAhead(length: Long) {
        if (closed || delegateOnly || chunkSize < DEMAND_FIRST_MIN_CHUNK_SIZE) return
        if (readAheadWindow != null) return

        val window = activeWindow ?: return
        val nextOffset = activeWindowEndExclusive ?: return
        val remainingBytes = nextOffset - window.position
        if (remainingBytes > READ_AHEAD_TRIGGER_BYTES) return
        if (nextOffset >= length) return
        val requestedBytes = min(chunkSize.toLong(), length - nextOffset).toInt()
        if (requestedBytes <= 0) return

        val expectedGeneration = generation
        val future = CompletableFuture.supplyAsync(
            {
                val stream = try {
                    transport.openRangeStream(
                        url = url,
                        context = context,
                        offset = nextOffset,
                        size = requestedBytes,
                    )
                } catch (_: IOException) {
                    null
                }
                if (
                    stream != null &&
                    (
                        generation != expectedGeneration ||
                            (stream.totalLength != null && stream.totalLength != length)
                        )
                ) {
                    stream.close()
                    null
                } else {
                    stream
                }
            },
            READ_AHEAD_EXECUTOR,
        )
        readAheadWindow = ReadAheadWindow(
            generation = expectedGeneration,
            startOffset = nextOffset,
            requestedBytes = requestedBytes,
            future = future,
        )
    }

    private fun takeReadAhead(
        offset: Long,
        requestedBytes: Int,
        length: Long,
    ): ExternalRangeHttpStream? {
        val task = readAheadWindow ?: return null
        if (
            task.generation != generation ||
            task.startOffset != offset ||
            task.requestedBytes != requestedBytes
        ) {
            return null
        }
        readAheadWindow = null

        val stream = try {
            task.future.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: ExecutionException) {
            null
        } ?: return null

        if (
            stream.position != offset ||
            (stream.totalLength != null && stream.totalLength != length)
        ) {
            stream.close()
            return null
        }
        return stream
    }

    private fun invalidateStreamingState() {
        lastReadEnd = null
        abandonReadAhead()
        closeActiveWindow()
    }

    private fun abandonReadAhead() {
        generation += 1L
        val task = readAheadWindow
        readAheadWindow = null
        if (task != null && task.future.isDone) {
            runCatching { task.future.getNow(null)?.close() }
        }
    }

    private fun closeActiveWindow() {
        activeWindow?.close()
        activeWindow = null
        activeWindowEndExclusive = null
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Protected media resource is closed")
    }

    companion object {
        private const val DEMAND_FIRST_MIN_CHUNK_SIZE = 256 * 1024
        private const val READ_AHEAD_TRIGGER_BYTES = 256 * 1024L
        private val READ_AHEAD_THREAD_ID = AtomicInteger(0)
        private val READ_AHEAD_EXECUTOR = Executors.newFixedThreadPool(
            2,
            ThreadFactory { runnable ->
                Thread(
                    runnable,
                    "external-media-demand-read-ahead-${READ_AHEAD_THREAD_ID.incrementAndGet()}",
                ).apply {
                    isDaemon = true
                }
            },
        )
    }
}
