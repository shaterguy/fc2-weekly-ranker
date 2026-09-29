package com.shaterguy.fc2weeklyranker.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewApplicationPageTransportInstrumentedTest {
    @Test
    fun generalSearchWaitsForRequestedWebViewDocument() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transport = WebViewApplicationPageTransport(context, AvseeClient.USER_AGENT)
        val html = transport.fetch("https://example.com/bbs/search.php")
        assertTrue(html.isNotBlank())
    }

    @Test
    fun tagSearchWaitsForRequestedWebViewDocument() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transport = WebViewApplicationPageTransport(context, AvseeClient.USER_AGENT)
        val html = transport.fetch("https://example.com/bbs/tag.php")
        assertTrue(html.isNotBlank())
    }
}
