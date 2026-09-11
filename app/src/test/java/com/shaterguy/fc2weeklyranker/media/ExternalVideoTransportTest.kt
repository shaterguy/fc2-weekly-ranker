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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class ExternalVideoTransportTest {
    private val payload = ByteArray(96) { index -> (index and 0xff).toByte() }
    private val sourceUrl = "https://media.example.test/protected/video.mp4?sig=a%2Bb&expires=999"
    private val referer = "https://source.example.test/post/123"
    private val userAgent = "FC2-Test-UA/1.0"

    @Test
    fun protectedUpstreamRejectsMissingCookieAndAcceptsFullSelectedVideoContext() {
        val upstream = ProtectedProgressiveUpstream(payload, referer, userAgent, "session=selected")
        val noCookie = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(upstream).build(),
            cookieProvider = { null },
        )
        val context = ExternalVideoRequestContext(referer, userAgent)
        assertThrows(IOException::class.java) { noCookie.readRange(sourceUrl, context, 0, 8) }

        val transport = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(upstream).build(),
            cookieProvider = { target -> if (target.startsWith("https://media.example.test/")) "session=selected" else null },
        )
        val read = transport.readRange(sourceUrl, context, 8, 12)

        assertArrayEquals(payload.copyOfRange(8, 20), read.bytes)
        assertEquals(payload.size.toLong(), read.totalLength)
        assertTrue(upstream.seen.any { it.url.encodedQuery == "sig=a%2Bb&expires=999" })
        assertTrue(upstream.seen.any { it.header("Referer") == referer })
        assertTrue(upstream.seen.any { it.header("User-Agent") == userAgent })
        assertTrue(upstream.seen.any { it.header("Cookie") == "session=selected" })
        assertTrue(upstream.seen.all { it.header("Accept-Encoding") == "identity" })
    }

    @Test
    fun seekableReaderStreamsSmallRangesAndSupportsForwardBackwardSeekWithoutFullDownload() {
        val upstream = ProtectedProgressiveUpstream(payload, referer, userAgent, "session=selected")
        val transport = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(upstream).build(),
            cookieProvider = { "session=selected" },
        )
        val reader = SeekableExternalHttpResource(
            url = sourceUrl,
            context = ExternalVideoRequestContext(referer, userAgent),
            transport = transport,
            chunkSize = 16,
            maxCachedChunks = 2,
        )

        assertEquals(payload.size.toLong(), reader.size())
        assertArrayEquals(payload.copyOfRange(0, 10), reader.read(0, 10))
        assertArrayEquals(payload.copyOfRange(64, 76), reader.read(64, 12))
        assertArrayEquals(payload.copyOfRange(8, 20), reader.read(8, 12))
        assertArrayEquals(payload.copyOfRange(95, 96), reader.read(95, 8))
        assertArrayEquals(ByteArray(0), reader.read(payload.size.toLong(), 4))

        val rangeHeaders = upstream.seen.mapNotNull { it.header("Range") }
        assertTrue(rangeHeaders.contains("bytes=0-15"))
        assertTrue(rangeHeaders.contains("bytes=64-79"))
        assertTrue(rangeHeaders.contains("bytes=16-31"))
        assertTrue(rangeHeaders.contains("bytes=80-95"))
        assertFalse(upstream.seen.any { it.method == "GET" && it.header("Range") == null })
        assertTrue(upstream.seen.all { it.header("Accept-Encoding") == "identity" })
    }

    @Test
    fun targetCookiesAreRecomputedAfterRedirectInsteadOfForwardingOriginCookie() {
        val seen = CopyOnWriteArrayList<Request>()
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            seen += request
            when (request.url.host) {
                "media.example.test" -> testResponse(
                    request,
                    code = 302,
                    headers = mapOf("Location" to "https://cdn.example.test/video.mp4?sig=redirected"),
                )
                "cdn.example.test" -> {
                    if (request.header("Cookie") != "cdn=session") return@Interceptor testResponse(request, 403)
                    testResponse(
                        request,
                        code = 206,
                        body = payload.copyOfRange(0, 8),
                        headers = mapOf(
                            "Content-Range" to "bytes 0-7/${payload.size}",
                            "Content-Length" to "8",
                            "Content-Type" to "video/mp4",
                        ),
                    )
                }
                else -> testResponse(request, 404)
            }
        }
        val transport = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            cookieProvider = { target ->
                when {
                    target.startsWith("https://media.example.test/") -> "origin=session"
                    target.startsWith("https://cdn.example.test/") -> "cdn=session"
                    else -> null
                }
            },
        )

        val read = transport.readRange(sourceUrl, ExternalVideoRequestContext(referer, userAgent), 0, 8)

        assertArrayEquals(payload.copyOfRange(0, 8), read.bytes)
        assertEquals("origin=session", seen[0].header("Cookie"))
        assertEquals("cdn=session", seen[1].header("Cookie"))
        assertEquals("sig=redirected", seen[1].url.encodedQuery)
        assertTrue(seen.all { it.header("Accept-Encoding") == "identity" })
    }

    @Test
    fun validEof416ReturnsEmptyButIgnoredSeekRangeFailsClosed() {
        val upstream = ProtectedProgressiveUpstream(payload, referer, userAgent, "session=selected")
        val transport = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(upstream).build(),
            cookieProvider = { "session=selected" },
        )
        val context = ExternalVideoRequestContext(referer, userAgent)

        val eof = transport.readRange(sourceUrl, context, payload.size.toLong(), 4)
        assertArrayEquals(ByteArray(0), eof.bytes)
        assertEquals(payload.size.toLong(), eof.totalLength)

        val ignoringRange = Interceptor { chain ->
            testResponse(
                chain.request(),
                code = 200,
                body = payload,
                headers = mapOf("Content-Length" to payload.size.toString(), "Content-Type" to "video/mp4"),
            )
        }
        val unsafe = ExternalHttpTransport(
            client = OkHttpClient.Builder().addInterceptor(ignoringRange).build(),
            cookieProvider = { "session=selected" },
        )
        assertThrows(IOException::class.java) { unsafe.readRange(sourceUrl, context, 32, 8) }
    }

    @Test
    fun hlsPrototypeRewritesVariantsSegmentsKeysMapsAndPreservesNonHttpSchemes() {
        val source = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000
            variant/low.m3u8?token=one
            #EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin?token=two"
            #EXT-X-MAP:URI="init.mp4?token=three"
            #EXTINF:4.0,
            segments/0001.ts?token=four
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",URI="https://cdn.example.test/audio.m3u8?token=five"
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://drm.example/key"
        """.trimIndent()
        val mapped = linkedMapOf<String, String>()
        val rewritten = rewriteHlsPlaylist(source, "https://media.example.test/path/master.m3u8?root=yes") { absolute ->
            mapped.getOrPut(absolute) { "content://test/session/token/root/r${mapped.size + 1}" }
        }

        assertTrue(mapped.containsKey("https://media.example.test/path/variant/low.m3u8?token=one"))
        assertTrue(mapped.containsKey("https://media.example.test/path/keys/key.bin?token=two"))
        assertTrue(mapped.containsKey("https://media.example.test/path/init.mp4?token=three"))
        assertTrue(mapped.containsKey("https://media.example.test/path/segments/0001.ts?token=four"))
        assertTrue(mapped.containsKey("https://cdn.example.test/audio.m3u8?token=five"))
        assertTrue(rewritten.contains("content://test/session/token/root/r1"))
        assertTrue(rewritten.contains("URI=\"content://test/session/token/root/"))
        assertTrue(rewritten.contains("URI=\"skd://drm.example/key\""))
    }

    @Test
    fun mediaKindDistinguishesProgressiveAndHlsPaths() {
        assertEquals(ExternalMediaKind.HLS, inferExternalMediaKind("https://example.test/master.m3u8?sig=1"))
        assertEquals(ExternalMediaKind.MP4, inferExternalMediaKind("https://example.test/video.mp4?sig=1"))
        assertEquals(ExternalMediaKind.WEBM, inferExternalMediaKind("https://example.test/video.webm"))
        assertEquals(
            ExternalMediaKind.HLS,
            inferExternalMediaKind("https://example.test/opaque", "application/vnd.apple.mpegurl; charset=utf-8"),
        )
    }

    private class ProtectedProgressiveUpstream(
        private val payload: ByteArray,
        private val expectedReferer: String,
        private val expectedUserAgent: String,
        private val expectedCookie: String,
    ) : Interceptor {
        val seen = CopyOnWriteArrayList<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            seen += request
            if (
                request.header("Referer") != expectedReferer ||
                request.header("User-Agent") != expectedUserAgent ||
                request.header("Cookie") != expectedCookie ||
                request.header("Accept-Encoding") != "identity"
            ) {
                return testResponse(request, 403)
            }
            if (request.method == "HEAD") {
                return testResponse(
                    request,
                    200,
                    headers = mapOf("Content-Length" to payload.size.toString(), "Content-Type" to "video/mp4"),
                )
            }
            val range = request.header("Range") ?: return testResponse(request, 400)
            val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range) ?: return testResponse(request, 400)
            val start = match.groupValues[1].toLong()
            val requestedEnd = match.groupValues[2].toLong()
            if (start >= payload.size) {
                return testResponse(
                    request,
                    416,
                    headers = mapOf("Content-Range" to "bytes */${payload.size}"),
                )
            }
            val end = minOf(requestedEnd, payload.lastIndex.toLong())
            val body = payload.copyOfRange(start.toInt(), end.toInt() + 1)
            return testResponse(
                request,
                206,
                body = body,
                headers = mapOf(
                    "Content-Range" to "bytes $start-$end/${payload.size}",
                    "Content-Length" to body.size.toString(),
                    "Content-Type" to "video/mp4",
                ),
            )
        }
    }

    companion object {
        private fun testResponse(
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
