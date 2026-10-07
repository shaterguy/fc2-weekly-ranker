package com.shaterguy.fc2weeklyranker.network

import android.webkit.WebView
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import com.shaterguy.fc2weeklyranker.ui.searchNeedsAuthentication

// Intercepted HTTP responses do not trigger onReceivedHttpError on every WebView provider.
// HTTP cases explicitly exercise the real client's callback contract on the main thread.
// The broken-stream case still exercises the provider's actual onReceivedError callback.
@RunWith(AndroidJUnit4::class)
class WebViewHttpErrorInstrumentedTest {
    @Test
    fun foreignMainFrameFormPostIsBlockedBeforeFixtureHandling() {
        val foreignFixtureCalls = AtomicInteger()
        val html = """<!doctype html><html><head><title>Neutral POST fixture</title></head><body>
            <form id="neutral-post" method="POST" action="https://foreign.example.test/bbs/login.php">
            <input name="neutral-field" value="synthetic-only"></form>POST_FIXTURE_READY</body></html>"""
        withTransport(responder = { request ->
            if (request.url.host != "fixture.invalid") foreignFixtureCalls.incrementAndGet()
            WebResourceResponse("text/html", "UTF-8", 200, "OK", emptyMap(), ByteArrayInputStream(html.toByteArray()))
        }) { transport, events ->
            assertTrue(transport.fetch("$ORIGIN/post-fixture.html").contains("POST_FIXTURE_READY"))
            withContext(Dispatchers.Main.immediate) {
                currentWebView(transport)!!.evaluateJavascript("document.getElementById('neutral-post').submit()", null)
            }
            withTimeout(5_000) {
                while (synchronized(events) { events.none { it == "origin_blocked method=POST" } }) delay(50)
            }
            assertEquals(0, foreignFixtureCalls.get())
        }
    }
    @Test
    fun unconfiguredOriginIsRejectedBeforeCreatingBrowserOrNetwork() = runBlocking<Unit> {
        val transport = WebViewApplicationPageTransport(
            InstrumentationRegistry.getInstrumentation().targetContext,
            configuredOrigins = { setOf(ORIGIN) },
        )
        try {
            val error = runCatching { transport.fetch("https://foreign.example.test/bbs/search.php") }.exceptionOrNull()
            assertTrue(error is PageLoadException)
            assertEquals(PageFailureStage.SESSION, (error as PageLoadException).stage)
            assertEquals(PageFailureReason.ERROR, error.reason)
            assertEquals(null, currentWebView(transport))
        } finally {
            transport.close()
        }
    }
    @Test
    fun mainFrame404IsNotReturnedAsAnEmptySuccess() = verifyHttpFailure(404)

    @Test
    fun mainFrame429IsNotReturnedAsAnEmptySuccess() = verifyHttpFailure(429)

    @Test
    fun mainFrame503IsNotReturnedAsAnEmptySuccess() = verifyHttpFailure(503)

    @Test
    fun successfulEmptyDocumentRemainsUsable() = withTransport { transport, _ ->
        assertTrue(transport.fetch("$ORIGIN/ok.html").contains("NEUTRAL_EMPTY_DOCUMENT"))
        val acceptsThirdParty = withContext(Dispatchers.Main.immediate) {
            CookieManager.getInstance().acceptThirdPartyCookies(currentWebView(transport)!!)
        }
        assertFalse(acceptsThirdParty)
    }

    @Test
    fun mainFrame403ChallengeCanReloadIntoRequestedSearchDocument() {
        val attempts = AtomicInteger()
        val parser = AvseeClient(OkHttpClient())
        withTransport(
            responder = { request -> challengeThenSearchResult(request, attempts) },
            hasUsableSearchResults = parser::hasUsableApplicationSearchResults,
        ) { transport, events ->
            val url = parser.buildSearchUrl(ORIGIN, "fixture", 1, "javfc2")
            val html = withTimeout(10_000) { transport.fetch(url) }
            assertTrue(html.contains("CHALLENGE_RECOVERED_RESULT"))
            assertTrue("challenge did not cause a same-document reload", attempts.get() >= 2)
            assertTrue(
                "main-frame challenge HTTP callback was not exercised",
                synchronized(events) { events.any { "http_error status=403 main=true" in it } },
            )
        }
    }

