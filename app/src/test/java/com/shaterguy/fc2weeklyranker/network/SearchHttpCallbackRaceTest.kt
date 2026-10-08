package com.shaterguy.fc2weeklyranker.network

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowWebView
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Real transport/policy/callbacks; only document evaluation and Main handoff are controlled. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [HttpRaceBrowserShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SearchHttpCallbackRaceTest {
    private lateinit var transport: WebViewApplicationPageTransport
    private val events = ConcurrentLinkedQueue<String>()

    @Before fun setUp() {
        HttpRaceBrowserShadow.reset()
        val main = Handler(Looper.getMainLooper()).asCoroutineDispatcher("HTTP callback fixture")
        Dispatchers.setMain(object : MainCoroutineDispatcher() {
            override val immediate: MainCoroutineDispatcher get() = this
            override fun isDispatchNeeded(context: CoroutineContext) = main.immediate.isDispatchNeeded(context)
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                main.dispatch(context, Runnable {
                    block.run()
                    HttpRaceBrowserShadow.afterMainTask()
                })
            }
        })
        val parser = AvseeClient(OkHttpClient.Builder().addInterceptor {
            throw AssertionError("Real HTTP is forbidden")
        }.build())
        transport = WebViewApplicationPageTransport(
            ApplicationProvider.getApplicationContext<Application>(),
            configuredOrigins = { setOf(ORIGIN) },
            hasUsableSearchResults = parser::hasUsableApplicationSearchResults,
            fixtureResponse = { WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf())) },
            fixtureDiagnostic = { events += it },
        )
    }

    @After fun tearDown() = runBlocking<Unit> {
        try {
            val closing = async(Dispatchers.Default) { transport.close() }
            waitUntil { closing.isCompleted }
            closing.await()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun callbackAfterStatusReadCannotFailThePreviousDocument() = verifyLateCallback(challenge = false)
    @Test fun callbackAfterStatusReadCannotFailAChallengeDocument() = verifyLateCallback(challenge = true)

    private fun verifyLateCallback(challenge: Boolean) = runBlocking<Unit> {
        supervisorScope {
            HttpRaceBrowserShadow.showChallenge = challenge
            val failure = AtomicReference<Throwable?>()
            val fetching = async(Dispatchers.Default) {
                try { transport.fetch("$ORIGIN/bbs/search.php?stx=fixture&onetable=javfc2&page=1") }
                catch (error: Throwable) { failure.set(error); throw error }
            }
            try {
                waitUntil { HttpRaceBrowserShadow.callbackDelivered && (fetching.isCompleted || HttpRaceBrowserShadow.snapshots >= 2) }
                assertTrue("Expected one injected 403 on the real client; events=$events",
                    events.any { "http_error status=403 main=true" in it })
                assertTrue("A 403 between the status read and failure lookup ended search: ${failure.get()}; events=$events",
                    fetching.isActive)
                HttpRaceBrowserShadow.releaseResult = true
                waitUntil { fetching.isCompleted }
                assertTrue(fetching.await().contains("NEUTRAL_RECOVERED_RESULT"))
                assertEquals("Deferral must not recreate the session", 1, HttpRaceBrowserShadow.created)
            } finally {
                if (!fetching.isCompleted) fetching.cancel()
                waitUntil { fetching.isCompleted }
            }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "Timed out at the controlled Main callback boundary" }
            Thread.yield()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    companion object { const val ORIGIN = "https://fixture.invalid" }
}

@Implements(WebView::class)
class HttpRaceBrowserShadow : ShadowWebView() {
    @RealObject private lateinit var browser: WebView

    @Implementation override fun setWebViewClient(client: WebViewClient) {
        super.setWebViewClient(client)
        current = browser
        created += 1
    }

    @Implementation override fun evaluateJavascript(script: String, callback: ValueCallback<String>) {
        if (script.contains("document.documentElement.outerHTML")) {
            callback.onReceiveValue(JSONObject.quote("<ul class='search-results'><li class='search-result'>" +
                "<div class='search-result-title'><a href='/bbs/board.php?bo_table=javfc2&wr_id=42'>Recovered</a></div>" +
                "</li></ul><p>NEUTRAL_RECOVERED_RESULT</p>"))
        } else {
            snapshots += 1
            val url = if (releaseResult || showChallenge) lastLoadedUrl else "about:blank"
            val snapshot = JSONObject().put("url", url).put("ready", "complete")
                .put("timeOrigin", if (releaseResult) 3.0 else 1.0).put("domContentLoaded", true)
                .put("responseStatus", if (releaseResult) 200 else 0)
                .put("hasSearchRows", releaseResult).put("hasTagRows", false)
                .put("title", if (showChallenge && !releaseResult) "Just a moment..." else "Neutral document")
                .put("text", if (showChallenge && !releaseResult) "Performing security verification" else "")
            if (phase == 0) phase = 1
            callback.onReceiveValue(JSONObject.quote(snapshot.toString()))
        }
    }

    companion object {
        var current: WebView? = null
        var phase = 0
        @Volatile var created = 0
        @Volatile var snapshots = 0
        @Volatile var callbackDelivered = false
        @Volatile var showChallenge = false
        @Volatile var releaseResult = false

        fun reset() {
            current = null; phase = 0; created = 0; snapshots = 0
            callbackDelivered = false; showChallenge = false; releaseResult = false
        }

        fun afterMainTask() {
            if (phase == 1) { phase = 2; return } // Snapshot evaluation itself is complete.
            if (phase != 2) return
            phase = 3 // The next task just sampled HTTP 200; deliver 403 before failure lookup.
            val view = requireNotNull(current)
            val url = Shadows.shadowOf(view).lastLoadedUrl
            val request = object : WebResourceRequest {
                override fun getUrl() = Uri.parse(url)
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = false
                override fun getMethod() = "GET"
                override fun getRequestHeaders(): Map<String, String> = emptyMap()
            }
            view.webViewClient.onReceivedHttpError(view, request,
                WebResourceResponse("text/html", "UTF-8", 403, "Neutral pending response",
                    emptyMap(), ByteArrayInputStream(byteArrayOf())))
            callbackDelivered = true
        }
    }
}
