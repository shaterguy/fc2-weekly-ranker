package com.shaterguy.fc2weeklyranker.network

import android.app.Application
import android.os.Looper
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceResponse
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shaterguy.fc2weeklyranker.data.AppDatabase
import com.shaterguy.fc2weeklyranker.data.SettingsStore
import com.shaterguy.fc2weeklyranker.domain.ContentMode
import com.shaterguy.fc2weeklyranker.repo.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/** Production WebView transport/session/navigation; only browser document evaluation is synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [CandidateBrowserShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CandidateOriginConnectionTest {
    private lateinit var settings: SettingsStore
    private lateinit var db: AppDatabase
    private lateinit var transport: WebViewApplicationPageTransport
    private lateinit var repo: AppRepository

    @Before fun setUp() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        settings = SettingsStore(app)
        settings.setBaseUrl(OLD, ContentMode.FC2)
        settings.setBaseUrl(OLD, ContentMode.JAV)
        CandidateBrowserShadow.views.clear()
        CandidateBrowserShadow.documents.clear()
        CandidateBrowserShadow.holdHtml = false
        CandidateBrowserShadow.heldCallback = null
        CandidateBrowserShadow.invalidBoard = false
        CandidateBrowserShadow.detailOrigin = CANDIDATE
        CandidateBrowserShadow.afterClient = null
        CandidateBrowserShadow.beforeHtml = {
            assertEquals("Candidate validation must not pre-save its origin", OLD, runBlocking { settings.baseUrl.first() })
        }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        transport = WebViewApplicationPageTransport(
            app, configuredOrigins = { setOf(settings.baseUrl.first()) },
            fixtureResponse = { WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("neutral".toByteArray())) },
        )
        val http = OkHttpClient.Builder().addInterceptor { throw AssertionError("Real HTTP is forbidden") }.build()
        repo = AppRepository(app, db, settings, AvseeClient(http, applicationPageTransport = transport))
    }

    @After fun tearDown() = runBlocking<Unit> {
        val closing = async(Dispatchers.Default) { transport.close() }
        waitUntil { closing.isCompleted }
        closing.await()
        db.close()
        CandidateBrowserShadow.beforeHtml = null
        CandidateBrowserShadow.afterClient = null
    }

    @Test fun repositoryValidatesTheNewOriginBeforeCommittingEitherPreference() = runBlocking<Unit> {
        val saving = async(Dispatchers.Default) { repo.setBaseUrl(CANDIDATE) }
        waitUntil { saving.isCompleted }
        val result = saving.await()
        assertTrue("Actual transport rejected the candidate: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(2, CandidateBrowserShadow.documents.size)
        assertTrue(CandidateBrowserShadow.documents.all { it.startsWith(CANDIDATE) })
        for (mode in ContentMode.entries) assertEquals(CANDIDATE, settings.baseUrl(mode).first())
        assertTrue("The validation browser must be destroyed", CandidateBrowserShadow.views.all { Shadows.shadowOf(it).wasDestroyCalled() })
    }

    @Test fun javValidationKeepsItsBoardIdentityAndCommitsTheCommonAddress() = runBlocking<Unit> {
        val saving = async(Dispatchers.Default) { repo.setBaseUrl(CANDIDATE, ContentMode.JAV) }
        waitUntil { saving.isCompleted }
        assertTrue(saving.await().isSuccess)
        assertEquals(2, CandidateBrowserShadow.documents.size)
        assertTrue(CandidateBrowserShadow.documents.all { it.contains("bo_table=javc&") })
        for (mode in ContentMode.entries) assertEquals(CANDIDATE, settings.baseUrl(mode).first())
    }

    @Test fun failedValidationPreservesBothPreferencesAndDoesNotAuthorizeNormalFetch() = runBlocking<Unit> {
        CandidateBrowserShadow.invalidBoard = true
        val saving = async(Dispatchers.Default) { repo.setBaseUrl(CANDIDATE) }
        waitUntil { saving.isCompleted }
        assertTrue(saving.await().isFailure)
        assertOldAddress()
        assertValidationBrowsersDestroyed()
        assertNormalCandidateFetchRejected()
    }

    @Test fun foreignDetailLinkFailsBeforeNavigationAndPreservesTheOldAddress() = runBlocking<Unit> {
        CandidateBrowserShadow.detailOrigin = "https://foreign.example.test"
        val saving = async(Dispatchers.Default) { repo.setBaseUrl(CANDIDATE) }
        waitUntil { saving.isCompleted }
        assertTrue(saving.await().isFailure)
        assertEquals(1, CandidateBrowserShadow.documents.size)
        assertTrue(CandidateBrowserShadow.documents.none { it.contains("foreign.example.test") })
        assertOldAddress()
        assertValidationBrowsersDestroyed()
    }

    @Test fun cancellationRevokesCandidateAuthorityAndIgnoresLateBrowserCompletion() = runBlocking<Unit> {
        CandidateBrowserShadow.holdHtml = true
        val saving = async(Dispatchers.Default) { repo.setBaseUrl(CANDIDATE) }
        waitUntil { CandidateBrowserShadow.heldCallback != null }
        assertOldAddress()
        // The application's ordinary transport never acquires the in-flight candidate.
        assertNormalCandidateFetchRejected()
        saving.cancel()
        waitUntil { saving.isCompleted }
        assertTrue(saving.isCancelled)
        assertValidationBrowsersDestroyed()
        CandidateBrowserShadow.heldCallback!!.onReceiveValue(JSONObject.quote("late neutral document"))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertOldAddress()
        assertNormalCandidateFetchRejected()
    }

    @Test fun cancellationDuringBrowserCreationStillDestroysTheCandidateBrowser() = runBlocking<Unit> {
        val saving = async(Dispatchers.Default, start = CoroutineStart.LAZY) { repo.setBaseUrl(CANDIDATE) }
        // Cancel after a real browser has been configured, before the Main dispatcher
        // can return it to the suspended validation coroutine.
        CandidateBrowserShadow.afterClient = { saving.cancel() }
        saving.start()
        waitUntil { saving.isCompleted }
        assertTrue(saving.isCancelled)
        assertTrue("No document should load after creation-time cancellation", CandidateBrowserShadow.documents.isEmpty())
        assertValidationBrowsersDestroyed()
        assertOldAddress()
        assertNormalCandidateFetchRejected()
    }

    @Test fun scopedTransportExpiresAfterSuccessAndFailureAndRejectsForeignRequests() = runBlocking<Unit> {
        for (failValidation in listOf(false, true)) {
            var escaped: ApplicationPageTransport? = null
            val validation = async(Dispatchers.Default) {
                runCatching {
                    transport.withConnectionValidationOrigin(CANDIDATE) { scoped ->
                        escaped = scoped
                        for (url in listOf(
                            "$OLD/bbs/board.php", "https://foreign.example.test/bbs/board.php",
                            "http://fixture.invalid/bbs/board.php", "https://user@fixture.invalid/bbs/board.php",
                            "https://fixture.invalid:8443/bbs/board.php",
                        )) assertTrue(runCatching { scoped.fetch(url) }.isFailure)
                        assertTrue(runCatching { scoped.fetch("$CANDIDATE/bbs/board.php", "$OLD/bbs/") }.isFailure)
                        scoped.fetch("$CANDIDATE/bbs/board.php")
                        if (failValidation) error("neutral validation failure")
                    }
                }
            }
            waitUntil { validation.isCompleted }
            assertEquals(failValidation, validation.await().isFailure)
            val late = async(Dispatchers.Default) { runCatching { escaped!!.fetch("$CANDIDATE/bbs/board.php") } }
            waitUntil { late.isCompleted }
            assertTrue("A validation transport must not outlive its callback", late.await().isFailure)
            assertValidationBrowsersDestroyed()
            assertNormalCandidateFetchRejected()
        }
        assertOldAddress()
    }

    @Test fun candidateBrowserKeepsExistingRedirectAndPostBoundaries() = runBlocking<Unit> {
        val validation = async(Dispatchers.Default) {
            transport.withConnectionValidationOrigin(CANDIDATE) { scoped -> scoped.fetch("$CANDIDATE/bbs/board.php") }
        }
        waitUntil { validation.isCompleted }
        validation.await()
        val browser = CandidateBrowserShadow.views.single()
        val client = browser.webViewClient
        assertFalse(client.shouldOverrideUrlLoading(browser, request("$CANDIDATE/bbs/board.php")))
        for (url in listOf(
            "$OLD/bbs/board.php", "https://foreign.example.test/bbs/board.php",
            "http://fixture.invalid/bbs/board.php", "https://user@fixture.invalid/bbs/board.php",
            "https://fixture.invalid:8443/bbs/board.php",
        )) {
            assertTrue(client.shouldOverrideUrlLoading(browser, request(url)))
            assertEquals(403, client.shouldInterceptRequest(browser, request(url, "POST"))?.statusCode)
        }
        assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(browser))
        assertValidationBrowsersDestroyed()
    }

    private suspend fun assertOldAddress() {
        for (mode in ContentMode.entries) assertEquals(OLD, settings.baseUrl(mode).first())
    }

    private fun assertValidationBrowsersDestroyed() {
        assertTrue(CandidateBrowserShadow.views.isNotEmpty())
        assertTrue(CandidateBrowserShadow.views.all { Shadows.shadowOf(it).wasDestroyCalled() })
    }

    private suspend fun assertNormalCandidateFetchRejected() = kotlinx.coroutines.coroutineScope {
        val before = CandidateBrowserShadow.views.size
        val ordinary = async(Dispatchers.Default) { runCatching { transport.fetch("$CANDIDATE/bbs/board.php") } }
        waitUntil { ordinary.isCompleted }
        assertTrue("Normal application requests must still use the saved origin", ordinary.await().isFailure)
        assertEquals("Normal fetch must not create a candidate browser", before, CandidateBrowserShadow.views.size)
    }

    private fun request(url: String, method: String = "GET") = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = true
        override fun hasGesture() = false
        override fun getMethod() = method
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "Timed out waiting for synthetic WebView state" }
            Thread.yield()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    companion object {
        const val OLD = "https://saved.example.test"
        const val CANDIDATE = "https://fixture.invalid"
    }
}

@Implements(WebView::class)
class CandidateBrowserShadow : ShadowWebView() {
    @RealObject private lateinit var browserView: WebView

    @Implementation override fun setWebViewClient(client: WebViewClient) {
        super.setWebViewClient(client)
        if (browserView !in views) views += browserView
        afterClient?.invoke()
    }

    @Implementation override fun evaluateJavascript(script: String, callback: ValueCallback<String>) {
        if (browserView !in views) views += browserView
        val url = lastLoadedUrl
        val response = if (script.contains("document.documentElement.outerHTML")) {
            beforeHtml?.invoke()
            documents += url
            if (holdHtml) { heldCallback = callback; return }
            if (url.contains("wr_id=")) {
                "<article><meta itemprop='datePublished' content='2026-10-08KST12:00:00'><h1>Neutral fixture</h1></article>"
            } else {
                val board = if (url.contains("bo_table=javc&")) "javc" else "javfc2"
                if (invalidBoard) "<form id='fboardlist'></form>" else
                    "<form id='fboardlist'><div class='list-item'><h2><a href='$detailOrigin/bbs/board.php?bo_table=$board&wr_id=123'>Neutral fixture</a></h2><span class='comments'><b>3</b></span><span>20</span></div></form>"
            }
        } else {
            JSONObject().put("url", url).put("ready", "complete").put("title", "Neutral fixture")
                .put("timeOrigin", 1.0).put("domContentLoaded", true).put("responseStatus", 200).put("text", "").toString()
        }
        callback.onReceiveValue(JSONObject.quote(response))
    }

    companion object {
        val views = mutableListOf<WebView>()
        val documents = mutableListOf<String>()
        var beforeHtml: (() -> Unit)? = null
        var afterClient: (() -> Unit)? = null
        var holdHtml = false
        var heldCallback: ValueCallback<String>? = null
        var invalidBoard = false
        var detailOrigin = CandidateOriginConnectionTest.CANDIDATE
    }
}
