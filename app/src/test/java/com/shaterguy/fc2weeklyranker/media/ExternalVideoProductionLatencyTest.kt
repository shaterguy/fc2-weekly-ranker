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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class ExternalVideoProductionLatencyTest {
    private val chunkBytes = 512 * 1024
    private val payload = ByteArray(10 * chunkBytes) { index -> ((index * 29) and 0xff).toByte() }
    private val sourceUrl = "https://media.example.test/protected/latency.mp4?sig=fixture"
    private val context = ExternalVideoRequestContext(
        referer = "https://source.example.test/post/latency",
        userAgent = "FC2-Production-Latency-Test/1.0",
    )

    @Test
    fun productionChunkRestoresSingleRoundTripForWideStartupAndSeekReads() {
        val dev24 = measureWideReads(chunkSize = 64 * 1024)
        val candidate = measureWideReads(chunkSize = chunkBytes)

        assertEquals("dev24 policy must reproduce eight startup foreground ranges", 8, dev24.startupRanges)
        assertEquals("dev24 policy must reproduce eight seek foreground ranges", 8, dev24.seekRanges)
        assertEquals("candidate startup must use one foreground range", 1, candidate.startupRanges)
        assertEquals("candidate seek must use one foreground range", 1, candidate.seekRanges)
        assertTrue(
            "candidate startup did not materially remove per-range delay: ${candidate.startupMs} vs ${dev24.startupMs}",
            candidate.startupMs <= dev24.startupMs * 0.50,
        )
        assertTrue(
            "candidate seek did not materially remove per-range delay: ${candidate.seekMs} vs ${dev24.seekMs}",
            candidate.seekMs <= dev24.seekMs * 0.50,
        )

        println(
            "FC2_PRODUCTION_LATENCY_METRIC " +
                "dev24_startup_ms=${formatMetric(dev24.startupMs)} " +
                "candidate_startup_ms=${formatMetric(candidate.startupMs)} " +
                "dev24_seek_ms=${formatMetric(dev24.seekMs)} " +
                "candidate_seek_ms=${formatMetric(candidate.seekMs)} " +
                "dev24_startup_ranges=${dev24.startupRanges} " +
                "candidate_startup_ranges=${candidate.startupRanges} " +
                "dev24_seek_ranges=${dev24.seekRanges} " +
                "candidate_seek_ranges=${candidate.seekRanges}",
        )
    }

    private fun measureWideReads(chunkSize: Int): Sample {
        val upstream = DelayedFullRangeUpstream(payload, rangeDelayMs = 20L)
        val transport = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(upstream).build(),
            cookieProvider = { "session=latency-fixture" },
        )
        val reader = SeekableExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport,
            chunkSize = chunkSize,
            maxCachedChunks = 1,
        )

        return try {
            val startupBefore = upstream.foregroundRangeCount.get()
            val startupStarted = System.nanoTime()
            val startup = reader.read(0L, chunkBytes)
            val startupMs = (System.nanoTime() - startupStarted) / 1_000_000.0
            val startupRanges = upstream.foregroundRangeCount.get() - startupBefore
            assertArrayEquals(payload.copyOfRange(0, chunkBytes), startup)

            val seekOffset = 4L * chunkBytes.toLong()
            val seekBefore = upstream.foregroundRangeCount.get()
            val seekStarted = System.nanoTime()
            val seek = reader.read(seekOffset, chunkBytes)
            val seekMs = (System.nanoTime() - seekStarted) / 1_000_000.0
            val seekRanges = upstream.foregroundRangeCount.get() - seekBefore
            assertArrayEquals(
                payload.copyOfRange(seekOffset.toInt(), seekOffset.toInt() + chunkBytes),
                seek,
            )

            Sample(
                startupMs = startupMs,
                seekMs = seekMs,
                startupRanges = startupRanges,
                seekRanges = seekRanges,
            )
        } finally {
            reader.close()
        }
    }

    private fun formatMetric(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

    private data class Sample(
        val startupMs: Double,
        val seekMs: Double,
        val startupRanges: Int,
        val seekRanges: Int,
    )

    private class DelayedFullRangeUpstream(
        private val payload: ByteArray,
        private val rangeDelayMs: Long,
    ) : Interceptor {
        val foregroundRangeCount = AtomicInteger(0)

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.method == "HEAD") {
                return response(
                    request,
                    code = 200,
                    headers = mapOf(
                        "Content-Length" to payload.size.toString(),
                        "Content-Type" to "video/mp4",
                    ),
                )
            }

            val range = RANGE.matchEntire(request.header("Range").orEmpty())
                ?: return response(request, code = 400)
            if (!Thread.currentThread().name.startsWith("external-media-read-ahead-")) {
                foregroundRangeCount.incrementAndGet()
            }
            if (rangeDelayMs > 0L) Thread.sleep(rangeDelayMs)

            val requestedStart = range.groupValues[1].toLong()
            val requestedEnd = range.groupValues[2].toLong()
            if (requestedStart >= payload.size) {
                return response(
                    request,
                    code = 416,
                    headers = mapOf("Content-Range" to "bytes */${payload.size}"),
                )
            }
            val start = requestedStart.toInt()
            val endInclusive = min(requestedEnd, payload.lastIndex.toLong()).toInt()
            val body = payload.copyOfRange(start, endInclusive + 1)
            return response(
                request,
                code = 206,
                body = body,
                headers = mapOf(
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
