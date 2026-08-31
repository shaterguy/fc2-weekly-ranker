package com.shaterguy.fc2weeklyranker.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ParserFixtureTest {
    private val parser = AvseeClient(OkHttpClient())

    @Test
    fun `extracts only actual board cards and ignores sidebar links`() {
        val html = """
            <form id='fboardlist'>
              <div class='list-item'>
                <a href='/bbs/board.php?bo_table=javfc2&wr_id=123'>image duplicate</a>
                <h2><a href='/bbs/board.php?bo_table=javfc2&wr_id=123'>actual title</a></h2>
              </div>
            </form>
            <aside><a href='/bbs/board.php?bo_table=javfc2&wr_id=999'>sidebar recommendation</a></aside>
        """.trimIndent()
        assertEquals(
            listOf("https://example.test/bbs/board.php?bo_table=javfc2&wr_id=123"),
            parser.parseBoardLinks(html, "https://example.test"),
        )
    }

    @Test
    fun `reads exact live published content and wr good count`() {
        val html = """
            <h1 itemprop='headline'>Live-shaped FC2 fixture</h1>
            <div class='view-head'>
              <i class='fa fa-comment'></i><b>49</b>
              <i class='fa fa-eye'></i>44726
              <i class='fa fa-thumbs-up'></i>37
              <span itemprop='datePublished' content='2026-08-29KST19:28:42'>2일전</span>
            </div>
            <div class='view-good'><a href='#' onclick="apms_good('javfc2', '607221', 'good', 'wr_good')"><b id='wr_good'>37</b></a></div>
        """.trimIndent()
        val post = parser.parseDetail(
            html,
            "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=607221",
        )
        assertEquals(Instant.parse("2026-08-29T10:28:42Z"), post.postedAt)
        assertEquals(37, post.recommendationCount)
    }

    @Test
    fun `extracts datetime recommendation and multiple media without real content`() {
        val html = "<h1>Synthetic fixture</h1><div>작성일 2026-08-30 12:15:00 추천 9</div><div id='good_button'><strong>9</strong></div><video src='https://media.example.test/a.mp4'></video><iframe src='https://player.example.test/embed/abc'></iframe>"
        val post = parser.parseDetail(html, "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=123")
        assertEquals("123", post.id)
        assertEquals(Instant.parse("2026-08-30T03:15:00Z"), post.postedAt)
        assertEquals(9, post.recommendationCount)
        assertEquals(2, post.media.size)
        assertTrue(post.media.any { it.kind == "DIRECT" && it.url.endsWith("a.mp4") })
        assertTrue(post.media.any { it.kind == "IFRAME" })
    }

    @Test
    fun `parses site shaped yearless posting timestamp relative to ranking window`() {
        val html = """
            <h1>Synthetic FC2 fixture</h1>
            <div id='bo_v_info'>M Manager 11 4913 12 08.29 19:28</div>
            <div>출시일 : 2026-09-18</div>
            <div id='good_button'><strong>12</strong></div>
        """.trimIndent()
        val post = parser.parseDetail(
            html,
            "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=456",
            Instant.parse("2026-08-30T08:44:02Z"),
        )
        assertEquals(Instant.parse("2026-08-29T10:28:00Z"), post.postedAt)
        assertEquals(12, post.recommendationCount)
    }

    @Test
    fun `reads recommendation from proven live metadata sequence without a good button`() {
        val html = """
            <h1>Live-shaped metadata fixture</h1>
            <div id='bo_v_info'>M Manager 11 4913 12 08.29 19:28</div>
        """.trimIndent()
        val post = parser.parseDetail(
            html,
            "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=457",
            Instant.parse("2026-08-30T08:44:02Z"),
        )
        assertEquals(Instant.parse("2026-08-29T10:28:00Z"), post.postedAt)
        assertEquals(12, post.recommendationCount)
    }

    @Test
    fun `infers previous year for december post viewed from january window`() {
        val html = "<h1>Year boundary fixture</h1><div id='bo_v_info'>M Manager 1 100 2 12.31 23:55</div><div id='good_button'><strong>2</strong></div>"
        val post = parser.parseDetail(
            html,
            "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=789",
            Instant.parse("2027-01-02T03:00:00Z"),
        )
        assertEquals(Instant.parse("2026-12-31T14:55:00Z"), post.postedAt)
    }
}
