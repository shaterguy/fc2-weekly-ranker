package com.shaterguy.fc2weeklyranker.media

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.min

/**
 * Test-only controlled diagnostics for the user-observed external-player latency gap.
 *
 * The fixture deliberately separates request/header RTT from body delivery time. It is
 * not a product threshold: it demonstrates whether the current transport/resource
 * policy amplifies RTT for decoder-sized reads and whether cancelled read-ahead work
 * can continue occupying the shared demand-first executor after a seek.
 */
class ExternalVideoControlledNetworkDiagnosticTest {
    private val chunkBytes = 512 * 1024
    private val smallReadBytes = 16 * 1024
    private val sequentialReads = 8
    private val payload = ByteArray(8 * chunkBytes) { index -> ((index * 31 + 7) and 0xff).toByte() }
    private val sourceUrl = "https://media.example.test/protected/controlled-rtt.mp4?sig=fixture"
    private val context = ExternalVideoRequestContext(
        referer = "https://source.example.test/post/controlled-rtt",
        userAgent = "FC2-Controlled-Network-Diagnostic/1.0",
    )

    @Test
    fun demandFirstReproducesRepeatedRttAcrossSequentialDecoderSizedReads() {
        val seekableUpstream = ControlledRangeUpstream(
            payload = payload,
            headerDelayMs = 60L,
            bodyChunkBytes = smallReadBytes,
            bodyChunkDelayMs = 4L,
        )
        val demandUpstream = ControlledRangeUpstream(
            payload = payload,
            headerDelayMs = 60L,
            bodyChunkBytes = smallReadBytes,
            bodyChunkDelayMs = 4L,
        )

        val seekable = SeekableExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport(seekableUpstream),
            chunkSize = chunkBytes,
            maxCachedChunks = 1,
        )
        val demandFirst = DemandFirstExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport(demandUpstream),
            chunkSize = chunkBytes,
            maxCachedChunks = 1,
        )

        val seekableSample = try {
            measureSequentialReads(seekableUpstream) { offset, size -> seekable.read(offset, size) }
        } finally {
            seekable.close()
        }
        val demandSample = try {
            measureSequentialReads(demandUpstream) { offset, size -> demandFirst.read(offset, size) }
        } finally {
            demandFirst.close()
        }

        assertTrue(
            "controlled fixture must preserve the intended faster first small demand: " +
                "demand=${demandSample.firstReadMs}ms seekable=${seekableSample.firstReadMs}ms",
            demandSample.firstReadMs < seekableSample.firstReadMs * 0.70,
        )
        assertTrue(
            "current demand-first policy should reproduce repeated RTT amplification across contiguous small reads: " +
                "demand=${demandSample.totalMs}ms seekable=${seekableSample.totalMs}ms",
            demandSample.totalMs > seekableSample.totalMs * 1.50,
        )
        assertTrue(
            "seekable baseline should satisfy the decoder-sized demand window from one foreground range, got " +
                seekableSample.demandWindowRangeStarts,
            seekableSample.demandWindowRangeStarts.size == 1,
        )
        assertTrue(
            "demand-first should issue a separate exact range for each decoder-sized offset in the controlled window, got " +
                demandSample.demandWindowRangeStarts,
            demandSample.demandWindowRangeStarts.containsAll(
                (0 until sequentialReads).map { it.toLong() * smallReadBytes.toLong() },
            ),
        )

        println(
            "FC2_CONTROLLED_RTT_METRIC " +
                "seekable_first_ms=${formatMetric(seekableSample.firstReadMs)} " +
                "demand_first_ms=${formatMetric(demandSample.firstReadMs)} " +
                "seekable_total_ms=${formatMetric(seekableSample.totalMs)} " +
                "demand_total_ms=${formatMetric(demandSample.totalMs)} " +
                "seekable_demand_window_ranges=${seekableSample.demandWindowRangeStarts.size} " +
                "demand_demand_window_ranges=${demandSample.demandWindowRangeStarts.size}",
        )
    }

    @Test
    fun cancelledPrefetchWorkersCanStarveNewestContiguousDemandAfterRapidSeeks() {
        val upstream = BlockingPrefetchRangeUpstream(payload)
        val reader = DemandFirstExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport(upstream),
            chunkSize = chunkBytes,
            maxCachedChunks = 1,
        )
        val readExecutor = Executors.newSingleThreadExecutor()

        try {
            assertArrayEquals(payload.copyOfRange(0, smallReadBytes), reader.read(0L, smallReadBytes))
            assertTrue("first read-ahead worker did not enter the controlled block", upstream.awaitBlockedPrefetch())

            val secondBase = chunkBytes.toLong()
            assertArrayEquals(
                payload.copyOfRange(secondBase.toInt(), secondBase.toInt() + smallReadBytes),
                reader.read(secondBase, smallReadBytes),
            )
            assertTrue("second read-ahead worker did not enter the controlled block", upstream.awaitBlockedPrefetch())

            val newestBase = 2L * chunkBytes.toLong()
            assertArrayEquals(
                payload.copyOfRange(newestBase.toInt(), newestBase.toInt() + smallReadBytes),
                reader.read(newestBase, smallReadBytes),
            )

            val newestContiguousOffset = newestBase + smallReadBytes.toLong()
            val newestRead = readExecutor.submit<ByteArray> {
                reader.read(newestContiguousOffset, smallReadBytes)
            }

            var completedWhileStaleWorkersBlocked = false
            try {
                newestRead.get(200L, TimeUnit.MILLISECONDS)
                completedWhileStaleWorkersBlocked = true
            } catch (_: TimeoutException) {
                // Expected reproduction: both shared workers are still occupied by cancelled stale requests.
            }
            assertFalse(
                "newest contiguous demand unexpectedly completed while two cancelled stale prefetch calls still occupied the executor",
                completedWhileStaleWorkersBlocked,
            )

            upstream.releaseBlockedPrefetch()
            val bytes = newestRead.get(2L, TimeUnit.SECONDS)
            assertArrayEquals(
                payload.copyOfRange(
                    newestContiguousOffset.toInt(),
                    newestContiguousOffset.toInt() + smallReadBytes,
                ),
                bytes,
            )
            assertTrue(
                "expected at least two cancelled-but-running prefetch requests, got ${upstream.blockedPrefetchStarts}",
                upstream.blockedPrefetchStarts >= 2,
            )

            println(
                "FC2_RAPID_SEEK_STALE_WORKER_METRIC reproduced=true " +
                    "blocked_prefetch_starts=${upstream.blockedPrefetchStarts} " +
                    "blocked_observation_ms=200",
            )
        } finally {
            upstream.releaseBlockedPrefetch()
            reader.close()
            readExecutor.shutdownNow()
        }
    }

    private fun measureSequentialReads(
        upstream: ControlledRangeUpstream,
        read: (Long, Int) -> ByteArray,
    ): SequentialSample {
        val startedAt = System.nanoTime()
        var firstReadMs = 0.0
        repeat(sequentialReads) { index ->
            val offset = index.toLong() * smallReadBytes.toLong()
            val readStartedAt = System.nanoTime()
            val actual = read(offset, smallReadBytes)
            if (index == 0) {
                firstReadMs = (System.nanoTime() - readStartedAt) / 1_000_000.0
            }
            assertArrayEquals(
                payload.copyOfRange(offset.toInt(), offset.toInt() + smallReadBytes),
                actual,
            )
        }
        val totalMs = (System.nanoTime() - startedAt) / 1_000_000.0
        val demandEnd = sequentialReads.toLong() * smallReadBytes.toLong()
        val demandWindowRangeStarts = upstream.snapshotRequests()
            .filter { it.start < demandEnd }
            .map { it.start }
            .distinct()
            .sorted()

        return SequentialSample(
            firstReadMs = firstReadMs,
            totalMs = totalMs,
            demandWindowRangeStarts = demandWindowRangeStarts,
        )
    }

    private fun transport(upstream: Interceptor): ExternalHttpTransport = ExternalHttpTransport(
        client = OkHttpClient.Builder().addInterceptor(upstream).build(),
        cookieProvider = { "session=controlled-network-fixture" },
    )

    private fun formatMetric(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

    private data class SequentialSample(
        val firstReadMs: Double,
        val totalMs: Double,
        val demandWindowRangeStarts: List<Long>,
    )

    private data class RangeRequest(
        val start: Long,
        val endInclusive: Long,
        val threadName: String,
    )

    private class ControlledRangeUpstream(
        private val payload: ByteArray,
        private val headerDelayMs: Long,
        private val bodyChunkBytes: Int,
        private val bodyChunkDelayMs: Long,
    ) : Interceptor {
        private val requests = Collections.synchronizedList(mutableListOf<RangeRequest>())

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.method == "HEAD") {
                return response(
                    request = request,
                    code = 200,
                    headers = mapOf(
                        "Content-Length" to payload.size.toString(),
                        "Content-Type" to "video/mp4",
                    ),
                )
            }

            val range = RANGE.matchEntire(request.header("Range").orEmpty())
                ?: return response(request, code = 400)
            val requestedStart = range.groupValues[1].toLong()
            val requestedEnd = range.groupValues[2].toLong()
            if (requestedStart >= payload.size) {
                return response(
                    request = request,
                    code = 416,
                    headers = mapOf("Content-Range" to "bytes */${payload.size}"),
                )
            }

            if (headerDelayMs > 0L) Thread.sleep(headerDelayMs)
            val start = requestedStart.toInt()
            val endInclusive = min(requestedEnd, payload.lastIndex.toLong()).toInt()
            requests += RangeRequest(requestedStart, endInclusive.toLong(), Thread.currentThread().name)
            val body = payload.copyOfRange(start, endInclusive + 1)
            return response(
                request = request,
                code = 206,
                body = ThrottledResponseBody(
                    bytes = body,
                    mediaType = "video/mp4".toMediaType(),
                    maxChunkBytes = bodyChunkBytes,
                    chunkDelayMs = bodyChunkDelayMs,
                ),
                headers = mapOf(
                    "Content-Range" to "bytes $start-$endInclusive/${payload.size}",
                    "Content-Length" to body.size.toString(),
                    "Content-Type" to "video/mp4",
                ),
            )
        }

        fun snapshotRequests(): List<RangeRequest> = synchronized(requests) { requests.toList() }
    }

    private class BlockingPrefetchRangeUpstream(
        private val payload: ByteArray,
    ) : Interceptor {
        private val blockedStarted = Semaphore(0)
        private val release = CountDownLatch(1)
        @Volatile
        var blockedPrefetchStarts: Int = 0
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.method == "HEAD") {
                return response(
                    request = request,
                    code = 200,
                    headers = mapOf(
                        "Content-Length" to payload.size.toString(),
                        "Content-Type" to "video/mp4",
                    ),
                )
            }

            val range = RANGE.matchEntire(request.header("Range").orEmpty())
                ?: return response(request, code = 400)
            val requestedStart = range.groupValues[1].toLong()
            val requestedEnd = range.groupValues[2].toLong()
            if (requestedStart >= payload.size) {
                return response(
                    request = request,
                    code = 416,
                    headers = mapOf("Content-Range" to "bytes */${payload.size}"),
                )
            }

            if (Thread.currentThread().name.startsWith("external-media-demand-read-ahead-")) {
                synchronized(this) { blockedPrefetchStarts += 1 }
                blockedStarted.release()
                while (release.count > 0L) {
                    try {
                        release.await(25L, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Deliberately model a network call that does not stop when only the Future is cancelled.
                    }
                }
            }

            val start = requestedStart.toInt()
            val endInclusive = min(requestedEnd, payload.lastIndex.toLong()).toInt()
            val body = payload.copyOfRange(start, endInclusive + 1)
            return response(
                request = request,
                code = 206,
                body = body.toResponseBody("video/mp4".toMediaType()),
                headers = mapOf(
                    "Content-Range" to "bytes $start-$endInclusive/${payload.size}",
                    "Content-Length" to body.size.toString(),
                    "Content-Type" to "video/mp4",
                ),
            )
        }

        fun awaitBlockedPrefetch(): Boolean = blockedStarted.tryAcquire(2L, TimeUnit.SECONDS)

        fun releaseBlockedPrefetch() {
            release.countDown()
        }
    }

    private class ThrottledResponseBody(
        private val bytes: ByteArray,
        private val mediaType: MediaType,
        private val maxChunkBytes: Int,
        private val chunkDelayMs: Long,
    ) : ResponseBody() {
        private var offset = 0
        private var closed = false
        private val bufferedSource: BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (closed) return -1L
                if (offset >= bytes.size) return -1L
                val count = minOf(
                    byteCount,
                    maxChunkBytes.toLong(),
                    (bytes.size - offset).toLong(),
                ).toInt()
                if (chunkDelayMs > 0L) Thread.sleep(chunkDelayMs)
                sink.write(bytes, offset, count)
                offset += count
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

    companion object {
        private val RANGE = Regex("bytes=(\\d+)-(\\d+)")

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
                .message("controlled fixture")
                .body(body)
            headers.forEach { (name, value) -> builder.header(name, value) }
            return builder.build()
        }
    }
}