    @Test
    fun challengeReloadWithSuccessfulNavigationStatusAllowsZeroResults() {
        val attempts = AtomicInteger()
        val parser = AvseeClient(OkHttpClient())
        withTransport(
            responder = { request ->
                if (!request.isForMainFrame || request.url.path != "/bbs/search.php") {
                    response(404, "Neutral missing resource")
                } else if (attempts.getAndIncrement() == 0) {
                    response(403, "<!doctype html><html><head><title>Just a moment...</title></head>" +
                        "<body>Performing security verification<script>setTimeout(function(){location.reload()},50)</script></body></html>")
                } else {
                    // Controlled same-document status input for providers too old to expose responseStatus.
                    response(200, "<!doctype html><html><head><script>" +
                        "var originalEntries=performance.getEntriesByType.bind(performance);" +
                        "performance.getEntriesByType=function(type){var e=originalEntries(type);" +
                        "return type==='navigation'?e.map(function(v){return {domContentLoadedEventEnd:v.domContentLoadedEventEnd,responseStatus:200}}):e};" +
                        "</script></head><body><ul class='search-results'></ul><p>EMPTY_SEARCH_RESPONSE</p></body></html>")
                }
            },
            hasUsableSearchResults = parser::hasUsableApplicationSearchResults,
        ) { transport, _ ->
            val url = parser.buildSearchUrl(ORIGIN, "fixture", 1, "javfc2")
            val html = withTimeout(10_000) { transport.fetch(url) }
            assertTrue(html.contains("EMPTY_SEARCH_RESPONSE"))
            assertTrue(parser.parseSearchPage(html, url).posts.isEmpty())
            assertTrue(attempts.get() >= 2)
        }
    }

    @Test
    fun protectedSearch403RetainsLoginRecoveryForStoredHttpDiagnostic() {
        assertTrue(
            "A protected search HTTP 403 must offer the existing first-party login recovery",
            searchNeedsAuthentication("검색 실패: 페이지 요청에 실패했습니다. [DOCUMENT/HTTP_403]"),
        )
    }

    @Test
    fun persistent403ChallengeRetainsHttpStatusAtDocumentTimeout() {
        withTransport(
            responder = { request ->
                if (request.isForMainFrame && request.url.path == "/bbs/search.php") {
                    response(403, "<!doctype html><html><head><title>Just a moment...</title></head>" +
                        "<body>Performing security verification</body></html>")
                } else response(404, "Neutral missing resource")
            },
            timeoutMillis = 45_000,
        ) { transport, events ->
            val failure = runCatching { transport.fetch("$ORIGIN/bbs/search.php?stx=fixture") }.exceptionOrNull()
            assertTrue(
                "Persistent main-frame HTTP 403 was lost to a generic timeout: $failure; events=$events",
                failure?.message.orEmpty().contains("[DOCUMENT/HTTP_403]"),
            )
            assertTrue(
                "Persistent challenge HTTP callback was not exercised",
                synchronized(events) { events.any { "http_error status=403 main=true" in it } },
            )
        }
    }

    @Test
    fun subresource404DoesNotFailTheMainDocumentOrNextNavigation() = withTransport { transport, events ->
        val html = transport.fetch("$ORIGIN/subresource.html")
        assertTrue(html.contains("NEUTRAL_DOCUMENT_WITH_IMAGE"))
        withTimeout(5_000) {
            while (!synchronized(events) { events.any { "http_error status=404 main=false" in it } }) {
                delay(25)
            }
        }
        assertTrue(transport.fetch("$ORIGIN/ok.html").contains("NEUTRAL_EMPTY_DOCUMENT"))
    }

    @Test
    fun mainFrameReadFailureDoesNotReturnABrowserErrorDocument() = withTransport { transport, events ->
        val failure = runCatching { withTimeout(10_000) { transport.fetch("$ORIGIN/read-failure.html") } }.exceptionOrNull()
        assertTrue("expected an explicit WebView load failure, got $failure; events=$events",
            failure is IllegalStateException && causes(failure).any { "WEBVIEW_ERROR_" in it })
        assertTrue("user-visible failure must retain a safe document stage and load code",
            failure?.message.orEmpty().contains("[DOCUMENT/WEBVIEW_ERROR_"))
        assertTrue("main-frame load callback was not exercised", synchronized(events) { events.any { "load_error" in it && "main=true" in it } })
        assertTrue(transport.fetch("$ORIGIN/ok.html").contains("NEUTRAL_EMPTY_DOCUMENT"))
    }

    private fun verifyHttpFailure(status: Int) = withTransport { transport, events ->
        val failure = runCatching { withTimeout(10_000) { transport.fetch("$ORIGIN/status-$status.html") } }.exceptionOrNull()
        assertTrue("HTTP $status was accepted as success or lost its status: $failure; events=$events",
            failure is IllegalStateException && causes(failure).any { "HTTP_$status" in it })
        assertTrue("user-visible failure must retain a safe document stage and HTTP code",
            failure?.message.orEmpty().contains("[DOCUMENT/HTTP_$status]"))
        assertTrue("main-frame HTTP callback was not exercised", synchronized(events) { events.any { "http_error status=$status main=true" in it } })
        assertTrue("failed navigation poisoned a later successful request",
            transport.fetch("$ORIGIN/ok.html").contains("NEUTRAL_EMPTY_DOCUMENT"))
    }

