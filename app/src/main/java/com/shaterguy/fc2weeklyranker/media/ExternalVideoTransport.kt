package com.shaterguy.fc2weeklyranker.media

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.LinkedHashMap
import kotlin.math.min

internal data class ExternalVideoRequestContext(
    val referer: String,
    val userAgent: String,
)

internal data class ExternalResourceMetadata(
    val length: Long,
    val mimeType: String?,
    val finalUrl: String,
)

internal data class ExternalRangeRead(
    val bytes: ByteArray,
    val totalLength: Long?,
    val finalUrl: String,
    val rangeHonored: Boolean,
)

internal enum class ExternalMediaKind(val mimeType: String) {
    HLS("application/vnd.apple.mpegurl"),
    MP4("video/mp4"),
    WEBM("video/webm"),
    MPEG_TS("video/mp2t"),
    VIDEO("video/*"),
    BINARY("application/octet-stream"),
}

internal fun inferExternalMediaKind(url: String, contentType: String? = null): ExternalMediaKind {
    val normalizedType = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (normalizedType in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")) {
        return ExternalMediaKind.HLS
    }
    if (normalizedType == "video/mp4") return ExternalMediaKind.MP4
    if (normalizedType == "video/webm") return ExternalMediaKind.WEBM
    if (normalizedType == "video/mp2t") return ExternalMediaKind.MPEG_TS

    val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
    return when {
        path.endsWith(".m3u8") -> ExternalMediaKind.HLS
        path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".m4s") -> ExternalMediaKind.MP4
        path.endsWith(".webm") -> ExternalMediaKind.WEBM
        path.endsWith(".ts") -> ExternalMediaKind.MPEG_TS
        normalizedType.startsWith("video/") -> ExternalMediaKind.VIDEO
        normalizedType.isNotBlank() -> ExternalMediaKind.BINARY
        else -> ExternalMediaKind.VIDEO
    }
}

