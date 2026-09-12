package com.shaterguy.fc2weeklyranker.media

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ExternalVideoTransportAfterStartContinuityTest {
    private val sourceUrl = "https://media.example.test/protected/video.mp4"
    private val context = ExternalVideoRequestContext(
        referer = "https://source.example.test/post/continuity",
        userAgent = "FC2-Continuity-Test/1.0",
    )

    @Test
    fun short206ResponsesAfterInitialChunkKeepMakingProgressPastThirtyTwoRequests() {
        val chunkSize = 512 * 1024
        val payload = ByteArray(chunkSize * 2 + 257) { index -> ((index * 31) and 0xff).toByte() }
        val upstream = AfterStartCappedRangeUpstream(
            payload = payload,
            capFromOffset = chunkSize.toLong(),
            maxBodyBytes = 8 * 1024,
        )
        val transport = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(upstream).build(),
            cookieProvider = { "session=selected" },
        )
        val reader = SeekableExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport,
            maxCachedChunks = 1,
        )

        val initial = reader.read(0, 64)
        assertArrayEquals(payload.copyOfRange(0, 64), initial)

        val afterStartOffset = chunkSize
        val afterStart = reader.read(afterStartOffset.toLong(), 384 * 1024)

        assertArrayEquals(
            payload.copyOfRange(afterStartOffset, afterStartOffset + 384 * 1024),
            afterStart,
        )
        assertTrue(
            "second production-sized chunk must tolerate more than 32 successful short 206 responses",
            upstream.cappedRangeRequestCount.get() > 32,
        )
        assertFalse(upstream.seen.any { it.method == "GET" && it.header("Range") == null })
    }

    private class AfterStartCappedRangeUpstream(
        private val payload: ByteArray,
        private val capFromOffset: Long,
        private val maxBodyBytes: Int,
    ) : Interceptor {
        val seen = CopyOnWriteArrayList<Request>()
        val cappedRangeRequestCount = AtomicInteger(0)

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            seen += request
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

            val range = request.header("Range") ?: return response(request, 400)
            val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range) ?: return response(request, 400)
            val start = match.groupValues[1].toLong()
            val requestedEnd = match.groupValues[2].toLong()
            if (start >= payload.size) {
                return response(
                    request = request,
                    code = 416,
                    headers = mapOf("Content-Range" to "bytes */${payload.size}"),
                )
            }

            val responseEnd = if (start >= capFromOffset) {
                cappedRangeRequestCount.incrementAndGet()
                minOf(requestedEnd, start + maxBodyBytes - 1L, payload.lastIndex.toLong())
            } else {
                minOf(requestedEnd, payload.lastIndex.toLong())
            }
            val body = payload.copyOfRange(start.toInt(), responseEnd.toInt() + 1)
            return response(
                request = request,
                code = 206,
                body = body,
                headers = mapOf(
                    "Content-Range" to "bytes $start-$responseEnd/${payload.size}",
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
                .message("test")
                .body(body.toResponseBody("application/octet-stream".toMediaType()))
            headers.forEach { (name, value) -> builder.header(name, value) }
            return builder.build()
        }
    }
}
