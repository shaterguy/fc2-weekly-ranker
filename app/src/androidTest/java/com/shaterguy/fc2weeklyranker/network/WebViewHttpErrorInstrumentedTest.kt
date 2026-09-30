package com.shaterguy.fc2weeklyranker.network

import android.webkit.WebView
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

// Intercepted HTTP responses do not trigger onReceivedHttpError on every WebView provider.
// HTTP cases explicitly exercise the real client's callback contract on the main thread.
// The broken-stream case still exercises the provider's actual onReceivedError callback.
@RunWith(AndroidJUnit4::class)
class WebViewHttpErrorInstrumentedTest {
    @Test
    fun mainFrame404IsNotReturnedAsAnEmptySuccess() = verifyHttpFailure(404)

    @Test
    fun mainFrame429IsNotReturnedAsAnEmptySuccess() = verifyHttpFailure(429)

    @Test
    fun mainFrame503IsNotReturnedAsAnEmptySuccess() = verifyHttpFailure(503)

    @Test
    fun successfulEmptyDocumentRemainsUsable() = withTransport { transport, _ ->
        assertTrue(transport.fetch("$ORIGIN/ok.html").contains("NEUTRAL_EMPTY_DOCUMENT"))
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
        assertTrue("main-frame load callback was not exercised", synchronized(events) { events.any { "load_error" in it && "main=true" in it } })
        assertTrue(transport.fetch("$ORIGIN/ok.html").contains("NEUTRAL_EMPTY_DOCUMENT"))
    }

    private fun verifyHttpFailure(status: Int) = withTransport { transport, events ->
        val failure = runCatching { withTimeout(10_000) { transport.fetch("$ORIGIN/status-$status.html") } }.exceptionOrNull()
        assertTrue("HTTP $status was accepted as success or lost its status: $failure; events=$events",
            failure is IllegalStateException && causes(failure).any { "HTTP_$status" in it })
        assertTrue("main-frame HTTP callback was not exercised", synchronized(events) { events.any { "http_error status=$status main=true" in it } })
        assertTrue("failed navigation poisoned a later successful request",
            transport.fetch("$ORIGIN/ok.html").contains("NEUTRAL_EMPTY_DOCUMENT"))
    }

    private fun causes(error: Throwable): List<String> =
        generateSequence(error) { it.cause }.map { it.message.orEmpty() }.toList()

    private fun withTransport(block: suspend (WebViewApplicationPageTransport, List<String>) -> Unit) = runBlocking<Unit> {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val httpCallbacks = ConcurrentLinkedQueue<Pair<WebResourceRequest, WebResourceResponse>>()
        val transport = WebViewApplicationPageTransport(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "NeutralWebViewTransportTest",
            fixtureResponse = { request ->
                respond(request).also { response ->
                    if (response.statusCode >= 400) httpCallbacks.add(request to response)
                }
            },
            fixtureDiagnostic = { events.add(it) },
        )
        val callbackDelivery = launch(Dispatchers.Main.immediate) {
            while (isActive) {
                var callback = httpCallbacks.poll()
                while (callback != null) {
                    val view = currentWebView(transport)
                    view.webViewClient.onReceivedHttpError(view, callback.first, callback.second)
                    callback = httpCallbacks.poll()
                }
                delay(10)
            }
        }
        try {
            withTimeout(20_000) { block(transport, events) }
        } finally {
            withContext(NonCancellable) {
                callbackDelivery.cancelAndJoin()
                transport.close()
            }
        }
    }

    // Reflection stays in androidTest; production exposes no additional callback injection API.
    private fun currentWebView(transport: WebViewApplicationPageTransport): WebView {
        val sessionField = WebViewApplicationPageTransport::class.java.getDeclaredField("session")
        sessionField.isAccessible = true
        val session = checkNotNull(sessionField.get(transport))
        val viewField = session.javaClass.getDeclaredField("webView")
        viewField.isAccessible = true
        return viewField.get(session) as WebView
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
