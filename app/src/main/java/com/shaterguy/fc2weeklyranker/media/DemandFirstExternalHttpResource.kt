package com.shaterguy.fc2weeklyranker.media

import java.io.IOException
import kotlin.math.min

/**
 * Production-sized proxy reads can be much wider than the decoder's immediate demand.
 * This adapter opens one bounded streaming Range window for normal small reads, returns
 * only the decoder's immediate demand, and reuses the same response for contiguous reads.
 * Any unusual server behavior falls back permanently to the proven
 * SeekableExternalHttpResource path for this resource.
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

    private val delegate = SeekableExternalHttpResource(
        url = url,
        context = context,
        transport = transport,
        chunkSize = chunkSize,
        maxCachedChunks = maxCachedChunks,
    )

    private var lastReadEnd: Long? = null
    private var activeWindow: ExternalRangeHttpStream? = null
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
            invalidateActiveWindow()
        }

        if (delegateOnly || chunkSize < DEMAND_FIRST_MIN_CHUNK_SIZE || wanted >= chunkSize) {
            closeActiveWindow()
            val bytes = delegate.read(offset, wanted)
            lastReadEnd = offset + bytes.size.toLong()
            return bytes
        }

        val bytes = readActiveWindow(offset, wanted)
            ?: openAndReadWindow(offset, wanted, length)
        if (bytes == null) {
            delegateOnly = true
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
        closeActiveWindow()
        delegate.close()
    }

    private fun readActiveWindow(offset: Long, requestedBytes: Int): ByteArray? {
        val window = activeWindow ?: return null
        if (window.position != offset) {
            closeActiveWindow()
            return null
        }

        val bytes = try {
            window.readExact(requestedBytes)
        } catch (_: IOException) {
            closeActiveWindow()
            return null
        }
        if (bytes.size != requestedBytes) {
            closeActiveWindow()
            return null
        }
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

        val window = try {
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
        return readActiveWindow(offset, requestedBytes)
    }

    private fun invalidateActiveWindow() {
        lastReadEnd = null
        closeActiveWindow()
    }

    private fun closeActiveWindow() {
        activeWindow?.close()
        activeWindow = null
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Protected media resource is closed")
    }

    companion object {
        private const val DEMAND_FIRST_MIN_CHUNK_SIZE = 256 * 1024
    }
}
