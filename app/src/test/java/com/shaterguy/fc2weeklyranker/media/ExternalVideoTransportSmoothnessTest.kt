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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.min

class ExternalVideoTransportSmoothnessTest {
    private val payload = ByteArray(12 * 512 * 1024 + 64 * 1024) { index -> ((index * 37) and 0xff).toByte() }
    private val sourceUrl = "https://media.example.test/protected/smoothness.mp4?sig=fixture"
    private val context = ExternalVideoRequestContext(
        referer = "https://source.example.test/post/smoothness",
        userAgent = "FC2-Smoothness-Test/1.0",
    )

    @Test
    fun delayedShort206PlaybackReadsMeetLockedSmoothnessContract() {
        val upstream = DelayedShortRangeUpstream(payload, maxBodyBytes = 8 * 1024, rangeDelayMs = 12L)
        val transport = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(upstream).build(),
            cookieProvider = { "session=smoothness" },
        )
        val reader = SeekableExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport,
            chunkSize = 64 * 1024,
            maxCachedChunks = 8,
        )

        val latencyMs = mutableListOf<Double>()
        val foregroundRangeCounts = mutableListOf<Int>()
        try {
            repeat(3) {
                repeat(12) { boundary ->
                    val offset = boundary * 512L * 1024L
                    val before = upstream.foregroundRangeCount.get()
                    val started = System.nanoTime()
                    val actual = reader.read(offset, 64 * 1024)
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                    val after = upstream.foregroundRangeCount.get()

                    assertArrayEquals(payload.copyOfRange(offset.toInt(), offset.toInt() + 64 * 1024), actual)
                    latencyMs += elapsedMs
                    foregroundRangeCounts += after - before
                }
            }

            val seekOffsets = listOf(
                5L * 512L * 1024L + 7L * 1024L,
                2L * 512L * 1024L + 3L * 1024L,
                9L * 512L * 1024L + 11L * 1024L,
                1L * 512L * 1024L + 17L * 1024L,
                10L * 512L * 1024L + 5L * 1024L,
                4L * 512L * 1024L + 19L * 1024L,
                8L * 512L * 1024L + 13L * 1024L,
                3L * 512L * 1024L + 23L * 1024L,
            )
            seekOffsets.forEach { offset ->
                val actual = reader.read(offset, 16 * 1024)
                assertArrayEquals(payload.copyOfRange(offset.toInt(), offset.toInt() + 16 * 1024), actual)
            }

            val p50 = percentile(latencyMs, 0.50)
            val p95 = percentile(latencyMs, 0.95)
            val max = latencyMs.maxOrNull() ?: error("missing latency samples")
            val maxForegroundRanges = foregroundRangeCounts.maxOrNull() ?: error("missing range-count samples")
            val debug = readDebugSnapshot(reader)

            println(
                "FC2_SMOOTHNESS_METRIC " +
                    "samples=${latencyMs.size} " +
                    "p50_ms=${formatMetric(p50)} " +
                    "p95_ms=${formatMetric(p95)} " +
                    "max_ms=${formatMetric(max)} " +
                    "max_foreground_ranges=$maxForegroundRanges " +
                    "prefetch_hwm=${debug?.prefetchHighWater ?: -1} " +
                    "resident_bytes=${debug?.residentBytes ?: -1}",
            )

            val mode = System.getenv("FC2_SMOOTHNESS_MODE")
                ?.trim()
                ?.lowercase()
                ?.takeIf(String::isNotEmpty)
                ?: if (debug == null) "baseline" else "candidate"
            when (mode) {
                "baseline" -> {
                    assertTrue("baseline p95 must reproduce the short-range stall", p95 >= 250.0)
                    assertTrue("baseline max must reproduce the short-range stall", max >= 300.0)
                }
                else -> {
                    assertTrue("candidate p95 exceeded 180 ms: $p95", p95 <= 180.0)
                    assertTrue("candidate max exceeded 250 ms: $max", max <= 250.0)
                    assertTrue("candidate foreground short-206 count exceeded 16: $maxForegroundRanges", maxForegroundRanges <= 16)

                    optionalBaselineMetric("FC2_BASELINE_P50_MS")?.let { baseline ->
                        assertTrue("candidate p50 did not improve by at least 55%", p50 <= baseline * 0.45)
                    }
                    optionalBaselineMetric("FC2_BASELINE_P95_MS")?.let { baseline ->
                        assertTrue("candidate p95 did not improve by at least 55%", p95 <= baseline * 0.45)
                    }
                    optionalBaselineMetric("FC2_BASELINE_MAX_MS")?.let { baseline ->
                        assertTrue("candidate max did not improve by at least 40%", max <= baseline * 0.60)
                    }

                    if (debug != null) {
                        assertTrue("per-resource read-ahead exceeded single-flight", debug.prefetchHighWater <= 1)
                        assertTrue("resident cache/read-ahead exceeded 1 MiB", debug.residentBytes <= 1024 * 1024)
                    }
                }
            }
        } finally {
            reader.close()
        }
    }

    @Test
    fun sequentialBoundaryReadAheadMovesShortRangeDelayOffForeground() {
        val chunkSize = 64 * 1024
        val upstream = DelayedShortRangeUpstream(payload, maxBodyBytes = 8 * 1024, rangeDelayMs = 12L)
        val transport = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(upstream).build(),
            cookieProvider = { "session=read-ahead" },
        )
        val reader = SeekableExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport,
            chunkSize = chunkSize,
            maxCachedChunks = 8,
        )

        try {
            assertArrayEquals(payload.copyOfRange(0, 64 * 1024), reader.read(0L, 64 * 1024))
            val firstDebug = readDebugSnapshot(reader)
            val candidate = firstDebug != null
            if (candidate) {
                assertTrue(
                    "candidate read-ahead did not fetch the next 64 KiB chunk in the background",
                    upstream.awaitBackgroundRangeCount(expected = 8, timeoutMs = 2_000L),
                )
            }

            assertArrayEquals(
                payload.copyOfRange(64 * 1024, 128 * 1024),
                reader.read(64L * 1024L, 64 * 1024),
            )

            val foregroundBefore = upstream.foregroundRangeCount.get()
            val started = System.nanoTime()
            val boundary = reader.read(128L * 1024L, 64 * 1024)
            val boundaryMs = (System.nanoTime() - started) / 1_000_000.0
            val foregroundDelta = upstream.foregroundRangeCount.get() - foregroundBefore
            val finalDebug = readDebugSnapshot(reader)

            assertArrayEquals(payload.copyOfRange(128 * 1024, 192 * 1024), boundary)
            println(
                "FC2_READ_AHEAD_METRIC " +
                    "mode=${if (candidate) "candidate" else "baseline"} " +
                    "boundary_ms=${formatMetric(boundaryMs)} " +
                    "foreground_ranges=$foregroundDelta " +
                    "background_ranges=${upstream.backgroundRangeCount.get()} " +
                    "prefetch_hwm=${finalDebug?.prefetchHighWater ?: -1} " +
                    "resident_bytes=${finalDebug?.residentBytes ?: -1} " +
                    "range_read_ahead=${finalDebug?.rangeReadAheadEnabled ?: false}",
            )

            if (candidate) {
                assertEquals("candidate boundary read unexpectedly issued foreground range requests", 0, foregroundDelta)
                assertTrue("candidate did not keep range read-ahead enabled", finalDebug?.rangeReadAheadEnabled == true)
                assertTrue("candidate read-ahead exceeded single-flight", (finalDebug?.prefetchHighWater ?: Int.MAX_VALUE) <= 1)
                assertTrue("candidate read-ahead did not issue background short-range requests", upstream.backgroundRangeCount.get() >= 8)
            } else {
                assertTrue("baseline boundary read must issue short-range requests on the foreground path", foregroundDelta >= 8)
                assertEquals("baseline unexpectedly issued background range requests", 0, upstream.backgroundRangeCount.get())
            }
        } finally {
            reader.close()
        }
    }

    @Test
    fun delayedShort206ChunkStitchingUsesBoundedParallelRanges() {
        val upstream = DelayedShortRangeUpstream(payload, maxBodyBytes = 8 * 1024, rangeDelayMs = 12L)
        val transport = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(upstream).build(),
            cookieProvider = { "session=parallel-short-range" },
        )
        val reader = SeekableExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport,
            chunkSize = 64 * 1024,
            maxCachedChunks = 8,
        )

        try {
            val actual = reader.read(0L, 64 * 1024)
            assertArrayEquals(payload.copyOfRange(0, 64 * 1024), actual)
            val highWater = upstream.rangeConcurrencyHighWater.get()
            assertTrue("short-206 stitching did not use parallel ranges: $highWater", highWater >= 2)
            assertTrue("short-206 stitching exceeded bounded parallelism: $highWater", highWater <= 4)
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

    private fun optionalBaselineMetric(name: String): Double? =
        System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)?.toDoubleOrNull()

    private fun formatMetric(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

    private fun readDebugSnapshot(reader: SeekableExternalHttpResource): DebugSnapshot? {
        val method = reader.javaClass.declaredMethods.firstOrNull { it.name.contains("debugSnapshot") } ?: return null
        method.isAccessible = true
        val snapshot = method.invoke(reader) ?: return null
        val getters = snapshot.javaClass.methods.associateBy { it.name }
        val prefetch = getters.entries.firstOrNull { it.key.startsWith("getPrefetchInFlightHighWater") }
            ?.value?.invoke(snapshot) as? Number ?: return null
        val resident = getters.entries.firstOrNull { it.key.startsWith("getResidentBytes") }
            ?.value?.invoke(snapshot) as? Number ?: return null
        val enabled = getters.entries.firstOrNull { it.key.startsWith("getRangeReadAheadEnabled") }
            ?.value?.invoke(snapshot) as? Boolean ?: return null
        return DebugSnapshot(prefetch.toInt(), resident.toInt(), enabled)
    }

    private data class DebugSnapshot(
        val prefetchHighWater: Int,
        val residentBytes: Int,
        val rangeReadAheadEnabled: Boolean,
    )

    private class DelayedShortRangeUpstream(
        private val payload: ByteArray,
        private val maxBodyBytes: Int,
        private val rangeDelayMs: Long,
    ) : Interceptor {
        val foregroundRangeCount = AtomicInteger(0)
        val backgroundRangeCount = AtomicInteger(0)
        val activeRangeRequests = AtomicInteger(0)
        val rangeConcurrencyHighWater = AtomicInteger(0)
        private val seen = CopyOnWriteArrayList<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            seen += request
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
            if (Thread.currentThread().name.startsWith("external-media-read-ahead-")) {
                backgroundRangeCount.incrementAndGet()
            } else {
                foregroundRangeCount.incrementAndGet()
            }
            val active = activeRangeRequests.incrementAndGet()
            rangeConcurrencyHighWater.updateAndGet { maxOf(it, active) }
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
                val start = requestedStart.toInt()
                val requestedLength = (requestedEnd - requestedStart + 1L).coerceAtLeast(1L)
                val bodyLength = min(min(requestedLength, maxBodyBytes.toLong()), payload.size.toLong() - requestedStart).toInt()
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
                activeRangeRequests.decrementAndGet()
            }
        }

        fun awaitBackgroundRangeCount(expected: Int, timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (backgroundRangeCount.get() >= expected) return true
                Thread.sleep(5L)
            }
            return backgroundRangeCount.get() >= expected
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