    private fun causes(error: Throwable): List<String> =
        generateSequence(error) { it.cause }.map { it.message.orEmpty() }.toList()

    private fun withTransport(
        responder: (WebResourceRequest) -> WebResourceResponse = { request -> respond(request) },
        hasUsableSearchResults: (String, String) -> Boolean = { _, _ -> false },
        timeoutMillis: Long = 20_000,
        block: suspend (WebViewApplicationPageTransport, List<String>) -> Unit,
    ) = runBlocking<Unit> {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val httpCallbacks = ConcurrentLinkedQueue<Pair<WebResourceRequest, WebResourceResponse>>()
        val transport = WebViewApplicationPageTransport(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "NeutralWebViewTransportTest",
            configuredOrigins = { setOf(ORIGIN) },
            hasUsableSearchResults = hasUsableSearchResults,
            fixtureResponse = { request ->
                responder(request).also { response ->
                    if (response.statusCode >= 400) httpCallbacks.add(request to response)
                }
            },
            fixtureDiagnostic = { events.add(it) },
        )
        val callbackDelivery = launch(Dispatchers.Main.immediate) {
            while (isActive) {
                var callback = httpCallbacks.poll()
                while (callback != null) {
                    val pending = callback
                    val view = currentWebView(transport)
                    view?.let { it.webViewClient.onReceivedHttpError(it, pending.first, pending.second) }
                    callback = httpCallbacks.poll()
                }
                delay(10)
            }
        }
        try {
            withTimeout(timeoutMillis) { block(transport, events) }
        } finally {
            withContext(NonCancellable) {
                callbackDelivery.cancelAndJoin()
                transport.close()
            }
        }
    }

    // Reflection stays in androidTest; production exposes no additional callback injection API.
    private fun currentWebView(transport: WebViewApplicationPageTransport): WebView? {
        val sessionField = WebViewApplicationPageTransport::class.java.getDeclaredField("session")
        sessionField.isAccessible = true
        val session = sessionField.get(transport) ?: return null
        val viewField = session.javaClass.getDeclaredField("webView")
        viewField.isAccessible = true
        return viewField.get(session) as WebView
    }

    private fun challengeThenSearchResult(
        request: WebResourceRequest,
        attempts: AtomicInteger,
    ): WebResourceResponse {
        if (request.url.scheme != "https" || request.url.host != "fixture.invalid") {
            return response(403, "unexpected fixture host")
        }
        if (!request.isForMainFrame || request.url.path != "/bbs/search.php") {
            return response(404, "Neutral missing resource")
        }
        return if (attempts.getAndIncrement() == 0) {
            response(
                403,
                "<!doctype html><html><head><title>Just a moment...</title></head>" +
                    "<body>Performing security verification" +
                    "<script>setTimeout(function(){location.reload()},50)</script></body></html>",
            )
        } else {
            response(
                200,
                "<!doctype html><html><body><ul class='search-results'><li class='search-result'>" +
                    "<div class='search-result-title'><a href='/bbs/board.php?bo_table=javfc2&wr_id=42'>Recovered</a></div>" +
                    "</li></ul><p>CHALLENGE_RECOVERED_RESULT</p></body></html>",
            )
        }
    }

    private fun respond(request: WebResourceRequest): WebResourceResponse {
        if (request.url.scheme != "https" || request.url.host != "fixture.invalid") {
            return response(403, "unexpected fixture host")
        }
        val path = request.url.path.orEmpty()
        if (path == "/read-failure.html") {
            val broken = object : InputStream() {
                override fun read(): Int = throw IOException("synthetic main-frame read failure")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    throw IOException("synthetic main-frame read failure")
            }
            return WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), broken)
        }
        val status = Regex("/status-(404|429|503)\\.html").matchEntire(path)?.groupValues?.get(1)?.toInt()
        if (status != null) return response(status, "<!doctype html><html><body>Neutral failure</body></html>")
        return when (path) {
            "/ok.html" -> response(200, "<!doctype html><html><body>NEUTRAL_EMPTY_DOCUMENT</body></html>")
            "/subresource.html" -> response(200, "<!doctype html><html><body>NEUTRAL_DOCUMENT_WITH_IMAGE<img src='/missing.png'></body></html>")
            else -> response(404, "Neutral missing resource")
        }
    }

    private fun response(status: Int, body: String) = WebResourceResponse(
        "text/html", "UTF-8", status, if (status == 200) "OK" else "Synthetic failure",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
    )

    companion object {
        private const val ORIGIN = "https://fixture.invalid"
    }
}
