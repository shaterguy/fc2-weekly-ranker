package com.shaterguy.fc2weeklyranker.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardListParsingTest {
    private val parser = AvseeClient(OkHttpClient())

    @Test
    fun `board row reads id title and comment count without detail html`() {
        val html = """
            <form id='fboardlist'>
              <div class='list-item'>
                <h2><a href='/bbs/board.php?bo_table=javfc2&wr_id=4969139'>FC2PPV-4969139</a></h2>
                <div class='meta'>M Manager <span class='comments'><i class='fa fa-comment'></i><b>24</b></span> <span>20,592</span></div>
              </div>
            </form>
        """.trimIndent()

        val rows = parser.parseBoardRows(html, "https://example.test")

        assertEquals(1, rows.size)
        assertEquals("4969139", rows.single().id)
        assertEquals("FC2PPV-4969139", rows.single().title)
        assertEquals(24, rows.single().commentCount)
        assertEquals("https://example.test/bbs/board.php?bo_table=javfc2&wr_id=4969139", rows.single().url)
    }

    @Test
    fun `board row fallback uses first metric after title only when comments and views are both visible`() {
        val html = """
            <form id='fboardlist'>
              <div class='list-item'>
                <h2><a href='/bbs/board.php?bo_table=javfc2&wr_id=4968831'>FC2PPV-4968831</a></h2>
                <div>M Manager 106 34,610</div>
              </div>
            </form>
        """.trimIndent()

        assertEquals(106, parser.parseBoardRows(html, "https://example.test").single().commentCount)
    }

    @Test
    fun `ambiguous single metric fails instead of treating views as comments`() {
        val html = """
            <form id='fboardlist'>
              <div class='list-item'>
                <h2><a href='/bbs/board.php?bo_table=javfc2&wr_id=1'>Synthetic</a></h2>
                <div>M Manager 20,592</div>
              </div>
            </form>
        """.trimIndent()

        val failure = runCatching { parser.parseBoardRows(html, "https://example.test") }.exceptionOrNull()
        assertTrue(failure?.message?.contains("댓글수") == true)
    }
}
