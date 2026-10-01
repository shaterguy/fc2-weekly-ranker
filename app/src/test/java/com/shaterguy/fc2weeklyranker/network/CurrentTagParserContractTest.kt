package com.shaterguy.fc2weeklyranker.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentTagParserContractTest {
    private val client = AvseeClient(OkHttpClient())

    @Test
    fun `tag URL carries selected board without changing legacy default contract`() {
        assertEquals(
            "https://example.test/bbs/tag.php?q=%23sample&eq=&onetable=javc&result_sort=newest&page=2",
            client.buildTagSearchUrl("https://example.test", "#sample", 2, "javc"),
        )
        assertEquals(
            "https://example.test/bbs/tag.php?q=%23sample&eq=&onetable=javfc2&result_sort=newest&page=3",
            client.buildTagSearchUrl("https://example.test", "#sample", 3, "javfc2"),
        )
    }

    @Test
    fun `current tag-result DOM parses labeled metrics and board scoped pagination`() {
        val rows = (1..15).joinToString("\n") { index ->
            val id = 3_000_000 + index
            val views = if (index == 1) "5,582" else (5_582 + index).toString()
            """
                <li class='tag-result'>
                  <div class='tag-result-title'><a href='/javc/$id'>Synthetic tag result $index</a></div>
                  <div class='tag-result-meta'>댓글 ${index + 2} 조회 $views 추천 1 <time>2026.10.01 12:00</time></div>
                </li>
            """.trimIndent()
        }
        val html = """
            <ul class='tag-results'>$rows</ul>
            <a href='/bbs/tag.php?q=%23sample&eq=&onetable=javc&result_sort=newest&page=420'>420</a>
            <a href='/bbs/tag.php?q=%23sample&eq=&onetable=javfc2&result_sort=newest&page=999'>wrong board</a>
            <a href='/bbs/tag.php?q=%23sample&eq=&onetable=javc&result_sort=oldest&page=998'>wrong sort</a>
        """.trimIndent()
        val pageUrl = "https://example.test/bbs/tag.php?q=%23sample&eq=&onetable=javc&result_sort=newest&page=2"

        val parsed = client.parseTagPage(html, pageUrl, "javc")

        assertEquals(420, parsed.totalPages)
        assertEquals(15, parsed.posts.size)
        assertEquals("3000001", parsed.posts.first().id)
        assertEquals(3, parsed.posts.first().commentCount)
        assertEquals(5_582, parsed.posts.first().viewCount)
    }
}
