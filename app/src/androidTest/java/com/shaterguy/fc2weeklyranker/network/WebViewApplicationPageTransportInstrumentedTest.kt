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
        // raw.githubusercontent.com intentionally canonicalizes away query strings in WebView.
        // The real query-matching contract is covered by JVM tests; this device test proves that
        // browser navigation leaves the bootstrap document and reaches the requested search path.
        val target = fixtureBaseUrl() + "/bbs/search.php"
        val html = transport.fetch(target)
        assertTrue(html.contains("SELF_RUN_GENERAL_SEARCH_WEBVIEW_FIXTURE"))
        assertTrue(html.contains("search-page-marker"))
        assertFalse(html.contains("Just a moment...", ignoreCase = true))
    }

    @Test
    fun tagSearchWaitsForRequestedWebViewDocument() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transport = WebViewApplicationPageTransport(context, AvseeClient.USER_AGENT)
        // Keep the device fixture path-only for the same CDN canonicalization reason above.
        val target = fixtureBaseUrl() + "/bbs/tag.php"
        val html = transport.fetch(target)
        assertTrue(html.contains("SELF_RUN_TAG_SEARCH_WEBVIEW_FIXTURE"))
        assertTrue(html.contains("tag-page-marker"))
        assertFalse(html.contains("Just a moment...", ignoreCase = true))
    }
}
