package com.shaterguy.fc2weeklyranker.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LtxParserContractTest {
    private val parser = AvseeClient(OkHttpClient())
    private val base = "https://04.avsee.is"
    private val listing = "$base/javm?sop=and&sst=wr_datetime&sod=desc&page=1"

    @Test fun ltxBoardRowsUseOnlyJavmLinksAndKeepOtherBoardsSeparate() {
        val html = """
            <form id='fboardlist'>
              <div class='list-item'><h2><a href='/javm/4242'>LTX item</a></h2><span class='comments'>댓글 <b>3</b></span></div>
              <div class='list-item'><h2><a href='/javc/4242'>JAV item</a></h2><span class='comments'>댓글 <b>5</b></span></div>
              <div class='list-item'><h2><a href='/javfc2/4242'>FC2 item</a></h2><span class='comments'>댓글 <b>7</b></span></div>
            </form>
        """.trimIndent()
        val rows = parser.parseBoardRows(html, listing)
        assertEquals(1, rows.size)
        assertEquals("4242", rows.first().id)
        assertEquals("$base/javm/4242", rows.first().url)
        assertTrue(isRankingHtmlTarget(listing))
        assertTrue(isRankingHtmlTarget("$base/javm/4242"))
        assertFalse(isRankingHtmlTarget("$base/javm/4242/another"))
    }

    @Test fun ltxGeneralSearchAndTagSearchCarryTheBoardIdentity() {
        val searchUrl = parser.buildSearchUrl(base, "test", 1, "javm")
        val tagUrl = parser.buildTagSearchUrl(base, "#test", 1, "javm")
        assertTrue(searchUrl.contains("onetable=javm"))
        assertTrue(tagUrl.contains("onetable=javm"))
        val searchHtml = """
          <div class='search-results'><div class='search-result'>
            <div class='search-result-title'><a href='/javm/4242'>LTX hit</a></div>
          </div></div>
        """.trimIndent()
        assertEquals("4242", parser.parseSearchPage(searchHtml, searchUrl).posts.single().id)
        val tagsHtml = """
          <div class='tag-results'><div class='tag-result'>
            <div class='tag-result-title'><a href='/javm/4242'>LTX hit</a></div>
            <div class='tag-result-meta'>댓글 3 조회 42 추천 1</div>
          </div></div>
        """.trimIndent()
        assertEquals("4242", parser.parseTagPage(tagsHtml, tagUrl, "javm").posts.single().id)
    }
}