private class ExternalProtocolIOException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal class ExternalSequentialHttpStream(
    private val response: Response,
    val totalLength: Long?,
    val finalUrl: String,
) : AutoCloseable {
    private val input = response.body.byteStream()
    var position: Long = 0L
        private set

    fun skipTo(targetOffset: Long) {
        require(targetOffset >= position) { "sequential stream cannot seek backward" }
        val scratch = ByteArray(16 * 1024)
        while (position < targetOffset) {
            val wanted = min(scratch.size.toLong(), targetOffset - position).toInt()
            val read = input.read(scratch, 0, wanted)
            if (read < 0) throw IOException("Protected media full response ended while seeking forward")
            if (read == 0) {
                val single = input.read()
                if (single < 0) throw IOException("Protected media full response ended while seeking forward")
                position += 1L
            } else {
                position += read.toLong()
            }
        }
    }

    fun readSome(maxBytes: Int): ByteArray {
        require(maxBytes > 0)
        val buffer = ByteArray(min(maxBytes, 64 * 1024))
        val read = input.read(buffer)
        if (read < 0) return ByteArray(0)
        if (read == 0) {
            val single = input.read()
            if (single < 0) return ByteArray(0)
            position += 1L
            return byteArrayOf(single.toByte())
        }
        position += read.toLong()
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    override fun close() {
        response.close()
    }
}

internal class ExternalHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val cookieProvider: (String) -> String? = { null },
) {
    private data class Snapshot(
        val code: Int,
        val contentRange: String?,
        val contentLength: Long?,
        val contentType: String?,
        val body: ByteArray,
        val finalUrl: String,
    )

    fun metadata(url: String, context: ExternalVideoRequestContext): ExternalResourceMetadata {
        requireHttpUrl(url)
        val head = request(method = "HEAD", url = url, context = context, range = null, maxBytes = 0)
        if (head.code in 200..299 && head.contentLength != null && head.contentLength >= 0L) {
            return ExternalResourceMetadata(head.contentLength, head.contentType, head.finalUrl)
        }

        val probe = request(method = "GET", url = url, context = context, range = "bytes=0-0", maxBytes = 1)
        if (probe.code == 206) {
            val parsed = parseContentRange(probe.contentRange)
                ?: throw ExternalProtocolIOException("Protected media server returned 206 without a valid Content-Range")
            if (parsed.start != 0L || parsed.end < parsed.start || parsed.total == null) {
                throw ExternalProtocolIOException("Protected media server returned an invalid range probe")
            }
            return ExternalResourceMetadata(parsed.total, probe.contentType, probe.finalUrl)
        }
        if (probe.code in 200..299 && probe.contentLength != null && probe.contentLength >= 0L) {
            return ExternalResourceMetadata(probe.contentLength, probe.contentType, probe.finalUrl)
        }
        throw httpFailure("Unable to determine protected media length", probe.code)
    }

    fun readRange(
        url: String,
        context: ExternalVideoRequestContext,
        offset: Long,
        size: Int,
    ): ExternalRangeRead {
        require(offset >= 0L) { "offset must be non-negative" }
        require(size > 0) { "size must be positive" }
        requireHttpUrl(url)
        val end = offset + size.toLong() - 1L
        if (end < offset) throw ExternalProtocolIOException("Requested media range overflowed")
        val response = request(
            method = "GET",
            url = url,
            context = context,
            range = "bytes=$offset-$end",
            maxBytes = size,
        )
        return when (response.code) {
            206 -> {
                val parsed = parseContentRange(response.contentRange)
                    ?: throw ExternalProtocolIOException("Protected media server returned 206 without Content-Range")
                if (parsed.start != offset || parsed.end < parsed.start) {
                    throw ExternalProtocolIOException("Protected media server returned a mismatched Content-Range")
                }
                val expectedMax = min(size.toLong(), parsed.end - parsed.start + 1L).toInt()
                if (response.body.size > expectedMax) {
                    throw ExternalProtocolIOException("Protected media server exceeded the requested byte range")
                }
                ExternalRangeRead(response.body, parsed.total, response.finalUrl, rangeHonored = true)
            }
            200 -> ExternalRangeRead(ByteArray(0), response.contentLength, response.finalUrl, rangeHonored = false)
            416 -> {
                val parsed = parseUnsatisfiedContentRange(response.contentRange)
                if (parsed != null && offset >= parsed) {
                    ExternalRangeRead(ByteArray(0), parsed, response.finalUrl, rangeHonored = true)
                } else {
                    throw ExternalProtocolIOException("Protected media server rejected a valid-looking range")
                }
            }
            else -> throw httpFailure("Protected media range request failed", response.code)
        }
    }

    fun openSequential(url: String, context: ExternalVideoRequestContext): ExternalSequentialHttpStream {
        var current = url
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            requireHttpUrl(current)
            val request = buildRequest(method = "GET", url = current, context = context, range = null)
            val response = client.newCall(request).execute()
            val location = response.header("Location")
            if (response.code in 300..399 && !location.isNullOrBlank()) {
                response.close()
                if (redirectCount >= MAX_REDIRECTS) throw ExternalProtocolIOException("Protected media redirect limit exceeded")
                val next = runCatching { URI(current).resolve(location).toString() }
                    .getOrElse { throw ExternalProtocolIOException("Protected media returned an invalid redirect", it) }
                requireHttpUrl(next)
                current = next
            } else if (response.code in 200..299) {
                return ExternalSequentialHttpStream(
                    response = response,
                    totalLength = response.header("Content-Length")?.toLongOrNull(),
                    finalUrl = current,
                )
            } else {
                val code = response.code
                response.close()
                throw httpFailure("Protected media sequential request failed", code)
            }
        }
        throw ExternalProtocolIOException("Protected media redirect loop")
    }

    fun fetchSmallText(
        url: String,
        context: ExternalVideoRequestContext,
        maxBytes: Int = 2 * 1024 * 1024,
    ): Pair<String, String> {
        require(maxBytes > 0)
        requireHttpUrl(url)
        val response = request(
            method = "GET",
            url = url,
            context = context,
            range = null,
            maxBytes = maxBytes + 1,
        )
        if (response.code !in 200..299) throw httpFailure("Protected playlist request failed", response.code)
        if (response.body.size > maxBytes) throw ExternalProtocolIOException("Protected playlist exceeds the safe size limit")
        return response.body.toString(Charsets.UTF_8) to response.finalUrl
    }

    private fun request(
        method: String,
        url: String,
        context: ExternalVideoRequestContext,
        range: String?,
        maxBytes: Int,
    ): Snapshot {
        var current = url
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            requireHttpUrl(current)
            val response = client.newCall(buildRequest(method, current, context, range)).execute()
            try {
                val location = response.header("Location")
                if (response.code in 300..399 && !location.isNullOrBlank()) {
                    if (redirectCount >= MAX_REDIRECTS) throw ExternalProtocolIOException("Protected media redirect limit exceeded")
                    val next = runCatching { URI(current).resolve(location).toString() }
                        .getOrElse { throw ExternalProtocolIOException("Protected media returned an invalid redirect", it) }
                    requireHttpUrl(next)
                    current = next
                } else {
                    val body = if (
                        method == "HEAD" ||
                        maxBytes == 0 ||
                        (range != null && response.code == 200)
                    ) {
                        ByteArray(0)
                    } else {
                        readAtMost(response.body.byteStream(), maxBytes)
                    }
                    return Snapshot(
                        code = response.code,
                        contentRange = response.header("Content-Range"),
                        contentLength = response.header("Content-Length")?.toLongOrNull(),
                        contentType = response.header("Content-Type"),
                        body = body,
                        finalUrl = current,
                    )
                }
            } finally {
                response.close()
            }
        }
        throw ExternalProtocolIOException("Protected media redirect loop")
    }

    private fun buildRequest(
        method: String,
        url: String,
        context: ExternalVideoRequestContext,
        range: String?,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Referer", context.referer)
            .header("User-Agent", context.userAgent)
            .header("Accept-Encoding", "identity")
        cookieProvider(url)?.takeIf(String::isNotBlank)?.let { builder.header("Cookie", it) }
        range?.let { builder.header("Range", it) }
        if (method == "HEAD") builder.head() else builder.get()
        return builder.build()
    }

    private fun httpFailure(prefix: String, code: Int): IOException =
        if (code in TRANSIENT_HTTP_CODES) {
            IOException("$prefix: HTTP $code")
        } else {
            ExternalProtocolIOException("$prefix: HTTP $code")
        }

    private fun requireHttpUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrElse { throw ExternalProtocolIOException("Invalid protected media URL", it) }
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw ExternalProtocolIOException("Unsupported protected media URL")
        }
    }

    private fun readAtMost(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(min(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = input.read(buffer, 0, min(buffer.size, remaining))
            if (read < 0) break
            if (read == 0) {
                val single = input.read()
                if (single < 0) break
                output.write(single)
                remaining -= 1
            } else {
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        return output.toByteArray()
    }

    private data class ContentRange(val start: Long, val end: Long, val total: Long?)

    private fun parseContentRange(value: String?): ContentRange? {
        val match = CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
        return ContentRange(
            start = match.groupValues[1].toLongOrNull() ?: return null,
            end = match.groupValues[2].toLongOrNull() ?: return null,
            total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull(),
        )
    }

    private fun parseUnsatisfiedContentRange(value: String?): Long? =
        UNSATISFIED_CONTENT_RANGE.matchEntire(value?.trim().orEmpty())?.groupValues?.get(1)?.toLongOrNull()

    companion object {
        private const val MAX_REDIRECTS = 5
        private val TRANSIENT_HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
        private val UNSATISFIED_CONTENT_RANGE = Regex("bytes \\*/(\\d+)", RegexOption.IGNORE_CASE)
    }
}

internal class SeekableExternalHttpResource(
    private val url: String,
    private val context: ExternalVideoRequestContext,
    private val transport: ExternalHttpTransport,
    private val chunkSize: Int = 512 * 1024,
    private val maxCachedChunks: Int = 4,
) : AutoCloseable {
    init {
        require(chunkSize > 0)
        require(maxCachedChunks > 0)
    }

    private val cache = object : LinkedHashMap<Long, ByteArray>(maxCachedChunks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > maxCachedChunks
    }
    private var metadata: ExternalResourceMetadata? = null
    private var sequentialFallback: ExternalSequentialHttpStream? = null

    @Synchronized
    fun size(): Long {
        val existing = metadata
        if (existing != null) return existing.length
        var lastFailure: IOException? = null
        repeat(MAX_METADATA_ATTEMPTS) {
            try {
                return transport.metadata(url, context).also { metadata = it }.length
            } catch (protocol: ExternalProtocolIOException) {
                throw protocol
            } catch (io: IOException) {
                lastFailure = io
            }
        }
        throw IOException("Protected media metadata failed after bounded retries", lastFailure)
    }

    @Synchronized
    fun read(offset: Long, size: Int): ByteArray {
        require(offset >= 0L)
        require(size > 0)
        val length = size()
        if (offset >= length) return ByteArray(0)
        val wanted = min(size.toLong(), length - offset).toInt()
        val output = ByteArrayOutputStream(wanted)
        var cursor = offset
        while (output.size() < wanted) {
            val chunkStart = (cursor / chunkSize) * chunkSize
            val chunk = cache[chunkStart] ?: loadChunk(chunkStart, length).also { cache[chunkStart] = it }
            val inChunk = (cursor - chunkStart).toInt()
            if (inChunk >= chunk.size) {
                throw IOException("Protected media chunk ended before the known resource length")
            }
            val copy = min(wanted - output.size(), chunk.size - inChunk)
            if (copy <= 0) throw IOException("Protected media read made no progress")
            output.write(chunk, inChunk, copy)
            cursor += copy.toLong()
        }
        return output.toByteArray()
    }

    @Synchronized
    override fun close() {
        resetSequentialFallback()
        cache.clear()
    }

    private fun loadChunk(chunkStart: Long, length: Long): ByteArray {
        val requested = min(chunkSize.toLong(), length - chunkStart).toInt()
        val output = ByteArrayOutputStream(requested)
        var cursor = chunkStart
        var rangeAttempts = 0
        var consecutiveNetworkFailures = 0

        while (output.size() < requested) {
            if (sequentialFallback != null) {
                return fillFromSequentialFallback(output, cursor, requested, length)
            }
            rangeAttempts += 1
            if (rangeAttempts > MAX_RANGE_REQUESTS_PER_CHUNK) {
                throw IOException("Protected media chunk exceeded the bounded range-request budget")
            }
            val remaining = requested - output.size()
            try {
                val range = transport.readRange(url, context, cursor, remaining)
                range.totalLength?.let { total ->
                    if (total != length) throw ExternalProtocolIOException("Protected media length changed during streaming")
                }
                if (!range.rangeHonored) {
                    return fillFromSequentialFallback(output, cursor, requested, length)
                }
                if (range.bytes.isEmpty()) {
                    throw IOException("Protected media range returned no bytes before EOF")
                }
                if (range.bytes.size > remaining) {
                    throw ExternalProtocolIOException("Protected media range exceeded the remaining chunk size")
                }
                output.write(range.bytes)
                cursor += range.bytes.size.toLong()
                consecutiveNetworkFailures = 0
            } catch (protocol: ExternalProtocolIOException) {
                throw protocol
            } catch (io: IOException) {
                consecutiveNetworkFailures += 1
                if (consecutiveNetworkFailures >= MAX_TRANSIENT_FAILURES_PER_POSITION) {
                    throw IOException("Protected media range failed after bounded retries", io)
                }
            }
        }
        return output.toByteArray()
    }

    private fun fillFromSequentialFallback(
        output: ByteArrayOutputStream,
        startCursor: Long,
        requested: Int,
        length: Long,
    ): ByteArray {
        var cursor = startCursor
        var consecutiveFailures = 0
        while (output.size() < requested) {
            try {
                val stream = sequentialStreamAt(cursor, length)
                val piece = stream.readSome(requested - output.size())
                if (piece.isEmpty()) {
                    throw ExternalProtocolIOException("Protected media full response ended before the known resource length")
                }
                output.write(piece)
                cursor += piece.size.toLong()
                consecutiveFailures = 0
            } catch (protocol: ExternalProtocolIOException) {
                throw protocol
            } catch (io: IOException) {
                consecutiveFailures += 1
                resetSequentialFallback()
                if (consecutiveFailures >= MAX_TRANSIENT_FAILURES_PER_POSITION) {
                    throw IOException("Protected media sequential fallback failed after bounded retries", io)
                }
            }
        }
        return output.toByteArray()
    }

    private fun sequentialStreamAt(offset: Long, length: Long): ExternalSequentialHttpStream {
        var stream = sequentialFallback
        if (stream == null || offset < stream.position) {
            resetSequentialFallback()
            stream = transport.openSequential(url, context)
            stream.totalLength?.let { total ->
                if (total != length) {
                    stream.close()
                    throw ExternalProtocolIOException("Protected media length changed during full-response fallback")
                }
            }
            sequentialFallback = stream
        }
        if (offset > stream.position) stream.skipTo(offset)
        if (stream.position != offset) throw ExternalProtocolIOException("Protected media sequential cursor is inconsistent")
        return stream
    }

    private fun resetSequentialFallback() {
        sequentialFallback?.close()
        sequentialFallback = null
    }

    companion object {
        private const val MAX_METADATA_ATTEMPTS = 3
        private const val MAX_TRANSIENT_FAILURES_PER_POSITION = 3
        private const val MAX_RANGE_REQUESTS_PER_CHUNK = 32
    }
}

internal fun rewriteHlsPlaylist(
    playlist: String,
    baseUrl: String,
    rewriteUrl: (String) -> String,
): String = playlist.lineSequence().joinToString("\n") { line ->
    when {
        line.isBlank() -> line
        !line.startsWith("#") -> resolveHlsHttpUrl(baseUrl, line.trim())?.let(rewriteUrl) ?: line
        "URI=" in line -> HLS_URI_ATTRIBUTE.replace(line) { match ->
            val raw = match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()
            val resolved = resolveHlsHttpUrl(baseUrl, raw) ?: return@replace match.value
            "URI=\"${rewriteUrl(resolved)}\""
        }
        else -> line
    }
}

private fun resolveHlsHttpUrl(baseUrl: String, reference: String): String? = runCatching {
    val resolved = URI(baseUrl).resolve(reference)
    val scheme = resolved.scheme?.lowercase()
    if ((scheme == "http" || scheme == "https") && !resolved.host.isNullOrBlank() && resolved.userInfo == null) {
        resolved.toString()
    } else {
        null
    }
}.getOrNull()

private val HLS_URI_ATTRIBUTE = Regex("URI=(?:\\\"([^\\\"]+)\\\"|([^,\\s]+))")
