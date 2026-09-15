package com.shaterguy.fc2weeklyranker.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class JavTagParserTest {
    private val client = AvseeClient(OkHttpClient())

    @Test
    fun `builds tag search url using the captured tag php contract`() {
        val url = client.buildTagSearchUrl("https://example.test", "#거유 배우", 2)

        assertEquals(
            "https://example.test/bbs/tag.php?q=%23%EA%B1%B0%EC%9C%A0+%EB%B0%B0%EC%9A%B0&eq=&page=2",
            url,
        )
    }

    @Test
    fun `jav detail extracts view tags in source order and deduplicates repeated query`() {
        val html = """
            <html><body>
              <h1>JAV title</h1>
              <meta itemprop='datePublished' content='2026-09-10KST12:00:00'>
              <p class='view-tag view-padding'>
                <a href='/bbs/tag.php?q=%EB%AA%A8%EB%AA%A8%ED%83%80+%EB%AF%B8%EC%B8%A0%ED%82%A4'>모모타 미츠키</a>
                <a href='/bbs/tag.php?q=%EB%AA%A8%EB%AA%A8%ED%83%80+%EB%AF%B8%EC%B8%A0%ED%82%A4'>모모타 미츠키</a>
                <a href='/bbs/tag.php?q=%23%EA%B1%B0%EC%9C%A0'>#거유</a>
                <a href='/bbs/tag.php?q=%40MOODYZ'>@MOODYZ</a>
              </p>
              <a href='/bbs/tag.php'>Tag Search menu</a>
            </body></html>
        """.trimIndent()

        val post = client.parseDetail(
            html = html,
            detailUrl = "https://example.test/bbs/board.php?bo_table=javc&wr_id=2147342",
            referenceInstant = Instant.parse("2026-09-15T00:00:00Z"),
            includeMedia = false,
        )

        assertEquals(
            listOf("모모타 미츠키", "#거유", "@MOODYZ"),
            post.tags.map(RemoteTag::label),
        )
        assertEquals(
            listOf("모모타 미츠키", "#거유", "@MOODYZ"),
            post.tags.map(RemoteTag::query),
        )
    }

    @Test
    fun `fc2 detail never exposes jav tag ui data`() {
        val html = """
            <html><body>
              <h1>FC2 title</h1>
              <meta itemprop='datePublished' content='2026-09-10KST12:00:00'>
              <p class='view-tag view-padding'><a href='/bbs/tag.php?q=%23tag'>#tag</a></p>
            </body></html>
        """.trimIndent()

        val post = client.parseDetail(
            html = html,
            detailUrl = "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=99",
            includeMedia = false,
        )

        assertTrue(post.tags.isEmpty())
    }

    @Test
    fun `tag result parser keeps javc rows parses comment view metrics and matching pagination`() {
        val html = """
            <div class='tagbox-media'>
              <div class='media'>
                <div class='media-body'>
                  <div class='media-heading'><a href='/bbs/board.php?bo_table=javc&wr_id=2009446'><b>MIDA-575 title</b></a></div>
                  <div class='media-info text-muted'>
                    <i class='fa fa-comment'></i><span class='red'>21</span><span class='sp'></span>
                    <i class='fa fa-eye'></i>30,859
                    <span class='hidden-xs'><span class='sp'></span><i class='fa fa-clock-o'></i><time>2026.04.04 00:21</time></span>
                  </div>
                </div>
              </div>
              <div class='media'>
                <div class='media-body'>
                  <div class='media-heading'><a href='/bbs/board.php?bo_table=javm&wr_id=187660'>removed row</a></div>
                  <div class='media-info text-muted'><i class='fa fa-comment'></i><span>23</span><i class='fa fa-eye'></i>30,537</div>
                </div>
              </div>
            </div>
            <ul class='pagination'>
              <li><a href='/bbs/tag.php?q=%EB%AA%A8%EB%AA%A8%ED%83%80+%EB%AF%B8%EC%B8%A0%ED%82%A4&eq=&page=1'>1</a></li>
              <li><a href='/bbs/tag.php?q=%EB%AA%A8%EB%AA%A8%ED%83%80+%EB%AF%B8%EC%B8%A0%ED%82%A4&eq=&page=6'>6</a></li>
              <li><a href='/bbs/tag.php?q=other&eq=&page=99'>other</a></li>
            </ul>
        """.trimIndent()
        val pageUrl = "https://example.test/bbs/tag.php?q=%EB%AA%A8%EB%AA%A8%ED%83%80+%EB%AF%B8%EC%B8%A0%ED%82%A4&eq=&page=2"

        val parsed = client.parseTagPage(html, pageUrl)

        assertEquals(6, parsed.totalPages)
        assertEquals(1, parsed.posts.size)
        val post = parsed.posts.single()
        assertEquals("2009446", post.id)
        assertEquals("MIDA-575 title", post.title)
        assertEquals(21, post.commentCount)
        assertEquals(30_859, post.viewCount)
        assertEquals("https://example.test/bbs/board.php?bo_table=javc&wr_id=2009446", post.url)
    }
}
