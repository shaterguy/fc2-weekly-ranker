package com.shaterguy.fc2weeklyranker.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ParserFixtureTest {
    private val parser = AvseeClient(OkHttpClient())
    @Test fun `extracts unique post links from board fixture`() { val html = "<table><tr><td><a href='/bbs/board.php?bo_table=javfc2&wr_id=123'>fixture</a><a href='/bbs/board.php?bo_table=javfc2&wr_id=123'>duplicate</a></td></tr></table>"; assertEquals(listOf("https://example.test/bbs/board.php?bo_table=javfc2&wr_id=123"), parser.parseBoardLinks(html, "https://example.test")) }
    @Test fun `extracts datetime recommendation and multiple media without real content`() { val html = "<h1>Synthetic fixture</h1><div>작성일 2026-08-30 12:15:00 추천 9</div><div id='good_button'><strong>9</strong></div><video src='https://media.example.test/a.mp4'></video><iframe src='https://player.example.test/embed/abc'></iframe>"; val post = parser.parseDetail(html, "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=123"); assertEquals("123", post.id); assertEquals(Instant.parse("2026-08-30T03:15:00Z"), post.postedAt); assertEquals(9, post.recommendationCount); assertEquals(2, post.media.size); assertTrue(post.media.any { it.kind == "DIRECT" && it.url.endsWith("a.mp4") }); assertTrue(post.media.any { it.kind == "IFRAME" }) }
}
