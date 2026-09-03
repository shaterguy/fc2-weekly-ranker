package com.shaterguy.fc2weeklyranker.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchParserTest {
    private val client = AvseeClient(OkHttpClient())

    @Test
    fun `builds global integrated search url with encoded query and blank onetable`() {
        val url = client.buildSearchUrl("https://example.test", "한글 test", 3)
        assertTrue(url.startsWith("https://example.test/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx="))
        assertTrue(url.contains("%ED%95%9C%EA%B8%80%20test"))
        assertTrue(url.contains("&sop=and&gr_id=&srows=10&onetable=&page=3"))
        assertFalse(url.contains("+"))
    }

    @Test
    fun `parses only main search rows filters javfc2 and deduplicates comment hits`() {
        val html = """
            <div id='at-main'>
              <div class='search-media'>
                <div class='media'>
                  <div class='media-body'>
                    <div class='media-heading'>
                      <a href='./board.php?bo_table=javfc2&wr_id=123#c_1'>first parent title</a>
                    </div>
                    <div class='media-content'><a href='./board.php?bo_table=javfc2&wr_id=123#c_1'>matched text</a></div>
                  </div>
                </div>
              </div>
              <div class='search-media'>
                <div class='media'>
                  <div class='media-body'>
                    <div class='media-heading'>
                      <a href='./board.php?bo_table=javfc2&wr_id=123#c_2'>same parent from another comment</a>
                    </div>
                  </div>
                </div>
              </div>
              <div class='search-media'>
                <div class='media'>
                  <div class='media-body'>
                    <div class='media-heading'>
                      <a href='./board.php?bo_table=other&wr_id=777'>other board result</a>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <div id='at-right'>
              <div class='widget-side-box'>
                <a href='./board.php?bo_table=javfc2&wr_id=999'>sidebar recommendation</a>
              </div>
            </div>
            <a href='/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=needle&sop=and&gr_id=&srows=10&onetable=&page=2'>2</a>
            <a href='/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=needle&sop=and&gr_id=&srows=10&onetable=&page=9'>last</a>
            <a href='/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=other&sop=and&gr_id=&srows=10&onetable=&page=999'>unrelated search</a>
        """.trimIndent()
        val pageUrl = "https://example.test/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=needle&sop=and&gr_id=&srows=10&onetable=&page=1"

        val parsed = client.parseSearchPage(html, pageUrl)

        assertEquals(9, parsed.totalPages)
        assertEquals(1, parsed.posts.size)
        assertEquals("123", parsed.posts.single().id)
        assertEquals("first parent title", parsed.posts.single().title)
        assertEquals("https://example.test/bbs/board.php?bo_table=javfc2&wr_id=123", parsed.posts.single().url)
    }

    @Test
    fun `uses current page when pagination has no later matching link`() {
        val pageUrl = "https://example.test/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=needle&sop=and&gr_id=&srows=10&onetable=&page=4"
        val html = "<div id='at-main'></div><a href='/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=other&sop=and&gr_id=&srows=10&onetable=&page=100'>other</a>"
        assertEquals(4, client.parseSearchPage(html, pageUrl).totalPages)
    }
}
