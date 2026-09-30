package com.shaterguy.fc2weeklyranker.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI

@RunWith(AndroidJUnit4::class)
class WebViewApplicationPageTransportInstrumentedTest {
    private fun fixtureBaseUrl(): String {
        val base = requireNotNull(InstrumentationRegistry.getArguments().getString("webViewFixtureBaseUrl")) {
            "A repository WebView fixture URL is required"
        }.trimEnd('/')
        val uri = URI(base)
        require(uri.scheme == "https" && uri.host == "raw.githubusercontent.com") {
            "Only the repository HTTPS fixture host is supported"
        }
        return base
    }

    @Test
    fun generalSearchWaitsForRequestedWebViewDocument() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transport = WebViewApplicationPageTransport(context, AvseeClient.USER_AGENT)
        val html = transport.fetch("${fixtureBaseUrl()}/bbs/search.php")
        assertTrue(html.contains("SELF_RUN_GENERAL_SEARCH_WEBVIEW_FIXTURE"))
    }

    @Test
    fun tagSearchWaitsForRequestedWebViewDocument() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transport = WebViewApplicationPageTransport(context, AvseeClient.USER_AGENT)
        val html = transport.fetch("${fixtureBaseUrl()}/bbs/tag.php")
        assertTrue(html.contains("SELF_RUN_TAG_SEARCH_WEBVIEW_FIXTURE"))
    }
}
