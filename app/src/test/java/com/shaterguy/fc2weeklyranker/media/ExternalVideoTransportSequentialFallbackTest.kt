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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ExternalVideoTransportSequentialFallbackTest {
    private val payload = ByteArray(160) { index -> ((index * 17) and 0xff).toByte() }
    private val sourceUrl = "https://media.example.test/protected/video.mp4?sig=opaque"
    private val context = ExternalVideoRequestContext(
        referer = "https://source.example.test/post/123",
        userAgent = "FC2-Test-UA/1.0",
    )

    @Test
    fun rangeIgnored200KeepsOneSequentialResponseAcrossForwardChunksAndReopensOnlyForBackwardSeek() {
        val rangeProbeCount = AtomicInteger(0)
        val sequentialGetCount = AtomicInteger(0)
        val seen = CopyOnWriteArrayList<Request>()
        val upstream = Interceptor { chain ->
            val request = chain.request()
            seen += request
            when {
                request.method == "HEAD" -> response(
                    request,
                    200,
                    headers = mapOf(
                        "Content-Length" to payload.size.toString(),
                        "Content-Type" to "video/mp4",
                    ),
                )
                request.header("Range") != null -> {
                    rangeProbeCount.incrementAndGet()
                    response(
                        request,
                        200,
                        body = payload,
                        headers = mapOf(
                            "Content-Length" to payload.size.toString(),
                            "Content-Type" to "video/mp4",
                        ),
                    )
                }
                else -> {
                    sequentialGetCount.incrementAndGet()
                    response(
                        request,
                        200,
                        body = payload,
                        headers = mapOf(
                            "Content-Length" to payload.size.toString(),
                            "Content-Type" to "video/mp4",
                        ),
                    )
                }
            }
        }
        val reader = SeekableExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport(upstream),
            chunkSize = 16,
            maxCachedChunks = 1,
        )

        assertArrayEquals(payload.copyOfRange(32, 72), reader.read(32, 40))
        assertArrayEquals(payload.copyOfRange(80, 96), reader.read(80, 16))
        assertEquals(1, rangeProbeCount.get())
        assertEquals(1, sequentialGetCount.get())

        assertArrayEquals(payload.copyOfRange(0, 8), reader.read(0, 8))
        assertEquals(1, rangeProbeCount.get())
        assertEquals(2, sequentialGetCount.get())
        assertTrue(seen.all { it.header("Accept-Encoding") == "identity" })
        reader.close()
    }

    @Test
    fun transient503IsRetriedAtTheExactSameRangeOffset() {
        val attempts = CopyOnWriteArrayList<String>()
        val upstream = Interceptor { chain ->
            val request = chain.request()
            if (request.method == "HEAD") {
                return@Interceptor response(
                    request,
                    200,
                    headers = mapOf("Content-Length" to payload.size.toString()),
                )
            }
            val range = request.header("Range").orEmpty()
            attempts += range
            if (attempts.size == 1) {
                response(request, 503)
            } else {
                val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range)
                    ?: error("missing range")
                val start = match.groupValues[1].toInt()
                val end = minOf(match.groupValues[2].toInt(), payload.lastIndex)
                val body = payload.copyOfRange(start, end + 1)
                response(
                    request,
                    206,
                    body = body,
                    headers = mapOf(
                        "Content-Range" to "bytes $start-$end/${payload.size}",
                        "Content-Length" to body.size.toString(),
                    ),
                )
            }
        }
        val reader = SeekableExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport(upstream),
            chunkSize = 16,
            maxCachedChunks = 1,
        )

        assertArrayEquals(payload.copyOfRange(32, 44), reader.read(32, 12))
        assertEquals(listOf("bytes=32-47", "bytes=32-47"), attempts.toList())
    }

    @Test
    fun permanent401403And404FailWithoutRetryLoop() {
        listOf(401, 403, 404).forEach { status ->
            val getCount = AtomicInteger(0)
            val upstream = Interceptor { chain ->
                val request = chain.request()
                if (request.method == "HEAD") {
                    response(
                        request,
                        200,
                        headers = mapOf("Content-Length" to payload.size.toString()),
                    )
                } else {
                    getCount.incrementAndGet()
                    response(request, status)
                }
            }
            val reader = SeekableExternalHttpResource(
                url = sourceUrl,
                context = context,
                transport = transport(upstream),
                chunkSize = 16,
                maxCachedChunks = 1,
            )

            assertThrows(IOException::class.java) { reader.read(32, 8) }
            assertEquals("HTTP $status must not be retried", 1, getCount.get())
        }
    }

    @Test
    fun representationLengthChangeFailsBeforeCachingTheChunk() {
        val getCount = AtomicInteger(0)
        val upstream = Interceptor { chain ->
            val request = chain.request()
            if (request.method == "HEAD") {
                return@Interceptor response(
                    request,
                    200,
                    headers = mapOf("Content-Length" to payload.size.toString()),
                )
            }
            getCount.incrementAndGet()
            val body = payload.copyOfRange(0, 16)
            response(
                request,
                206,
                body = body,
                headers = mapOf(
                    "Content-Range" to "bytes 0-15/${payload.size + 1}",
                    "Content-Length" to body.size.toString(),
                ),
            )
        }
        val reader = SeekableExternalHttpResource(
            url = sourceUrl,
            context = context,
            transport = transport(upstream),
            chunkSize = 16,
            maxCachedChunks = 1,
        )

        assertThrows(IOException::class.java) { reader.read(0, 8) }
        assertEquals(1, getCount.get())
    }

    private fun transport(upstream: Interceptor): ExternalHttpTransport = ExternalHttpTransport(
        client = OkHttpClient.Builder().addInterceptor(upstream).build(),
        cookieProvider = { "session=selected" },
    )

    companion object {
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
