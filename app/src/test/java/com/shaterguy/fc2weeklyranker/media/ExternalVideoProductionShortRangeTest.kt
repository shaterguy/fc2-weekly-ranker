package com.shaterguy.fc2weeklyranker.media

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.min

class ExternalVideoProductionShortRangeTest {
    private val payload = ByteArray(8 * 512 * 1024 + 128 * 1024) { index -> ((index * 41) and 0xff).toByte() }
    private val sourceUrl = "https://media.example.test/protected/production-short-range.mp4?sig=fixture"
    private val context = ExternalVideoRequestContext(
        referer = "https://source.example.test/post/production-short-range",
        userAgent = "FC2-Production-Short-Range-Test/1.0",
    )

    @Test
    fun productionPolicyBoundsDelayedShort206ForegroundDemand() {
        val upstream = DelayedShortRangeUpstream(payload, maxBodyBytes = 8 * 1024, rangeDelayMs = 12L)
        val transport = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(upstream).build(),
            cookieProvider = { "session=production-short-range" },
        )
        val reader = SeekableExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport,
            chunkSize = 512 * 1024,
            maxCachedChunks = 1,
        )

        val latencyMs = mutableListOf<Double>()
        val foregroundRanges = mutableListOf<Int>()
        val offsets = listOf(
            0L,
            1L * 512L * 1024L + 7L * 1024L,
            3L * 512L * 1024L + 11L * 1024L,
            2L * 512L * 1024L + 5L * 1024L,
            6L * 512L * 1024L + 13L * 1024L,
            4L * 512L * 1024L + 3L * 1024L,
            7L * 512L * 1024L + 17L * 1024L,
            5L * 512L * 1024L + 19L * 1024L,
        )

        try {
            repeat(3) { round ->
                offsets.forEachIndexed { index, offset ->
                    val readSize = if ((round + index) % 3 == 0) 16 * 1024 else 64 * 1024
                    val before = upstream.foregroundRangeCount.get()
                    val started = System.nanoTime()
                    val actual = reader.read(offset, readSize)
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                    val after = upstream.foregroundRangeCount.get()

                    assertArrayEquals(
                        payload.copyOfRange(offset.toInt(), offset.toInt() + readSize),
                        actual,
                    )
                    latencyMs += elapsedMs
                    foregroundRanges += after - before
                }
            }

            val p95 = percentile(latencyMs, 0.95)
            val max = latencyMs.maxOrNull() ?: error("missing latency samples")
            val maxForegroundRanges = foregroundRanges.maxOrNull() ?: error("missing foreground range samples")
            val debug = reader.debugSnapshot()

            println(
                "FC2_PRODUCTION_SHORT_RANGE_METRIC " +
                    "samples=${latencyMs.size} " +
                    "p95_ms=${formatMetric(p95)} " +
                    "max_ms=${formatMetric(max)} " +
                    "max_foreground_ranges=$maxForegroundRanges " +
                    "prefetch_hwm=${debug.prefetchInFlightHighWater} " +
                    "resident_bytes=${debug.residentBytes}",
            )

            assertTrue("production short-206 p95 exceeded 180 ms: $p95", p95 <= 180.0)
            assertTrue("production short-206 max exceeded 250 ms: $max", max <= 250.0)
            assertTrue(
                "production short-206 foreground range fan-out exceeded 16: $maxForegroundRanges",
                maxForegroundRanges <= 16,
            )
            assertTrue("production read-ahead exceeded single-flight", debug.prefetchInFlightHighWater <= 1)
            assertTrue("production resident cache/read-ahead exceeded 1 MiB", debug.residentBytes <= 1024 * 1024)
        } finally {
            reader.close()
        }
    }

    @Test
    fun seekDoesNotWaitForStaleShort206Prefetch() {
        val upstream = DelayedShortRangeUpstream(payload, maxBodyBytes = 8 * 1024, rangeDelayMs = 80L)
        val transport = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(upstream).build(),
            cookieProvider = { "session=prefetch-seek" },
        )
        val reader = SeekableExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport,
            chunkSize = 512 * 1024,
            maxCachedChunks = 1,
        )

        try {
            assertArrayEquals(payload.copyOfRange(0, 64 * 1024), reader.read(0L, 64 * 1024))
            assertTrue(
                "bounded short-206 read-ahead did not become active",
                upstream.awaitBackgroundActive(timeoutMs = 2_000L),
            )
            val generationBeforeSeek = reader.debugSnapshot().generation
            val seekOffset = 5L * 512L * 1024L + 23L * 1024L
            val foregroundBefore = upstream.foregroundRangeCount.get()
            val started = System.nanoTime()
            val actual = reader.read(seekOffset, 16 * 1024)
            val seekMs = (System.nanoTime() - started) / 1_000_000.0
            val foregroundDelta = upstream.foregroundRangeCount.get() - foregroundBefore
            val debug = reader.debugSnapshot()

            assertArrayEquals(
                payload.copyOfRange(seekOffset.toInt(), seekOffset.toInt() + 16 * 1024),
                actual,
            )
            assertTrue("seek waited for stale read-ahead: $seekMs ms", seekMs <= 180.0)
            assertTrue("seek issued too many foreground ranges: $foregroundDelta", foregroundDelta <= 3)
            assertTrue("seek did not invalidate the previous read-ahead generation", debug.generation > generationBeforeSeek)
            assertTrue("seek path exceeded single-flight read-ahead", debug.prefetchInFlightHighWater <= 1)
            assertTrue("seek path exceeded 1 MiB resident bound", debug.residentBytes <= 1024 * 1024)
            assertTrue("range fixture did not settle after seek", upstream.awaitIdle(timeoutMs = 2_000L))
        } finally {
            reader.close()
        }
    }

    private fun percentile(values: List<Double>, quantile: Double): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val index = (ceil(sorted.size * quantile).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun formatMetric(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

    private class DelayedShortRangeUpstream(
        private val payload: ByteArray,
        private val maxBodyBytes: Int,
        private val rangeDelayMs: Long,
    ) : Interceptor {
        val foregroundRangeCount = AtomicInteger(0)
        private val backgroundRangeCount = AtomicInteger(0)
        private val activeRangeCount = AtomicInteger(0)
        private val activeBackgroundRangeCount = AtomicInteger(0)

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
            val background = Thread.currentThread().name.startsWith("external-media-read-ahead-")
            if (background) backgroundRangeCount.incrementAndGet() else foregroundRangeCount.incrementAndGet()
            activeRangeCount.incrementAndGet()
            if (background) activeBackgroundRangeCount.incrementAndGet()
            try {
                if (rangeDelayMs > 0L) {
                    try {
                        Thread.sleep(rangeDelayMs)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("fixture range request interrupted", interrupted)
                    }
                }

                val requestedStart = range.groupValues[1].toLong()
                val requestedEnd = range.groupValues[2].toLong()
                if (requestedStart >= payload.size) {
                    return response(
                        request,
                        code = 416,
                        headers = mapOf("Content-Range" to "bytes */${payload.size}"),
                    )
                }
                val requestedLength = (requestedEnd - requestedStart + 1L).coerceAtLeast(1L)
                val bodyLength = min(
                    min(requestedLength, maxBodyBytes.toLong()),
                    payload.size.toLong() - requestedStart,
                ).toInt()
                val start = requestedStart.toInt()
                val endExclusive = start + bodyLength
                val body = payload.copyOfRange(start, endExclusive)
                return response(
                    request,
                    code = 206,
                    body = body,
                    headers = mapOf(
                        "Content-Range" to "bytes $start-${endExclusive - 1}/${payload.size}",
                        "Content-Length" to body.size.toString(),
                        "Content-Type" to "video/mp4",
                    ),
                )
            } finally {
                activeRangeCount.decrementAndGet()
                if (background) activeBackgroundRangeCount.decrementAndGet()
            }
        }

        fun awaitBackgroundActive(timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (activeBackgroundRangeCount.get() > 0) return true
                Thread.sleep(5L)
            }
            return activeBackgroundRangeCount.get() > 0
        }

        fun awaitIdle(timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (activeRangeCount.get() == 0) return true
                Thread.sleep(5L)
            }
            return activeRangeCount.get() == 0
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
