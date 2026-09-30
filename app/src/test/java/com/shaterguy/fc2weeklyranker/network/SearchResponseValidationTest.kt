package com.shaterguy.fc2weeklyranker.network

import com.shaterguy.fc2weeklyranker.domain.ContentMode
import com.shaterguy.fc2weeklyranker.search.BackgroundSearchClient
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Synthetic documents only. Never fetches a live site. */
class SearchResponseValidationTest {
    private val http = OkHttpClient()
    private val parser = AvseeClient(http)
    private val baseUrl = "https://example.test"
    @Test
    fun `complete empty pages without list wrappers keep legacy compatibility`() {
        val html = "<html><head><title>Synthetic empty page</title></head><body><main>No matches</main></body></html>"
        assertTrue(parser.parseSearchPage(html, parser.buildSearchUrl(baseUrl, "fixture", 1)).posts.isEmpty())
        assertTrue(parser.parseTagPage(html, parser.buildTagSearchUrl(baseUrl, "fixture", 1)).posts.isEmpty())
    }

    @Test
    fun `background search propagates an explicit transport failure`() = runTest {
        val source = AvseeClient(http, applicationPageTransport = failureTransport())
        try {
            BackgroundSearchClient(http, source).searchPage(baseUrl, "fixture", 1, ContentMode.FC2)
            fail("transport failure must not become a completed zero-result search")
        } catch (expected: IllegalStateException) {
            assertEquals("HTTP_503", expected.message)
        }
    }

    @Test
    fun `tag stream emits no successful snapshot when transport fails`() = runTest {
        val source = AvseeClient(http, applicationPageTransport = failureTransport())
        val snapshots = mutableListOf<TagSearchSnapshot>()
        try {
            TagSearchStreamer(http, source).search(baseUrl, "fixture").collect { snapshots += it }
            fail("transport failure must reach the existing error path")
        } catch (expected: IllegalStateException) {
            assertEquals("HTTP_503", expected.message)
        }
        assertTrue(snapshots.isEmpty())
    }

    @Test
    fun `recognized empty result containers remain valid`() {
        val search = parser.parseSearchPage("<div id='at-main'></div>", parser.buildSearchUrl(baseUrl, "fixture", 1))
        val tag = parser.parseTagPage("<div class='tagbox-media'></div>", parser.buildTagSearchUrl(baseUrl, "fixture", 1))
        assertEquals(1, search.totalPages)
        assertEquals(1, tag.totalPages)
        assertTrue(search.posts.isEmpty())
        assertTrue(tag.posts.isEmpty())
    }

    @Test
    fun `synthetic successful responses preserve items in both request paths`() = runTest {
        val searchHtml = "<div id='at-main'><div class='search-media'><div class='media'><div class='media-heading'><a href='/bbs/board.php?bo_table=javfc2&wr_id=42'>Sample document</a></div></div></div></div>"
        val source = AvseeClient(http, applicationPageTransport = fixtureTransport(searchHtml))
        val result = BackgroundSearchClient(http, source).searchPage(baseUrl, "fixture", 1)
        assertEquals(listOf("42"), result.posts.map { it.id })

        val tagHtml = "<div class='tagbox-media'><div class='media'><div class='media-heading'><a href='/bbs/board.php?bo_table=javc&wr_id=43'>Sample document</a></div><div class='media-info'><i class='fa fa-comment'></i> 2 <i class='fa fa-eye'></i> 10</div></div></div>"
        val tagSource = AvseeClient(http, applicationPageTransport = fixtureTransport(tagHtml))
        val snapshots = mutableListOf<TagSearchSnapshot>()
        TagSearchStreamer(http, tagSource).search(baseUrl, "fixture").collect { snapshots += it }
        assertEquals(listOf("43"), snapshots.single().posts.map { it.id })
    }

    private fun fixtureTransport(html: String) = object : ApplicationPageTransport {
        override suspend fun fetch(url: String, referer: String?): String = html
    }

    private fun failureTransport() = object : ApplicationPageTransport {
        override suspend fun fetch(url: String, referer: String?): String = throw IllegalStateException("HTTP_503")
    }
}
