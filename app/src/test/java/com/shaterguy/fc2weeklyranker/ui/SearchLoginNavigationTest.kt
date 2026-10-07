package com.shaterguy.fc2weeklyranker.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.shaterguy.fc2weeklyranker.AppGraph
import com.shaterguy.fc2weeklyranker.MainActivity
import com.shaterguy.fc2weeklyranker.domain.ContentMode
import com.shaterguy.fc2weeklyranker.network.ApplicationPageTransport
import com.shaterguy.fc2weeklyranker.network.AvseeClient
import com.shaterguy.fc2weeklyranker.network.PageFailureReason
import com.shaterguy.fc2weeklyranker.network.PageFailureStage
import com.shaterguy.fc2weeklyranker.network.PageLoadException
import com.shaterguy.fc2weeklyranker.network.safeApplicationFailureMessage
import com.shaterguy.fc2weeklyranker.repo.AppRepository
import com.shaterguy.fc2weeklyranker.search.SearchRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    private val searchCalls = AtomicInteger()

    @Before fun setUpNeutralDependencies() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppGraph.initialize(app)
        val http = OkHttpClient.Builder().addInterceptor {
            throw AssertionError("UI fixture must never perform a real HTTP request")
        }.build()
        val source = AvseeClient(http, applicationPageTransport = object : ApplicationPageTransport {
            override suspend fun fetch(url: String, referer: String?): String {
                if (URI(url).path == "/bbs/search.php") {
                    searchCalls.incrementAndGet()
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
        AppGraph.settings.setBaseUrl("https://02.avsee.is", ContentMode.FC2)
    }

    @After fun closeActivityAndRunner() {
        AppGraph.searchScheduler.cancelActive()
        activity?.pause()?.stop()?.destroy()
    }

    @Test fun searchNavigationPropagatesProtectedFailureToActualDialog() {
        launchSearchRoute()
        compose.onNode(hasSetTextAction()).performTextInput("neutral-query")
        compose.onNode(hasText("검색") and hasAnySibling(hasSetTextAction())).performClick()
        waitForLoginDialog()
        assertEquals(1, searchCalls.get())
        assertLoginWebView()
        compose.onNodeWithText("닫기").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("사이트 로그인").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("사이트 로그인").assertIsDisplayed()
    }

    @Test fun storedProtectedDiagnosticOpensDialogThroughActualNavigation() = runBlocking {
        val dao = AppGraph.searchDatabase.searchDao()
        val request = SearchRequest("ui-stored-403", "neutral-query", "https://02.avsee.is")
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

    private fun launchSearchRoute() {
        activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.onNodeWithText("검색").performClick()
        compose.onNodeWithText("FC2 검색").assertIsDisplayed()
    }

    private fun waitForLoginDialog() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("사이트 로그인").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("로그인 완료").assertIsDisplayed()
    }

    private fun assertLoginWebView() {
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
        val webView = findWebView(dialog.window!!.decorView)
        assertNotNull(webView)
        assertEquals("https://02.avsee.is/bbs/login.php", Shadows.shadowOf(webView!!).lastLoadedUrl)
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private fun replaceGraphField(name: String, value: Any) {
        AppGraph::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }
}
