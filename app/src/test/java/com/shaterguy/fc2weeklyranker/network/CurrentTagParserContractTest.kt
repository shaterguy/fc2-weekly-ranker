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
            <a href='/bbs/tag.php?q=%23sample&eq=&result_sort=newest&page=997'>missing board</a>
            <a href='/bbs/tag.php?q=%23sample&eq=&onetable=javc&page=996'>missing sort</a>
        """.trimIndent()
        val pageUrl = "https://example.test/bbs/tag.php?q=%23sample&eq=&onetable=javc&result_sort=newest&page=2"

        val parsed = client.parseTagPage(html, pageUrl, "javc")

        assertEquals(420, parsed.totalPages)
        assertEquals(15, parsed.posts.size)
        assertEquals("3000001", parsed.posts.first().id)
        assertEquals(3, parsed.posts.first().commentCount)
        assertEquals(5_582, parsed.posts.first().viewCount)
        assertEquals(1, parsed.posts.first().recommendationCount)
        assertEquals(java.time.Instant.parse("2026-10-01T03:00:00Z"), parsed.posts.first().postedAt)
    }

    @Test
    fun `LTX rank data follows tag pages across boards and filters the seven-day window`() = kotlinx.coroutines.runBlocking {
        val baseUrl = "https://example.test"
        val requested = mutableListOf<String>()
        val transport = object : ApplicationPageTransport {
            override suspend fun fetch(url: String, referer: String?): String {
                requested += url
                val page = url.substringAfterLast("page=").toInt()
                val rows = when (page) {
                    1 -> listOf(Triple("/javm/42", "11.09", "추천 1234"))
                    2 -> listOf(
                        Triple("/javc/42", "11.05", "<i class='fa fa-thumbs-up'></i>2,345"),
                        Triple("/javfc2/72", "10.30", "추천 8"),
                    )
                    else -> error("Unexpected page $page")
                }.joinToString("") { (path, day, rec) ->
                    """<li class='tag-result'>
                       <div class='tag-result-title'><a href='$path'>Result</a></div>
                       <div class='tag-result-meta'>댓글 99 조회 1234 <time>2026.$day 10:00</time> $rec</div>
                       </li>"""
                }
                return """<ul class='tag-results'>$rows</ul>
                    <a href='/bbs/tag.php?q=%23LTX&eq=&tag_op=or&onetable=&result_sort=newest&page=2'>2</a>
                    <a href='/bbs/tag.php?q=%23LTX&eq=&tag_op=and&onetable=&result_sort=newest&page=99'>wrong operator</a>"""
            }
        }
        val client = AvseeClient(OkHttpClient(), applicationPageTransport = transport)
        val window = com.shaterguy.fc2weeklyranker.domain.windowFor(java.time.Instant.parse("2026-11-10T12:00:00Z"), 0)
        val posts = client.crawlTagWindow(baseUrl, "#LTX", window)

        assertEquals(listOf("42", "javc:42"), posts.map { it.id })
        assertEquals(listOf(1234, 2345), posts.map { it.tagRecommendationCount })
        assertEquals(
            listOf(
                "$baseUrl/bbs/tag.php?q=%23LTX&eq=&tag_op=or&onetable=&result_sort=newest&page=1",
                "$baseUrl/bbs/tag.php?q=%23LTX&eq=&tag_op=or&onetable=&result_sort=newest&page=2",
            ),
            requested,
        )
    }
}
