package com.shaterguy.fc2weeklyranker.network

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebViewApplicationPageTransportInstrumentedTest {
    @Test
    fun generalSearchWaitsForRequestedWebViewDocument() = verifyFixture(isTag = false, holdImage = false, currentDom = false)

    @Test
    fun tagSearchWaitsForRequestedWebViewDocument() = verifyFixture(isTag = true, holdImage = false, currentDom = false)

    @Test
    fun generalSearchReturnsParsedResultsWhileImageIsPending() = verifyFixture(isTag = false, holdImage = true, currentDom = true)

    @Test
    fun tagSearchReturnsParsedResultsWhileImageIsPending() = verifyFixture(isTag = true, holdImage = true, currentDom = true)

    private fun verifyFixture(isTag: Boolean, holdImage: Boolean, currentDom: Boolean) = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = SyntheticPage(isTag, holdImage, currentDom)
        val webViewPackage = WebView.getCurrentWebViewPackage()
        val provider = "${webViewPackage?.packageName}/${webViewPackage?.versionName}"
        val events = Collections.synchronizedList(mutableListOf<String>())
        val parser = AvseeClient(OkHttpClient())
        val transport = WebViewApplicationPageTransport(
            context,
            AvseeClient.USER_AGENT,
            hasUsableSearchResults = parser::hasUsableApplicationSearchResults,
            fixtureResponse = fixture::respond,
            fixtureDiagnostic = { events.add(it) },
        )
        try {
            coroutineScope {
                val fetching = async(Dispatchers.Default) { transport.fetch(fixture.url) }
                if (holdImage) {
                    val imageStarted = withContext(Dispatchers.IO) { fixture.imageReadStarted.await(5, TimeUnit.SECONDS) }
                    assertTrue("synthetic image response was not requested", imageStarted)
                }
                val html = withTimeout(15_000) { fetching.await() }
                assertTrue("requested document marker is missing", html.contains(fixture.marker))
                assertTrue("the production parser cannot use the returned document", parser.hasUsableApplicationSearchResults(html, fixture.url))
                if (holdImage) {
                    assertEquals("fetch waited until the image response was released", 1L, fixture.releaseImage.count)
                }
                println("WEBVIEW_SYNTHETIC_FIXTURE provider=$provider path=${fixture.path} hold_image=$holdImage marker=true parser=true image_blocked=${fixture.releaseImage.count == 1L}")
            }
        } catch (error: Throwable) {
            val diagnostic = synchronized(events) { events.takeLast(20).joinToString(" | ") }
            throw AssertionError("Synthetic WebView ${fixture.path} holdImage=$holdImage provider=$provider; $diagnostic", error)
        } finally {
            fixture.releaseImage.countDown()
            withContext(NonCancellable) { transport.close() }
        }
    }

    private class SyntheticPage(private val isTag: Boolean, private val holdImage: Boolean, private val currentDom: Boolean) {
        val path = if (isTag) "/bbs/tag.php" else "/bbs/search.php"
        val url = "https://fixture.invalid$path?${if (isTag) "q=synthetic&eq=&onetable=javc&result_sort=newest" else "stx=synthetic&onetable=javfc2"}&page=1"
        val marker = if (isTag) "SELF_RUN_TAG_SEARCH_WEBVIEW_FIXTURE" else "SELF_RUN_GENERAL_SEARCH_WEBVIEW_FIXTURE"
        val imageReadStarted = CountDownLatch(1)
        val releaseImage = CountDownLatch(if (holdImage) 1 else 0)

        fun respond(request: WebResourceRequest): WebResourceResponse {
            if (request.url.scheme != "https" || request.url.host != "fixture.invalid") {
                return response("text/plain", "unexpected fixture host", status = 403)
            }
            if (request.isForMainFrame && request.url.path == path) {
                val board = if (isTag) "javc" else "javfc2"
                val resultMarkup = if (currentDom) {
                    if (isTag) {
                        "<ul class='tag-results'><li class='tag-result'><div class='tag-result-title'><a href='/bbs/board.php?bo_table=$board&amp;wr_id=42'>Synthetic current tag</a></div><div class='tag-result-meta'>댓글 2 조회 10 추천 1</div></li></ul>"
                    } else {
                        "<ul class='search-results'><li class='search-result'><div class='search-result-title'><a href='/bbs/board.php?bo_table=$board&amp;wr_id=42'>Synthetic current result</a></div><div class='search-excerpt'>match</div></li></ul>"
                    }
                } else {
                    val container = if (isTag) "tagbox-media" else "search-media"
                    val metrics = if (isTag) "<div class='media-info'><i class='fa fa-comment'></i> 2 <i class='fa fa-eye'></i> 10</div>" else ""
                    "<div id='at-main'><div class='$container'><div class='media'><h4 class='media-heading'><a href='/bbs/board.php?bo_table=$board&amp;wr_id=42'>Synthetic result</a></h4>$metrics</div></div></div>"
                }
                val image = if (holdImage) "<img width='1' height='1' src='/held-image.png'>" else ""
                return response("text/html", "<!doctype html><html><head><title>Synthetic navigation</title></head><body>$resultMarkup<p>$marker</p>$image</body></html>")
            }
            if (request.url.path == "/held-image.png" && holdImage) {
                val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9l8AAAAASUVORK5CYII=")
                val input = object : InputStream() {
                    private val delegate = ByteArrayInputStream(bytes)
                    private fun awaitRelease() {
                        imageReadStarted.countDown()
                        if (!releaseImage.await(20, TimeUnit.SECONDS)) throw IOException("synthetic image was not released")
                    }
                    override fun read(): Int { awaitRelease(); return delegate.read() }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        awaitRelease()
                        return delegate.read(buffer, offset, length)
                    }
                    override fun close() { releaseImage.countDown(); delegate.close() }
                }
                return WebResourceResponse("image/png", null, 200, "OK", mapOf("Cache-Control" to "no-store"), input)
            }
            return response("text/plain", "unknown fixture path", status = 404)
        }

        private fun response(mime: String, body: String, status: Int = 200) = WebResourceResponse(
            mime, "UTF-8", status, if (status == 200) "OK" else "Fixture missing",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
        )
    }
}
