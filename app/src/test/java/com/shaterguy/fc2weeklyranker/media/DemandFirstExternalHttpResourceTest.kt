package com.shaterguy.fc2weeklyranker.media

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class DemandFirstExternalHttpResourceTest {
    private val chunkBytes = 512 * 1024
    private val payload = ByteArray(8 * chunkBytes) { index -> ((index * 31) and 0xff).toByte() }
    private val sourceUrl = "https://media.example.test/protected/demand-first.mp4?sig=fixture"
    private val context = ExternalVideoRequestContext(
        referer = "https://source.example.test/post/demand-first",
        userAgent = "FC2-Demand-First-Test/1.0",
    )

    @Test
    fun smallStartupStreamsOneChunkWindowWithoutReadingWholeBody() {
        val startupBytes = 16 * 1024
        val upstream = FullRangeUpstream(
            payload = payload,
            trackBodyReads = true,
            bodyChunkBytes = startupBytes,
        )
        val reader = reader(upstream)
        try {
            val startup = reader.read(0L, startupBytes)
            assertArrayEquals(payload.copyOfRange(0, startupBytes), startup)
            assertEquals(
                listOf("bytes=0-${chunkBytes - 1}"),
                upstream.foregroundRanges.toList(),
            )
            assertTrue(
                "startup should return after immediate demand instead of draining the 512KiB response",
                upstream.trackedBodyBytes() >= startupBytes &&
                    upstream.trackedBodyBytes() < chunkBytes,
            )
        } finally {
            reader.close()
        }
    }

    @Test
    fun contiguousSmallReadsReuseOneStreamingWindow() {
        val upstream = FullRangeUpstream(payload)
        val reader = reader(upstream)
        try {
            val smallReadBytes = 16 * 1024
            repeat(8) { index ->
                val offset = index.toLong() * smallReadBytes.toLong()
                val bytes = reader.read(offset, smallReadBytes)
                assertArrayEquals(
                    payload.copyOfRange(offset.toInt(), offset.toInt() + smallReadBytes),
                    bytes,
                )
            }
            assertEquals(
                listOf("bytes=0-${chunkBytes - 1}"),
                upstream.foregroundRanges.toList(),
            )
            assertEquals(1, upstream.totalRangeRequests)
        } finally {
            reader.close()
        }
    }

    @Test
    fun nonAlignedSeekCancelsOldWindowBeforeOpeningNewRange() {
        val upstream = FullRangeUpstream(payload)
        val reader = reader(upstream)
        try {
            val startupBytes = 16 * 1024
            assertArrayEquals(
                payload.copyOfRange(0, startupBytes),
                reader.read(0L, startupBytes),
            )
            val firstRangeCall = upstream.rangeCalls.first()

            val seekOffset = chunkBytes.toLong() + 12_345L
            val seekBytes = 64 * 1024
            assertArrayEquals(
                payload.copyOfRange(seekOffset.toInt(), seekOffset.toInt() + seekBytes),
                reader.read(seekOffset, seekBytes),
            )

            assertTrue("previous streaming range Call must be cancelled on seek", firstRangeCall.isCanceled())
            assertEquals(
                listOf(
                    "bytes=0-${chunkBytes - 1}",
                    "bytes=$seekOffset-${seekOffset + chunkBytes - 1L}",
                ),
                upstream.foregroundRanges.toList(),
            )
        } finally {
            reader.close()
        }
    }

    @Test
    fun productionWideReadKeepsOneForegroundFullChunkRange() {
        val upstream = FullRangeUpstream(payload)
        val reader = reader(upstream)
        try {
            val bytes = reader.read(0L, chunkBytes)
            assertArrayEquals(payload.copyOfRange(0, chunkBytes), bytes)
            assertEquals(listOf("bytes=0-${chunkBytes - 1}"), upstream.foregroundRanges.toList())
        } finally {
            reader.close()
        }
    }

    @Test
    fun short206ResponseFallsBackToProvenReaderPath() {
        val upstream = FullRangeUpstream(payload, maxResponseBytes = 8 * 1024)
        val reader = reader(upstream)
        try {
            val first = reader.read(0L, 64 * 1024)
            assertArrayEquals(payload.copyOfRange(0, 64 * 1024), first)
            val secondOffset = 64L * 1024L
            val second = reader.read(secondOffset, 64 * 1024)
            assertArrayEquals(
                payload.copyOfRange(secondOffset.toInt(), secondOffset.toInt() + 64 * 1024),
                second,
            )
            assertTrue(upstream.totalRangeRequests > 2)
        } finally {
            reader.close()
        }
    }

    @Test
    fun rangeIgnoringServerFallsBackToSequentialReader() {
        val upstream = FullRangeUpstream(payload, ignoreRanges = true)
        val reader = reader(upstream)
        try {
            val offset = 96L * 1024L
            val wanted = 64 * 1024
            val bytes = reader.read(offset, wanted)
            assertArrayEquals(
                payload.copyOfRange(offset.toInt(), offset.toInt() + wanted),
                bytes,
            )
            assertTrue(upstream.foregroundRanges.isNotEmpty())
        } finally {
            reader.close()
        }
    }

    private fun reader(upstream: FullRangeUpstream): DemandFirstExternalHttpResource {
        val transport = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(upstream).build(),
            cookieProvider = { "session=demand-first-fixture" },
        )
        return DemandFirstExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport,
            chunkSize = chunkBytes,
            maxCachedChunks = 1,
        )
    }

    private class FullRangeUpstream(
        private val payload: ByteArray,
        private val maxResponseBytes: Int? = null,
        private val ignoreRanges: Boolean = false,
        private val trackBodyReads: Boolean = false,
        private val bodyChunkBytes: Int = Int.MAX_VALUE,
    ) : Interceptor {
        val foregroundRanges = Collections.synchronizedList(mutableListOf<String>())
        val rangeCalls = Collections.synchronizedList(mutableListOf<Call>())
        private val trackedBytes = AtomicInteger(0)

        @Volatile
        var totalRangeRequests: Int = 0
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.method == "HEAD") {
                return response(
                    request,
                    200,
                    headers = mapOf(
                        "Content-Length" to payload.size.toString(),
                        "Content-Type" to "video/mp4",
                    ),
                )
            }

            val rawRange = request.header("Range").orEmpty()
            if (ignoreRanges) {
                if (rawRange.isNotBlank()) {
                    recordRange(chain, rawRange)
                }
                return response(
                    request = request,
                    code = 200,
                    body = payload.toResponseBody("video/mp4".toMediaType()),
                    headers = mapOf(
                        "Content-Length" to payload.size.toString(),
                        "Content-Type" to "video/mp4",
                    ),
                )
            }

            val range = RANGE.matchEntire(rawRange) ?: return response(request, 400)
            recordRange(chain, rawRange)

            val requestedStart = range.groupValues[1].toLong()
            val requestedEnd = range.groupValues[2].toLong()
            if (requestedStart >= payload.size) {
                return response(
                    request,
                    416,
                    headers = mapOf("Content-Range" to "bytes */${payload.size}"),
                )
            }
            val start = requestedStart.toInt()
            var endInclusive = min(requestedEnd, payload.lastIndex.toLong()).toInt()
            maxResponseBytes?.let { cap ->
                endInclusive = min(endInclusive, start + cap - 1)
            }
            val bodyBytes = payload.copyOfRange(start, endInclusive + 1)
            val body = if (trackBodyReads) {
                TrackingResponseBody(
                    bytes = bodyBytes,
                    mediaType = "video/mp4".toMediaType(),
                    maxChunkBytes = bodyChunkBytes,
                    onRead = { trackedBytes.addAndGet(it) },
                )
            } else {
                bodyBytes.toResponseBody("video/mp4".toMediaType())
            }
            return response(
                request,
                206,
                body,
                mapOf(
                    "Content-Range" to "bytes $start-$endInclusive/${payload.size}",
                    "Content-Length" to bodyBytes.size.toString(),
                    "Content-Type" to "video/mp4",
                ),
            )
        }

        fun trackedBodyBytes(): Int = trackedBytes.get()

        private fun recordRange(chain: Interceptor.Chain, rawRange: String) {
            synchronized(this) { totalRangeRequests += 1 }
            rangeCalls += chain.call()
            val threadName = Thread.currentThread().name
            if (
                !threadName.startsWith("external-media-read-ahead-") &&
                !threadName.startsWith("external-media-demand-read-ahead-") &&
                !threadName.startsWith("external-media-range-")
            ) {
                foregroundRanges += rawRange
            }
        }

        private fun response(
            request: Request,
            code: Int,
            body: ResponseBody = ByteArray(0).toResponseBody("video/mp4".toMediaType()),
            headers: Map<String, String> = emptyMap(),
        ): Response {
            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fixture")
                .body(body)
            headers.forEach { (name, value) -> builder.header(name, value) }
            return builder.build()
        }

        companion object {
            private val RANGE = Regex("bytes=(\\d+)-(\\d+)")
        }
    }

    private class TrackingResponseBody(
        private val bytes: ByteArray,
        private val mediaType: MediaType,
        private val maxChunkBytes: Int,
        private val onRead: (Int) -> Unit,
    ) : ResponseBody() {
        private var offset = 0
        private var closed = false
        private val bufferedSource: BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (closed || offset >= bytes.size) return -1L
                val count = minOf(
                    byteCount,
                    maxChunkBytes.toLong(),
                    (bytes.size - offset).toLong(),
                ).toInt()
                sink.write(bytes, offset, count)
                offset += count
                onRead(count)
                return count.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                closed = true
            }
        }.buffer()

        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = bytes.size.toLong()

        override fun source(): BufferedSource = bufferedSource
    }
}
