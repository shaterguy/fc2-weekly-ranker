package com.shaterguy.fc2weeklyranker.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ParserFixtureTest {
    private val parser = AvseeClient(OkHttpClient())

    @Test
    fun `extracts unique post links from board list without sidebar links`() {
        val html = """
            <aside><a href='/bbs/board.php?bo_table=javfc2&wr_id=999'>sidebar old recommendation</a></aside>
            <div id='bo_list'><table><tbody><tr><td>
              <a href='/bbs/board.php?bo_table=javfc2&wr_id=123'>fixture</a>
              <a href='/bbs/board.php?bo_table=javfc2&wr_id=123'>duplicate</a>
            </td></tr></tbody></table></div>
        """.trimIndent()
        assertEquals(
            listOf("https://example.test/bbs/board.php?bo_table=javfc2&wr_id=123"),
            parser.parseBoardLinks(html, "https://example.test"),
        )
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
    fun `parses site shaped relative posting timestamp from post info before comment dates`() {
        val html = """
            <h1>Relative FC2 fixture</h1>
            <div id='bo_v_info'>M Manager 1 1262 1 3시간전</div>
            <section class='comments'>C Cangi 99 999 88 08.29 19:28</section>
        """.trimIndent()
        val post = parser.parseDetail(
            html = html,
            detailUrl = "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=455",
            yearReferenceInstant = Instant.parse("2026-08-24T14:59:59Z"),
            relativeReferenceInstant = Instant.parse("2026-08-30T13:00:00Z"),
        )
        assertEquals(Instant.parse("2026-08-30T10:00:00Z"), post.postedAt)
        assertEquals(1, post.recommendationCount)
    }

    @Test
    fun `finds live header without legacy info selector and ignores comment relative time`() {
        val html = """
            <h1>Selector drift fixture</h1>
            <div class='view-meta'>
              <span>M</span><span>Manager</span><span>1</span><span>1262</span><span>4</span><span>3시간전</span>
            </div>
            <section class='comments'><div>230 Cangi 2시간전</div></section>
        """.trimIndent()
        val post = parser.parseDetail(
            html = html,
            detailUrl = "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=452",
            yearReferenceInstant = Instant.parse("2026-08-24T14:59:59Z"),
            relativeReferenceInstant = Instant.parse("2026-08-30T13:00:00Z"),
        )
        assertEquals(Instant.parse("2026-08-30T10:00:00Z"), post.postedAt)
        assertEquals(4, post.recommendationCount)
    }

    @Test
    fun `parses minute and just now relative posting timestamps`() {
        val observedAt = Instant.parse("2026-08-30T13:00:00Z")
        val minutePost = parser.parseDetail(
            "<h1>Minute fixture</h1><div id='bo_v_info'>M Manager 1 100 4 43분전</div>",
            "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=454",
            observedAt,
            observedAt,
        )
        val justNowPost = parser.parseDetail(
            "<h1>Just now fixture</h1><div id='bo_v_info'>M Manager 1 100 4 방금전</div>",
            "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=453",
            observedAt,
            observedAt,
        )
        assertEquals(Instant.parse("2026-08-30T12:17:00Z"), minutePost.postedAt)
        assertEquals(observedAt, justNowPost.postedAt)
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
    fun `falls back to the last site metric before timestamp for recommendation`() {
        val html = """
            <h1>Live-shaped metric fallback</h1>
            <div id='bo_v_info'>M Manager 11 4913 12 08.29 19:28</div>
        """.trimIndent()
        val post = parser.parseDetail(
            html,
            "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=457",
            Instant.parse("2026-08-30T08:44:02Z"),
        )
        assertEquals(12, post.recommendationCount)
    }

    @Test
    fun `iframe containing direct stream does not create an extra wrapper card`() {
        val html = """
            <h1>Media identity fixture</h1>
            <div id='bo_v_info'>M Manager 1 100 4 08.29 19:28</div>
            <video src='https://MEDIA.example.test/path/a.mp4#player'></video>
            <iframe src='https://player.example.test/embed?url=https%3A%2F%2Fmedia.example.test%2Fpath%2Fa.mp4'></iframe>
        """.trimIndent()
        val post = parser.parseDetail(
            html,
            "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=458",
            Instant.parse("2026-08-30T08:44:02Z"),
        )
        assertEquals(1, post.media.size)
        assertEquals("DIRECT", post.media.single().kind)
        assertEquals("https://media.example.test/path/a.mp4", post.media.single().url)
    }

    @Test
    fun `encoded http iframe media is promoted to https without cleartext playback`() {
        val html = """
            <h1>Legacy wrapper fixture</h1>
            <div id='bo_v_info'>M Manager 1 100 4 08.29 19:28</div>
            <iframe src='https://player.example.test/player.php?720=http%3A%2F%2Fcdn.example.test%2Fh%2Fclip.mp4'></iframe>
        """.trimIndent()
        val post = parser.parseDetail(
            html,
            "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=459",
            Instant.parse("2026-08-30T08:44:02Z"),
        )
        assertEquals(1, post.media.size)
        assertEquals("DIRECT", post.media.single().kind)
        assertEquals("https://cdn.example.test/h/clip.mp4", post.media.single().url)
        assertTrue(AvseeClient.isDownloadableMediaUrl(post.media.single().url))
    }

    @Test
    fun `canonical media identity and download contract distinguish hls`() {
        assertEquals(
            "https://media.example.test/a.mp4?token=A",
            AvseeClient.canonicalMediaUrl("HTTPS://MEDIA.EXAMPLE.TEST:443/a.mp4?token=A#fragment"),
        )
        assertTrue(AvseeClient.isDownloadableMediaUrl("https://media.example.test/a.mp4?token=A"))
        assertTrue(AvseeClient.isDownloadableMediaUrl("https://media.example.test/a.webm"))
        assertTrue(AvseeClient.isHlsUrl("https://media.example.test/master.m3u8?token=A"))
        assertFalse(AvseeClient.isDownloadableMediaUrl("https://media.example.test/master.m3u8?token=A"))
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
