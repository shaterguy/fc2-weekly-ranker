package com.shaterguy.fc2weeklyranker.media

import okhttp3.OkHttpClient
import okhttp3.Request
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
        throw ExternalProtocolIOException("Unable to determine protected media length: HTTP ${probe.code}")
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
            fullResponseSkipBytes = offset,
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
                ExternalRangeRead(response.body, parsed.total, response.finalUrl)
            }
            200 -> ExternalRangeRead(response.body, response.contentLength, response.finalUrl)
            416 -> {
                val parsed = parseUnsatisfiedContentRange(response.contentRange)
                if (parsed != null && offset >= parsed) {
                    ExternalRangeRead(ByteArray(0), parsed, response.finalUrl)
                } else {
                    throw ExternalProtocolIOException("Protected media server rejected a valid-looking range")
                }
            }
            else -> throw ExternalProtocolIOException("Protected media range request failed: HTTP ${response.code}")
        }
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
        if (response.code !in 200..299) throw ExternalProtocolIOException("Protected playlist request failed: HTTP ${response.code}")
        if (response.body.size > maxBytes) throw ExternalProtocolIOException("Protected playlist exceeds the safe size limit")
        return response.body.toString(Charsets.UTF_8) to response.finalUrl
    }

    private fun request(
        method: String,
        url: String,
        context: ExternalVideoRequestContext,
        range: String?,
        maxBytes: Int,
        fullResponseSkipBytes: Long = 0L,
    ): Snapshot {
        var current = url
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            requireHttpUrl(current)
            val builder = Request.Builder()
                .url(current)
                .header("Referer", context.referer)
                .header("User-Agent", context.userAgent)
                .header("Accept-Encoding", "identity")
            cookieProvider(current)?.takeIf(String::isNotBlank)?.let { builder.header("Cookie", it) }
            range?.let { builder.header("Range", it) }
            if (method == "HEAD") builder.head() else builder.get()

            val response = client.newCall(builder.build()).execute()
            try {
                val location = response.header("Location")
                if (response.code in 300..399 && !location.isNullOrBlank()) {
                    if (redirectCount >= MAX_REDIRECTS) throw ExternalProtocolIOException("Protected media redirect limit exceeded")
                    val next = runCatching { URI(current).resolve(location).toString() }
                        .getOrElse { throw ExternalProtocolIOException("Protected media returned an invalid redirect", it) }
                    requireHttpUrl(next)
                    current = next
                } else {
                    val body = if (method == "HEAD" || maxBytes == 0) {
                        ByteArray(0)
                    } else {
                        val skipBytes = if (range != null && response.code == 200) fullResponseSkipBytes else 0L
                        readWindow(response.body.byteStream(), skipBytes, maxBytes)
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

    private fun requireHttpUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrElse { throw ExternalProtocolIOException("Invalid protected media URL", it) }
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw ExternalProtocolIOException("Unsupported protected media URL")
        }
    }

    private fun readWindow(input: java.io.InputStream, skipBytes: Long, maxBytes: Int): ByteArray {
        var remainingSkip = skipBytes
        val scratch = ByteArray(16 * 1024)
        while (remainingSkip > 0L) {
            val requested = min(scratch.size.toLong(), remainingSkip).toInt()
            val read = input.read(scratch, 0, requested)
            if (read < 0) return ByteArray(0)
            if (read == 0) {
                val single = input.read()
                if (single < 0) return ByteArray(0)
                remainingSkip -= 1L
            } else {
                remainingSkip -= read.toLong()
            }
        }
        return readAtMost(input, maxBytes)
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
) {
    init {
        require(chunkSize > 0)
        require(maxCachedChunks > 0)
    }

    private val cache = object : LinkedHashMap<Long, ByteArray>(maxCachedChunks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > maxCachedChunks
    }
    private var metadata: ExternalResourceMetadata? = null

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

    private fun loadChunk(chunkStart: Long, length: Long): ByteArray {
        val requested = min(chunkSize.toLong(), length - chunkStart).toInt()
        val output = ByteArrayOutputStream(requested)
        var cursor = chunkStart
        var attempts = 0
        var consecutiveNetworkFailures = 0

        while (output.size() < requested) {
            attempts += 1
            if (attempts > MAX_RANGE_REQUESTS_PER_CHUNK) {
                throw IOException("Protected media chunk exceeded the bounded range-request budget")
            }
            val remaining = requested - output.size()
            try {
                val range = transport.readRange(url, context, cursor, remaining)
                range.totalLength?.let { total ->
                    if (total != length) throw ExternalProtocolIOException("Protected media length changed during streaming")
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
