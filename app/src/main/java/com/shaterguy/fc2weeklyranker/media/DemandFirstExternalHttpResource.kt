package com.shaterguy.fc2weeklyranker.media

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Production-sized proxy reads can be much wider than the decoder's immediate demand.
 * This adapter serves normal full-206 small reads at decoder demand size first, then
 * asynchronously warms the next same-sized window. Any unusual server behavior falls
 * back permanently to the proven SeekableExternalHttpResource path for this resource.
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

    private data class PrefetchTask(
        val generation: Long,
        val startOffset: Long,
        val requestedBytes: Int,
        val future: CompletableFuture<ByteArray?>,
    )

    private val delegate = SeekableExternalHttpResource(
        url = url,
        context = context,
        transport = transport,
        chunkSize = chunkSize,
        maxCachedChunks = maxCachedChunks,
    )

    private var generation = 0L
    private var lastReadEnd: Long? = null
    private var prefetch: PrefetchTask? = null
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
            invalidateReadAhead()
        }

        if (delegateOnly || chunkSize < DEMAND_FIRST_MIN_CHUNK_SIZE || wanted >= chunkSize) {
            cancelPrefetch()
            val bytes = delegate.read(offset, wanted)
            lastReadEnd = offset + bytes.size.toLong()
            return bytes
        }

        val prefetched = takePrefetched(offset, wanted)
        val bytes = prefetched ?: loadExactDemand(offset, wanted, length)
        if (bytes == null) {
            delegateOnly = true
            cancelPrefetch()
            val fallback = delegate.read(offset, wanted)
            lastReadEnd = offset + fallback.size.toLong()
            return fallback
        }

        val nextOffset = offset + bytes.size.toLong()
        lastReadEnd = nextOffset
        scheduleReadAhead(nextOffset, wanted, length)
        return bytes
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        generation += 1L
        lastReadEnd = null
        cancelPrefetch()
        delegate.close()
    }

    private fun takePrefetched(offset: Long, requestedBytes: Int): ByteArray? {
        val task = prefetch ?: return null
        if (
            task.generation != generation ||
            task.startOffset != offset ||
            task.requestedBytes != requestedBytes
        ) {
            return null
        }
        val bytes = try {
            task.future.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        }
        if (prefetch === task) prefetch = null
        return bytes?.takeIf { it.size == requestedBytes }
    }

    private fun loadExactDemand(offset: Long, requestedBytes: Int, length: Long): ByteArray? {
        return try {
            val range = transport.readRange(url, context, offset, requestedBytes)
            when {
                range.totalLength != null && range.totalLength != length -> null
                !range.rangeHonored -> null
                range.bytes.size != requestedBytes -> null
                else -> range.bytes
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun scheduleReadAhead(startOffset: Long, requestedBytes: Int, length: Long) {
        if (closed || delegateOnly || startOffset >= length || requestedBytes <= 0) return
        val nextBytes = min(requestedBytes.toLong(), length - startOffset).toInt()
        if (nextBytes <= 0) return

        val existing = prefetch
        if (
            existing != null &&
            existing.generation == generation &&
            existing.startOffset == startOffset &&
            existing.requestedBytes == nextBytes
        ) {
            return
        }
        cancelPrefetch()

        val taskGeneration = generation
        val future = CompletableFuture.supplyAsync(
            {
                if (taskGeneration != generation || Thread.currentThread().isInterrupted) {
                    null
                } else {
                    loadExactDemand(startOffset, nextBytes, length)
                }
            },
            PREFETCH_EXECUTOR,
        )
        prefetch = PrefetchTask(taskGeneration, startOffset, nextBytes, future)
    }

    private fun invalidateReadAhead() {
        generation += 1L
        lastReadEnd = null
        cancelPrefetch()
    }

    private fun cancelPrefetch() {
        prefetch?.future?.cancel(true)
        prefetch = null
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Protected media resource is closed")
    }

    companion object {
        private const val DEMAND_FIRST_MIN_CHUNK_SIZE = 256 * 1024
        private val PREFETCH_THREAD_ID = AtomicInteger(0)
        private val PREFETCH_EXECUTOR = Executors.newFixedThreadPool(
            2,
            ThreadFactory { runnable ->
                Thread(runnable, "external-media-demand-read-ahead-${PREFETCH_THREAD_ID.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        )
    }
}
