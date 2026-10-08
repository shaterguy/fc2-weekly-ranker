package com.shaterguy.fc2weeklyranker.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.shaterguy.fc2weeklyranker.AppGraph
import com.shaterguy.fc2weeklyranker.MainActivity
import com.shaterguy.fc2weeklyranker.data.AppDatabase
import com.shaterguy.fc2weeklyranker.domain.ContentMode
import com.shaterguy.fc2weeklyranker.network.ApplicationPageTransport
import com.shaterguy.fc2weeklyranker.network.AvseeClient
import com.shaterguy.fc2weeklyranker.network.PageFailureReason
import com.shaterguy.fc2weeklyranker.network.PageFailureStage
import com.shaterguy.fc2weeklyranker.network.PageLoadException
import com.shaterguy.fc2weeklyranker.network.safeApplicationFailureMessage
import com.shaterguy.fc2weeklyranker.repo.AppRepository
import com.shaterguy.fc2weeklyranker.search.SearchRequest
import com.shaterguy.fc2weeklyranker.search.SearchDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue

/** Production Activity -> navigation -> real Room/ViewModel -> Compose Dialog.
 * Robolectric shadows the login WebView; no site request or account login occurs.
 * Fixture dependencies live only in this test, never in production classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SearchLoginNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<MainActivity>? = null
    private var database: AppDatabase? = null
    private var searchDatabase: SearchDatabase? = null
    private val searchCalls = AtomicInteger()
    private val searchUrls = ConcurrentLinkedQueue<String>()

    @Before fun setUpNeutralDependencies() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppGraph.initialize(app)
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        searchDatabase = Room.inMemoryDatabaseBuilder(app, SearchDatabase::class.java).build()
        replaceGraphField("database", database!!)
        replaceGraphField("searchDatabase", searchDatabase!!)
        val http = OkHttpClient.Builder().addInterceptor {
            throw AssertionError("UI fixture must never perform a real HTTP request")
        }.build()
        val source = AvseeClient(http, applicationPageTransport = object : ApplicationPageTransport {
            override suspend fun fetch(url: String, referer: String?): String {
                if (URI(url).path == "/bbs/search.php") {
                    searchCalls.incrementAndGet()
                    searchUrls.add(url)
                    throw PageLoadException(PageFailureStage.DOCUMENT, PageFailureReason.HTTP, 403)
                }
                return "<!doctype html><html><head><title>Neutral fixture</title></head><body></body></html>"
            }
        })
        // Existing AppGraph setters are private. Replace only this JVM test's dependencies.
        replaceGraphField("httpClient", http)
        replaceGraphField("sourceClient", source)
        replaceGraphField("repository", AppRepository(app, AppGraph.database, AppGraph.settings, source))
        AppGraph.settings.setSelectedContentMode(ContentMode.FC2)
        AppGraph.settings.setBaseUrl(CONFIGURED_ORIGIN, ContentMode.FC2)
        AppGraph.settings.setBaseUrl(CONFIGURED_ORIGIN, ContentMode.JAV)
    }

    @After fun closeActivityAndRunner() {
        AppGraph.searchScheduler.cancelActive()
        activity?.pause()?.stop()?.destroy()
        searchDatabase?.close()
        database?.close()
    }

    @Test fun settingsShowsOneSaveActionForTheCommonAddress() {
        launchSearchRoute()
        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("저장").assertIsDisplayed()
        compose.onNodeWithText("연결 테스트").assertDoesNotExist()
        compose.onNodeWithText("FC2와 JAV에 공통으로 적용됩니다. 저장 시 연결을 확인합니다.").assertIsDisplayed()
    }

    @Test fun searchNavigationPropagatesProtectedFailureToActualDialog() {
        launchSearchRoute()
        compose.onNode(hasSetTextAction()).performTextInput("neutral-query")
        compose.onNode(hasText("검색") and hasAnySibling(hasSetTextAction())).performClick()
        waitForLoginDialog()
        assertEquals("fresh search must call the fixture transport exactly once", 1, searchCalls.get())
        assertLoginWebView()
        compose.onNodeWithText("닫기").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("사이트 로그인").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("사이트 로그인").assertIsDisplayed()
    }

    @Test fun storedProtectedDiagnosticOpensDialogThroughActualNavigation() = runBlocking {
        val dao = AppGraph.searchDatabase.searchDao()
        val request = SearchRequest("ui-stored-403", "neutral-query", CONFIGURED_ORIGIN)
        dao.prepareSession(request)
        dao.fail(request.token, safeApplicationFailureMessage(
            PageLoadException(PageFailureStage.DOCUMENT, PageFailureReason.HTTP, 403)), 1L)
        launchSearchRoute()
        waitForLoginDialog()
        assertEquals(0, searchCalls.get())
        assertLoginWebView()
    }

    @Test fun manualLoginControlActuallyOpensDialogBeforeSearch() {
        launchSearchRoute()
        compose.onNodeWithText("사이트 로그인").assertIsDisplayed().performClick()
        waitForLoginDialog()
        assertEquals(0, searchCalls.get())
        assertLoginWebView()
    }

    @Test fun explicitlyConfiguredDifferentDomainUsesItsOwnLoginOrigin() = runBlocking {
        val configured = "https://new-search.example.test"
        AppGraph.settings.setBaseUrl(configured, ContentMode.FC2)
        launchSearchRoute(configured)
        compose.onNodeWithText("사이트 로그인").assertIsDisplayed().performClick()
        waitForLoginDialog()
        assertLoginWebView(configured)
        assertEquals(0, searchCalls.get())
    }

    @Test fun configured02LoginDoesNotAutomaticallyTrustAnotherPreviousHost() = runBlocking {
        val configured = "https://02.avsee.is"
        AppGraph.settings.setBaseUrl(configured, ContentMode.FC2)
        launchSearchRoute(configured)
        compose.onNodeWithText("사이트 로그인").performClick()
        waitForLoginDialog()
        assertLoginWebView(configured)
    }

    @Test fun changingOriginDisposesVisibleLoginBeforeOpeningReplacement() = runBlocking {
        val vm = launchSearchRoute()
        compose.onNode(hasSetTextAction()).performTextInput("neutral-origin-change")
        compose.onNode(hasText("검색") and hasAnySibling(hasSetTextAction())).performClick()
        waitForLoginDialog()
        val oldDialog = ShadowDialog.getLatestDialog()
        val oldWebView = requireNotNull(findWebView(oldDialog.window!!.decorView))
        val oldOrigin = requireNotNull(vm.currentApplicationOrigin())
        val failedRequestKey = vm.searchRequestKey()
        val replacement = "https://replacement.example.test"
        AppGraph.settings.setBaseUrl(replacement, ContentMode.FC2)
        waitForSavedBaseUrl(vm, replacement)

        assertFalse("the previous origin must no longer authorize a login completion", vm.isCurrentApplicationOrigin(oldOrigin))
        assertFalse("the previous site's dialog must be dismissed", oldDialog.isShowing)
        assertTrue("the previous site's WebView must be destroyed", Shadows.shadowOf(oldWebView).wasDestroyCalled())
        assertEquals("about:blank", Shadows.shadowOf(oldWebView).lastLoadedUrl)
        assertEquals("an old-origin 403 must not open a new-origin login", 1,
            compose.onAllNodesWithText("사이트 로그인").fetchSemanticsNodes().size)
        assertEquals("an origin change must retain the failed request", failedRequestKey, vm.searchRequestKey())
        assertEquals("an origin change must not submit another search", 1, searchCalls.get())

        compose.onNodeWithText("사이트 로그인").performClick()
        waitForLoginDialog()
        val replacementDialog = ShadowDialog.getLatestDialog()
        assertNotSame(oldDialog, replacementDialog)
        assertNotSame(oldWebView, findWebView(replacementDialog.window!!.decorView))
        assertLoginWebView(replacement)
    }

    @Test fun storedProtectedFailureFromAnotherOriginDoesNotPromptCurrentSite() = runBlocking {
        val dao = AppGraph.searchDatabase.searchDao()
        val request = SearchRequest("ui-stale-origin-403", "neutral-query", "https://previous.example.test")
        dao.prepareSession(request)
        dao.fail(request.token, safeApplicationFailureMessage(
            PageLoadException(PageFailureStage.DOCUMENT, PageFailureReason.HTTP, 403)), 1L)
        val vm = launchSearchRoute()
        compose.waitUntil(10_000) { vm.searchMessage.value?.contains("[DOCUMENT/HTTP_403]") == true }
        compose.waitForIdle()
        assertEquals("only the manual login control should be shown", 1,
            compose.onAllNodesWithText("사이트 로그인").fetchSemanticsNodes().size)
        assertEquals(0, searchCalls.get())
    }

    @Test fun changingModeDisposesLoginAndRejectsStaleCompletion() {
        val vm = launchSearchRoute()
        compose.onNode(hasSetTextAction()).performTextInput("neutral-mode-change")
        compose.onNodeWithText("사이트 로그인").performClick()
        waitForLoginDialog()
        val oldDialog = ShadowDialog.getLatestDialog()
        val oldWebView = requireNotNull(findWebView(oldDialog.window!!.decorView))
        val oldDone = requireNotNull(
            compose.onNodeWithText("로그인 완료").fetchSemanticsNode().config[SemanticsActions.OnClick].action,
        )
        compose.runOnIdle {
            vm.selectContentMode(ContentMode.JAV)
            oldDone()
            assertNull("an old-mode completion must not schedule a search", vm.searchRequestKey())
        }
        waitForSavedBaseUrl(vm, CONFIGURED_ORIGIN)
        assertFalse(oldDialog.isShowing)
        assertTrue(Shadows.shadowOf(oldWebView).wasDestroyCalled())
        assertEquals(0, searchCalls.get())
        compose.onNodeWithText("사이트 로그인").performClick()
        waitForLoginDialog()
        assertLoginWebView(CONFIGURED_ORIGIN)
    }

    @Test fun immediateModeSwitchNeverSubmitsThePreviousSavedOrigin() {
        val vm = launchSearchRoute()
        for ((mode, origin) in listOf(ContentMode.JAV to CONFIGURED_ORIGIN, ContentMode.FC2 to CONFIGURED_ORIGIN)) {
            var submitted = false
            compose.runOnIdle {
                vm.selectContentMode(mode)
                val saved = vm.currentApplicationOrigin()
                if (saved == null) {
                    vm.searchPosts("${mode.sourceKey}-pending-origin")
                    assertNull("mode switch must not schedule before its origin arrives", vm.searchRequestKey())
                } else {
                    assertEquals(mode, saved.mode)
                    assertEquals(origin, saved.baseUrl)
                    vm.searchPosts("${mode.sourceKey}-matching-origin")
                    submitted = true
                }
            }
            waitForSavedBaseUrl(vm, origin)
            compose.onNodeWithText("${mode.sourceKey} 검색").assertIsDisplayed()
            if (!submitted) compose.runOnIdle { vm.searchPosts("${mode.sourceKey}-matching-origin") }
            compose.waitUntil(10_000) {
                searchUrls.any { Uri.parse(it).getQueryParameter("stx") == "${mode.sourceKey}-matching-origin" }
            }
            waitForLoginDialog()
            assertLoginWebView(origin)
            compose.onNodeWithText("닫기").performClick()
        }
        assertEquals(2, searchCalls.get())
        for (url in searchUrls) {
            val mode = if (Uri.parse(url).getQueryParameter("onetable") == ContentMode.JAV.boardTable) {
                ContentMode.JAV
            } else ContentMode.FC2
            assertEquals("source request must use the common saved origin", URI(CONFIGURED_ORIGIN).host, URI(url).host)
            assertEquals(mode.boardTable, Uri.parse(url).getQueryParameter("onetable"))
        }
    }

    private fun launchSearchRoute(expectedOrigin: String = CONFIGURED_ORIGIN): MainViewModel {
        activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val vm = ViewModelProvider(activity!!.get()).get(MainViewModel::class.java)
        waitForSavedBaseUrl(vm, expectedOrigin)
        compose.onNodeWithText("검색").performClick()
        compose.onNodeWithText("FC2 검색").assertIsDisplayed()
        return vm
    }

    private fun waitForSavedBaseUrl(vm: MainViewModel, expectedOrigin: String) {
        compose.waitUntil(10_000) {
            // PAUSED leaves DataStore -> Main ViewModel continuations on Android's queue.
            // Advancing Compose's clock alone does not execute those posted tasks.
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.baseUrl.value == expectedOrigin
        }
        compose.waitForIdle()
        assertEquals("the saved snapshot must also match the displayed address", expectedOrigin,
            vm.currentApplicationOrigin()?.baseUrl)
    }

    private fun waitForLoginDialog() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("사이트 로그인").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("로그인 완료").assertIsDisplayed()
    }

    private fun assertLoginWebView(expectedOrigin: String = CONFIGURED_ORIGIN) {
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        val webView = findWebView(dialog.window!!.decorView)
        assertNotNull(webView)
        assertEquals("$expectedOrigin/bbs/login.php", Shadows.shadowOf(webView!!).lastLoadedUrl)
        assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(webView))
        val client = webView.webViewClient
        assertFalse(client.shouldOverrideUrlLoading(webView, request("$expectedOrigin/bbs/search.php")))
        assertNull("a same-origin POST must reach the site's login handler",
            client.shouldInterceptRequest(webView, request("$expectedOrigin/bbs/login_check.php", "POST")))
        assertEquals("a foreign main-frame POST must be rejected", 403,
            client.shouldInterceptRequest(webView, request("https://foreign.example.test/bbs/login_check.php", "POST"))?.statusCode)
        for (url in listOf(
            "https://01.avsee.is/bbs/login.php",
            "https://foreign.example.test/bbs/login.php",
            expectedOrigin.replace("https://", "http://") + "/bbs/login.php",
            expectedOrigin.replace("https://", "https://user@") + "/bbs/login.php",
            "$expectedOrigin.evil.test/bbs/login.php",
        )) {
            assertTrue("unconfigured origin must not be trusted: $url", client.shouldOverrideUrlLoading(webView, request(url)))
        }
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private fun replaceGraphField(name: String, value: Any) {
        AppGraph::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }

    private fun request(url: String, method: String = "GET"): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = true
        override fun hasGesture() = false
        override fun getMethod() = method
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    companion object {
        private const val CONFIGURED_ORIGIN = "https://03.avsee.is"
    }
}
