package com.shaterguy.fc2weeklyranker.network

import com.shaterguy.fc2weeklyranker.domain.ContentMode
import com.shaterguy.fc2weeklyranker.search.BackgroundSearchClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationPageTransportTest {
    private class FakeTransport(
        private val responder: (String) -> String,
    ) : ApplicationPageTransport {
        val calls = mutableListOf<Pair<String, String?>>()

        override suspend fun fetch(url: String, referer: String?): String {
            calls += url to referer
            return responder(url)
        }
    }

    @Test
    fun `connection test uses application page transport for board and detail`() = runTest {
        val transport = FakeTransport { url ->
            if ("wr_id=1" in url) {
                """<article><meta itemprop="datePublished" content="2026-09-29KST10:00:00"><h1>One</h1></article>"""
            } else {
                """<form id="fboardlist"><div class="list-item"><h2><a href="/bbs/board.php?bo_table=javfc2&wr_id=1">One</a></h2><span class="comments"><b>3</b></span><span>20</span></div></form>"""
            }
        }
        val client = AvseeClient(OkHttpClient(), applicationPageTransport = transport)

        assertTrue(client.testConnection("https://02.avsee.is", "javfc2").isSuccess)
        assertEquals(2, transport.calls.size)
        assertTrue(transport.calls.first().first.contains("bo_table=javfc2"))
        assertTrue(transport.calls.last().first.contains("wr_id=1"))
    }

    @Test
    fun `background text search uses application page transport`() = runTest {
        val transport = FakeTransport {
            """<div id="at-main"><div class="search-media"><div class="media"><div class="media-body"><div class="media-heading"><a href="./board.php?bo_table=javfc2&wr_id=9">Nine</a></div></div></div></div></div>"""
        }
        val parser = AvseeClient(OkHttpClient(), applicationPageTransport = transport)
        val client = BackgroundSearchClient(OkHttpClient(), parser)

        val page = client.searchPage("https://02.avsee.is", "nine", 1, ContentMode.FC2)

        assertEquals(1, page.posts.size)
        assertEquals("9", page.posts.single().id)
        assertEquals(1, transport.calls.size)
        assertTrue(transport.calls.single().first.contains("/bbs/search.php"))
    }

    @Test
    fun `tag streamer uses application page transport`() = runTest {
        val html = """
            <div class='tagbox-media'>
              <div class='media'>
                <div class='media-body'>
                  <div class='media-heading'><a href='/javc/11'><b>Eleven</b></a></div>
                  <div class='media-info text-muted'>
                    <i class='fa fa-comment'></i><span class='red'>2</span><span class='sp'></span>
                    <i class='fa fa-eye'></i>10
                  </div>
                </div>
              </div>
            </div>
        """.trimIndent()
        val transport = FakeTransport { html }
        val parser = AvseeClient(OkHttpClient(), applicationPageTransport = transport)
        val direct = parser.parseTagPage(
            html,
            parser.buildTagSearchUrl("https://02.avsee.is", "tag", 1),
            "javc",
        )
        assertEquals("11", direct.posts.single().id)
        val snapshots = TagSearchStreamer(OkHttpClient(), parser)
            .search("https://02.avsee.is", "tag", "javc")
            .toList()

        assertEquals(1, snapshots.size)
        assertEquals("11", snapshots.single().posts.single().id)
        assertEquals(1, transport.calls.size)
        assertTrue(transport.calls.single().first.contains("/bbs/tag.php"))
    }
}
