package com.shaterguy.fc2weeklyranker.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewApplicationPageTransportInstrumentedTest {
    private fun fixtureBaseUrl(): String =
        InstrumentationRegistry.getArguments().getString("webViewFixtureBaseUrl")
            ?: error("webViewFixtureBaseUrl instrumentation argument is required")

    @Test
    fun generalSearchWaitsForRequestedWebViewDocument() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transport = WebViewApplicationPageTransport(context, AvseeClient.USER_AGENT)
        val target = fixtureBaseUrl() +
            "/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=selfrun-search&sop=and&page=1"
        val html = transport.fetch(target)
        assertTrue(html.contains("SELF_RUN_GENERAL_SEARCH_WEBVIEW_FIXTURE"))
        assertTrue(html.contains("search-page-marker"))
        assertFalse(html.contains("Just a moment...", ignoreCase = true))
    }

    @Test
    fun tagSearchWaitsForRequestedWebViewDocument() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transport = WebViewApplicationPageTransport(context, AvseeClient.USER_AGENT)
        val target = fixtureBaseUrl() + "/bbs/tag.php?q=selfrun-tag&eq=&page=1"
        val html = transport.fetch(target)
        assertTrue(html.contains("SELF_RUN_TAG_SEARCH_WEBVIEW_FIXTURE"))
        assertTrue(html.contains("tag-page-marker"))
        assertFalse(html.contains("Just a moment...", ignoreCase = true))
    }
}
