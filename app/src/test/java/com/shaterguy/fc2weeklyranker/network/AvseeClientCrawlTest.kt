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
import java.util.concurrent.atomic.AtomicInteger

class AvseeClientCrawlTest {
    @Test
    fun `connection probe rejects board when comment contract is ambiguous`() = runBlocking {
        val client = AvseeClient(
            interceptingClient { request ->
                if (request.url.queryParameter("wr_id") != null) detail("123", "2026-08-29 12:00")
                else boardWithOnlyViews("123")
            },
        )
        val result = client.testConnection("https://example.test")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("댓글수") == true)
    }

    @Test
    fun `same date group needs only two detail probes instead of one per post`() = runBlocking {
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
                        "1" -> board(
                            "101" to 8,
                            "102" to 12,
                            "103" to 4,
                            "104" to 20,
                        )
                        else -> emptyBoard()
                    }
                }
            },
        )

        val posts = client.crawlWindow(
            "https://example.test",
            windowFor(Instant.parse("2026-08-30T08:44:02Z"), 0),
        )

        assertEquals(listOf("101", "102", "103", "104"), posts.map { it.id })
        assertEquals(listOf(8, 12, 4, 20), posts.map { it.commentCount })
        assertEquals(2, detailRequests.get())
        assertEquals(2, boardRequests.get())
        assertTrue("detail requests should be less than the four-post all-detail baseline", detailRequests.get() < posts.size)
    }

    @Test
    fun `older page tail stops crawl before requesting another board page`() = runBlocking {
        val boardRequests = AtomicInteger()
        val detailRequests = AtomicInteger()
        val client = AvseeClient(
            interceptingClient { request ->
                val id = request.url.queryParameter("wr_id")
                if (id != null) {
                    detailRequests.incrementAndGet()
                    when (id) {
                        "101" -> detail(id, "2026-08-29 12:00")
                        "102" -> detail(id, "2026-08-20 12:00")
                        else -> error("Unexpected detail id: $id")
                    }
                } else {
                    boardRequests.incrementAndGet()
                    check((request.url.queryParameter("page") ?: "1") == "1") { "crawl should have stopped before page 2" }
                    board("101" to 9, "102" to 99)
                }
            },
        )

        val posts = client.crawlWindow(
            "https://example.test",
            windowFor(Instant.parse("2026-08-30T08:44:02Z"), 0),
        )

        assertEquals(listOf("101"), posts.map { it.id })
        assertEquals(1, boardRequests.get())
        assertEquals(2, detailRequests.get())
    }

    @Test
    fun `descending date invariant violation fails safely instead of mass classifying`() = runBlocking {
        val client = AvseeClient(
            interceptingClient { request ->
                val id = request.url.queryParameter("wr_id")
                if (id == null) {
                    board("101" to 1, "102" to 2)
                } else {
                    when (id) {
                        "101" -> detail(id, "2026-08-28 12:00")
                        "102" -> detail(id, "2026-08-29 12:00")
                        else -> error("Unexpected detail id: $id")
                    }
                }
            },
        )

        val failure = runCatching {
            client.crawlWindow(
                "https://example.test",
                windowFor(Instant.parse("2026-08-30T08:44:02Z"), 0),
            )
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("내림차순") == true)
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
        "<div class='list-item'><h2><a href='/bbs/board.php?bo_table=javfc2&wr_id=$id'>Synthetic item $id</a></h2><div class='meta'>M Manager <span class='comments'><i class='fa fa-comment'></i><b>$comments</b></span> <span>${10_000 + id.toInt()}</span></div></div>"
    }

    private fun boardWithOnlyViews(id: String): String =
        "<form id='fboardlist'><div class='list-item'><h2><a href='/bbs/board.php?bo_table=javfc2&wr_id=$id'>Synthetic item $id</a></h2><div>M Manager 20,592</div></div></form>"

    private fun emptyBoard(): String = "<form id='fboardlist'></form>"

    private fun detail(id: String, postedAt: String): String =
        "<h1>FC2PPV-$id</h1><div id='bo_v_info'>$postedAt</div>"
}
