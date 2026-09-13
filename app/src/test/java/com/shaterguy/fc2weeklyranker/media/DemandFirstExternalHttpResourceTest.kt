package com.shaterguy.fc2weeklyranker.media

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
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
    fun smallStartupAndNonAlignedSeekUseExactForegroundDemandRanges() {
        val upstream = FullRangeUpstream(payload)
        val reader = reader(upstream)
        try {
            val startupBytes = 16 * 1024
            val startup = reader.read(0L, startupBytes)
            assertArrayEquals(payload.copyOfRange(0, startupBytes), startup)
            assertEquals("bytes=0-${startupBytes - 1}", upstream.foregroundRanges[0])

            val seekOffset = 3L * chunkBytes + 12_345L
            val seekBytes = 64 * 1024
            val seek = reader.read(seekOffset, seekBytes)
            assertArrayEquals(
                payload.copyOfRange(seekOffset.toInt(), seekOffset.toInt() + seekBytes),
                seek,
            )
            assertEquals(
                "bytes=$seekOffset-${seekOffset + seekBytes - 1L}",
                upstream.foregroundRanges[1],
            )
            assertEquals(2, upstream.foregroundRanges.size)
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
    ) : Interceptor {
        val foregroundRanges = Collections.synchronizedList(mutableListOf<String>())
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
            val range = RANGE.matchEntire(rawRange) ?: return response(request, 400)
            synchronized(this) { totalRangeRequests += 1 }
            val threadName = Thread.currentThread().name
            if (
                !threadName.startsWith("external-media-read-ahead-") &&
                !threadName.startsWith("external-media-demand-read-ahead-") &&
                !threadName.startsWith("external-media-range-")
            ) {
                foregroundRanges += rawRange
            }

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
            val body = payload.copyOfRange(start, endInclusive + 1)
            return response(
                request,
                206,
                body,
                mapOf(
                    "Content-Range" to "bytes $start-$endInclusive/${payload.size}",
                    "Content-Length" to body.size.toString(),
                    "Content-Type" to "video/mp4",
                ),
            )
        }

        private fun response(
            request: Request,
            code: Int,
            body: ByteArray = ByteArray(0),
            headers: Map<String, String> = emptyMap(),
        ): Response {
            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fixture")
                .body(body.toResponseBody("video/mp4".toMediaType()))
            headers.forEach { (name, value) -> builder.header(name, value) }
            return builder.build()
        }

        companion object {
            private val RANGE = Regex("bytes=(\\d+)-(\\d+)")
        }
    }
}
