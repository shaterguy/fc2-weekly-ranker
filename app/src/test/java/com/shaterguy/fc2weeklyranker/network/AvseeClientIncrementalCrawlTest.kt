package com.shaterguy.fc2weeklyranker.network

import com.shaterguy.fc2weeklyranker.domain.windowFor
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class AvseeClientIncrementalCrawlTest {
    @Test
    fun `known persisted post dates avoid detail requests`() = runBlocking {
        val boardRequests = AtomicInteger()
        val detailRequests = AtomicInteger()
        val client = AvseeClient(
            interceptingClient { request ->
                val id = request.url.queryParameter("wr_id")
                if (id != null) {
                    detailRequests.incrementAndGet()
                    detail(id, "2026-08-29 12:00")
                } else {
                    boardRequests.incrementAndGet()
                    when (request.url.queryParameter("page") ?: "1") {
                        "1" -> board("101" to 8, "102" to 12)
                        else -> emptyBoard()
                    }
                }
            },
        )

        val posts = client.crawlWindow(
            baseUrl = "https://example.test",
            window = windowFor(Instant.parse("2026-08-30T08:44:02Z"), 0),
            knownDates = mapOf(
                "101" to LocalDate.of(2026, 8, 29),
                "102" to LocalDate.of(2026, 8, 29),
            ),
        )

        assertEquals(listOf("101", "102"), posts.map { it.id })
        assertEquals(listOf(8, 12), posts.map { it.commentCount })
        assertEquals(0, detailRequests.get())
        assertEquals(2, boardRequests.get())
    }

    @Test
    fun `historical seek crosses former thirty page ceiling without linear scan`() = runBlocking {
        val requestedPages = mutableListOf<Int>()
        val detailRequests = AtomicInteger()
        val firstDate = LocalDate.of(2026, 9, 1)
        val client = AvseeClient(
            interceptingClient { request ->
                val id = request.url.queryParameter("wr_id")
                if (id != null) {
                    detailRequests.incrementAndGet()
                    val page = 10_000 - id.toInt()
                    detail(id, "${firstDate.minusDays((page - 1).toLong())} 12:00")
                } else {
                    val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
                    requestedPages += page
                    if (page <= 100) singleRowBoard(page) else emptyBoard()
                }
            },
        )

        val posts = client.crawlWindow(
            "https://example.test",
            windowFor(Instant.parse("2026-09-01T00:00:00Z"), 9),
        )

        assertEquals((64..70).map { (10_000 - it).toString() }, posts.map { it.id })
        assertTrue(requestedPages.any { it > 30 })
        assertTrue("historical seek should not scan pages 1 through 64", requestedPages.distinct().size < 30)
        assertTrue(detailRequests.get() < 30)
    }

    private fun interceptingClient(bodyFor: (Request) -> String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bodyFor(request).toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
        .build()

    private fun board(vararg rows: Pair<String, Int>): String = rows.joinToString(
        prefix = "<form id='fboardlist'><div class='list-container'>",
        postfix = "</div></form>",
    ) { (id, comments) ->
        "<div class='list-item'><h2><a href='/bbs/board.php?bo_table=javfc2&wr_id=$id'>Synthetic item $id</a></h2><div class='meta'>M Manager <span class='comments'><i class='fa fa-comment'></i><b>$comments</b></span> <span>10,000</span></div></div>"
    }

    private fun singleRowBoard(page: Int): String {
        val id = (10_000 - page).toString()
        return board(id to page)
    }

    private fun emptyBoard(): String = "<form id='fboardlist'></form>"

    private fun detail(id: String, postedAt: String): String =
        "<h1>FC2PPV-$id</h1><div id='bo_v_info'>$postedAt</div>"
}
