package com.shaterguy.fc2weeklyranker.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RankingDetailParsingTest {
    private val parser = AvseeClient(OkHttpClient())

    @Test
    fun `ranking detail path preserves rank fields and skips media discovery`() {
        val html = """
            <h1>FC2PPV-123</h1>
            <span itemprop='datePublished' content='2026-08-29KST19:28:42'>2일전</span>
            <div class='view-good'><b id='wr_good'>37</b></div>
            <video src='https://media.example.test/FC2PPV-123.mp4'></video>
            <iframe src='https://player.example.test/embed/123'></iframe>
        """.trimIndent()
        val url = "https://example.test/bbs/board.php?bo_table=javfc2&wr_id=123"
        val reference = Instant.parse("2026-08-30T08:44:02Z")

        val ranking = parser.parseDetail(html, url, reference, includeMedia = false)
        val full = parser.parseDetail(html, url, reference, includeMedia = true)

        assertEquals(full.id, ranking.id)
        assertEquals(full.title, ranking.title)
        assertEquals(full.postedAt, ranking.postedAt)
        assertEquals(full.recommendationCount, ranking.recommendationCount)
        assertTrue(ranking.media.isEmpty())
        assertEquals(2, full.media.size)
    }
}
