package com.shaterguy.fc2weeklyranker.network

import android.app.Application
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.json.JSONTokener
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
import java.util.concurrent.CopyOnWriteArrayList

/** Production transport, origin guard, parser readiness and cleanup; neutral JS responses are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [RankingBrowserShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class RankingHtmlTransportTest {
    private lateinit var transport: WebViewApplicationPageTransport

    @Before fun setUp() {
        RankingBrowserShadow.reset()
        transport = WebViewApplicationPageTransport(
            ApplicationProvider.getApplicationContext<Application>(),
            configuredOrigins = { setOf(ORIGIN) },
            fixtureResponse = { WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf())) },
        )
    }

    @After fun tearDown() = runBlocking<Unit> {
        val closing = async(Dispatchers.Default) { transport.close() }
        waitUntil { closing.isCompleted }
        closing.await()
    }

    @Test fun warmRankingFetchReturnsFreshHtmlWithoutAnotherNavigation() = runBlocking<Unit> {
        warm()
        val before = RankingBrowserShadow.documents.size
        val result = async(Dispatchers.Default) { transport.fetchRankingPage(BOARD) }
        waitUntil { result.isCompleted }
        assertTrue(result.await().contains("FETCH_RESULT"))
        assertEquals(before, RankingBrowserShadow.documents.size)
        assertEquals(1, RankingBrowserShadow.starts)
        assertEquals(1, RankingBrowserShadow.cleanups)
        assertEquals(0, RankingBrowserShadow.activeSlots)
    }

    @Test fun ordinaryDetailFetchKeepsNavigationAndDoesNotUseRankingScript() = runBlocking<Unit> {
        warm()
        val before = RankingBrowserShadow.documents.size
        val result = async(Dispatchers.Default) { transport.fetch("$ORIGIN/javfc2/43") }
        waitUntil { result.isCompleted }
        assertTrue(result.await().contains("NAVIGATION_RESULT"))
        assertEquals(before + 1, RankingBrowserShadow.documents.size)
        assertEquals(0, RankingBrowserShadow.starts)
    }

    @Test fun coldRankingSessionUsesNavigationBeforeHtmlFetch() = runBlocking<Unit> {
        val first = async(Dispatchers.Default) { transport.fetchRankingPage(BOARD) }
        waitUntil { first.isCompleted }
        assertTrue(first.await().contains("NAVIGATION_RESULT"))
        assertEquals(0, RankingBrowserShadow.starts)
        val second = async(Dispatchers.Default) { transport.fetchRankingPage(BOARD) }
        waitUntil { second.isCompleted }
        assertTrue(second.await().contains("FETCH_RESULT"))
        assertEquals(1, RankingBrowserShadow.starts)
    }

    @Test fun rejectedStartImmediatelyFallsBackAndCleansTheSlot() = runBlocking<Unit> {
        warm()
        RankingBrowserShadow.startAccepted = false
        val result = async(Dispatchers.Default) { transport.fetchRankingPage(BOARD) }
        waitUntil { result.isCompleted }
        assertTrue(result.await().contains("NAVIGATION_RESULT"))
        assertEquals("Start rejection must not enter the 30-second response poll", 0, RankingBrowserShadow.polls)
        assertEquals(1, RankingBrowserShadow.cleanups)
    }

    @Test fun invalidFetchResultsKeepTheExistingNavigationFallback() = runBlocking<Unit> {
        warm()
        val cases = listOf(
            Reply(403, BOARD, ""),
            Reply(200, BOARD, "<html>Just a moment... /cdn-cgi/challenge-platform/</html>"),
            Reply(200, BOARD, "<form id='fboardlist'></form>"),
            Reply(200, "$ORIGIN/bbs/board.php?bo_table=javfc2&page=99", RankingBrowserShadow.FETCH_HTML),
            Reply(200, "https://foreign.example.test/javfc2/42", RankingBrowserShadow.FETCH_HTML),
            Reply(0, "", ""),
            Reply(200, BOARD, "x".repeat(2_000_001)),
        )
        for (reply in cases) {
            val before = RankingBrowserShadow.documents.size
            RankingBrowserShadow.reply = reply
            val result = async(Dispatchers.Default) { transport.fetchRankingPage(BOARD) }
            waitUntil { result.isCompleted }
            assertTrue("Expected navigation fallback for status=${reply.status}", result.await().contains("NAVIGATION_RESULT"))
            assertEquals(before + 1, RankingBrowserShadow.documents.size)
            assertEquals(0, RankingBrowserShadow.activeSlots)
        }
        assertEquals(cases.size, RankingBrowserShadow.cleanups)
    }

    @Test fun currentChallengeDocumentDoesNotStartHtmlRequest() = runBlocking<Unit> {
        warm()
        RankingBrowserShadow.challengeSnapshot = true
        val result = async(Dispatchers.Default) { transport.fetchRankingPage(BOARD) }
        waitUntil { result.isCompleted }
        assertTrue(result.await().contains("NAVIGATION_RESULT"))
        assertEquals(0, RankingBrowserShadow.starts)
    }

    @Test fun sameUrlFallbackWaitsForANewDocumentInsteadOfReusingStaleCompleteHtml() = runBlocking<Unit> {
        warm()
        val initial = async(Dispatchers.Default) { transport.fetch(BOARD) }
        waitUntil { initial.isCompleted }
        initial.await()
        val before = RankingBrowserShadow.documents.size
        RankingBrowserShadow.reply = Reply(403, BOARD, "")
        RankingBrowserShadow.holdNewDocument = true
        val result = async(Dispatchers.Default) { transport.fetchRankingPage(BOARD) }
        waitUntil { result.isCompleted || RankingBrowserShadow.staleDocumentSnapshots >= 2 }
        assertTrue("The same URL's previous complete document must not satisfy fallback", result.isActive)
        assertEquals("Old HTML must not be read before the new document arrives", before, RankingBrowserShadow.documents.size)
        RankingBrowserShadow.holdNewDocument = false
        waitUntil { result.isCompleted }
        assertTrue(result.await().contains("NAVIGATION_RESULT"))
        assertEquals(before + 1, RankingBrowserShadow.documents.size)
    }

    @Test fun missingOrZeroPreviousIdentityCannotAcceptTheOldCompleteDocument() = runBlocking<Unit> {
        for (mode in listOf("missing", "zero")) {
            warm()
            val initial = async(Dispatchers.Default) { transport.fetch(BOARD) }
            waitUntil { initial.isCompleted }
            initial.await()
            val before = RankingBrowserShadow.documents.size
            RankingBrowserShadow.reply = Reply(403, BOARD, "")
            RankingBrowserShadow.invalidPreviousIdentityMode = mode
            RankingBrowserShadow.staleDocumentSnapshots = 0
            RankingBrowserShadow.holdNewDocument = true
            val result = async(Dispatchers.Default) { transport.fetchRankingPage(BOARD) }
            try {
                waitUntil { result.isCompleted || RankingBrowserShadow.staleDocumentSnapshots >= 2 }
                assertTrue("An invalid prior identity must not prove a new document: $mode", result.isActive)
                assertEquals(before, RankingBrowserShadow.documents.size)
            } finally {
                result.cancel()
                waitUntil { result.isCompleted }
                RankingBrowserShadow.holdNewDocument = false
                RankingBrowserShadow.invalidPreviousIdentityMode = ""
                RankingBrowserShadow.pendingInvalidSnapshot = false
                RankingBrowserShadow.reply = Reply(200, BOARD, RankingBrowserShadow.FETCH_HTML)
            }
            assertTrue(result.isCancelled)
        }
    }

    @Test fun cancellationAbortsSlotDestroysSessionAndReleasesMutex() = runBlocking<Unit> {
        warm()
        RankingBrowserShadow.holdResult = true
        val cancelled = async(Dispatchers.Default) { transport.fetchRankingPage(BOARD) }
        waitUntil { RankingBrowserShadow.polls > 0 }
        cancelled.cancel()
        waitUntil { cancelled.isCompleted }
        assertTrue(cancelled.isCancelled)
        assertEquals(1, RankingBrowserShadow.cleanups)
        assertEquals(0, RankingBrowserShadow.activeSlots)
        assertTrue(Shadows.shadowOf(RankingBrowserShadow.views.first()).wasDestroyCalled())

        RankingBrowserShadow.holdResult = false
        val recovered = async(Dispatchers.Default) { transport.fetchRankingPage(BOARD) }
        waitUntil { recovered.isCompleted }
        assertTrue(recovered.await().contains("NAVIGATION_RESULT"))
        val fresh = async(Dispatchers.Default) { transport.fetchRankingPage(BOARD) }
        waitUntil { fresh.isCompleted }
        assertTrue(fresh.await().contains("FETCH_RESULT"))
        assertEquals(0, RankingBrowserShadow.activeSlots)
    }

    @Test fun unconfiguredOriginIsRejectedBeforeStartingJavascriptOrNavigation() = runBlocking<Unit> {
        warm()
        val before = RankingBrowserShadow.documents.size
        for (url in listOf(
            "https://foreign.example.test/bbs/board.php?bo_table=javfc2",
            "http://fixture.invalid/bbs/board.php?bo_table=javfc2",
            "https://user@fixture.invalid/bbs/board.php?bo_table=javfc2",
            "https://fixture.invalid:8443/bbs/board.php?bo_table=javfc2",
        )) {
            val result = async(Dispatchers.Default) { runCatching { transport.fetchRankingPage(url) } }
            waitUntil { result.isCompleted }
            assertTrue(url, result.await().isFailure)
        }
        assertEquals(0, RankingBrowserShadow.starts)
        assertEquals(before, RankingBrowserShadow.documents.size)
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.warm() {
        val result = async(Dispatchers.Default) { transport.fetch("$ORIGIN/javfc2/1") }
        waitUntil { result.isCompleted }
        assertTrue(result.await().contains("NAVIGATION_RESULT"))
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "Timed out at neutral ranking browser boundary" }
            Thread.yield()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    companion object {
        const val ORIGIN = RankingHtmlPolicyTest.ORIGIN
        const val BOARD = RankingHtmlPolicyTest.BOARD
    }
}

data class Reply(val status: Int, val url: String, val body: String)

@Implements(WebView::class)
class RankingBrowserShadow : ShadowWebView() {
    @RealObject private lateinit var browser: WebView
    private var documentTimeOrigin = 1.0
    private var previousDocumentTimeOrigin = 1.0
    private var navigationHeld = false

    @Implementation override fun loadUrl(url: String) {
        beginNavigation()
        super.loadUrl(url)
    }

    @Implementation override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        beginNavigation()
        super.loadUrl(url, additionalHttpHeaders)
    }

    private fun beginNavigation() {
        previousDocumentTimeOrigin = documentTimeOrigin
        documentTimeOrigin += 1.0
        navigationHeld = holdNewDocument
    }

    @Implementation override fun setWebViewClient(client: WebViewClient) {
        super.setWebViewClient(client)
        views += browser
    }

    @Implementation override fun evaluateJavascript(script: String, callback: ValueCallback<String>) {
        val response: String = when {
            script.contains("const target = new URL(") -> {
                starts += 1
                if (startAccepted) activeSlots += 1
                return callback.onReceiveValue(startAccepted.toString())
            }
            script.contains("delete window[key]") -> {
                cleanups += 1
                activeSlots = 0
                pendingInvalidSnapshot = invalidPreviousIdentityMode.isNotEmpty()
                return callback.onReceiveValue("null")
            }
            script.startsWith("JSON.stringify(window[") -> {
                polls += 1
                if (holdResult) "null" else JSONObject()
                    .put("status", reply.status).put("url", reply.url).put("body", reply.body).toString()
            }
            script.contains("document.documentElement.outerHTML") -> {
                documents += lastLoadedUrl
                NAV_HTML
            }
            else -> {
                val challenge = challengeSnapshot
                challengeSnapshot = false
                val stale = holdNewDocument && navigationHeld
                if (stale) staleDocumentSnapshots += 1
                val snapshot = JSONObject().put("url", lastLoadedUrl).put("ready", "complete")
                    .put("title", if (challenge) "Just a moment..." else "Neutral fixture")
                    .put("text", if (challenge) "Performing security verification" else "")
                    .put("timeOrigin", if (stale) previousDocumentTimeOrigin else documentTimeOrigin).put("domContentLoaded", true).put("responseStatus", 200)
                if (pendingInvalidSnapshot) {
                    pendingInvalidSnapshot = false
                    if (invalidPreviousIdentityMode == "missing") snapshot.remove("timeOrigin")
                    else snapshot.put("timeOrigin", 0.0)
                }
                snapshot.toString()
            }
        }
        callback.onReceiveValue(JSONObject.quote(response))
    }

    companion object {
        const val FETCH_HTML = RankingHtmlPolicyTest.BOARD_HTML + "<p>FETCH_RESULT</p>"
        const val NAV_HTML = RankingHtmlPolicyTest.BOARD_HTML + "<p>NAVIGATION_RESULT</p>"
        val views = CopyOnWriteArrayList<WebView>()
        val documents = CopyOnWriteArrayList<String>()
        @Volatile var starts = 0
        @Volatile var polls = 0
        @Volatile var cleanups = 0
        @Volatile var activeSlots = 0
        @Volatile var startAccepted = true
        @Volatile var holdResult = false
        @Volatile var challengeSnapshot = false
        @Volatile var holdNewDocument = false
        @Volatile var staleDocumentSnapshots = 0
        @Volatile var invalidPreviousIdentityMode = ""
        @Volatile var pendingInvalidSnapshot = false
        @Volatile var reply = Reply(200, RankingHtmlPolicyTest.BOARD, FETCH_HTML)

        fun reset() {
            views.clear(); documents.clear()
            starts = 0; polls = 0; cleanups = 0; activeSlots = 0
            startAccepted = true; holdResult = false; challengeSnapshot = false
            holdNewDocument = false; staleDocumentSnapshots = 0
            invalidPreviousIdentityMode = ""; pendingInvalidSnapshot = false
            reply = Reply(200, RankingHtmlPolicyTest.BOARD, FETCH_HTML)
        }
    }
}
