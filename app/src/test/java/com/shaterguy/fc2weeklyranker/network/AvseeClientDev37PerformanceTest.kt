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

class AvseeClientDev37PerformanceTest {
    @Test
    fun `normal twenty row latest pages use concurrent board collection`() = runBlocking {
        val activeBoardRequests = AtomicInteger(0)
        val maxBoardRequests = AtomicInteger(0)
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val id = request.url.queryParameter("wr_id")
                val body = if (id != null) {
                    Thread.sleep(5)
                    val page = id.toInt() / 1_000
                    detail(id, dateForPage(page))
                } else {
                    val active = activeBoardRequests.incrementAndGet()
                    maxBoardRequests.accumulateAndGet(active, ::maxOf)
                    try {
                        Thread.sleep(80)
                        val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
                        if (page in 1..8) board(page) else emptyBoard()
                    } finally {
                        activeBoardRequests.decrementAndGet()
                    }
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val result = AvseeClient(http).crawlWindow(
            baseUrl = "https://example.test",
            window = windowFor(Instant.parse("2026-09-16T03:00:00Z"), 0),
            boardTable = "javfc2",
        )

        assertEquals(140, result.size)
        assertTrue(
            "twenty-row pages should overlap board requests; max=${maxBoardRequests.get()}",
            maxBoardRequests.get() >= 2,
        )
        println("FC2_DEV37_CRAWL_METRIC rows_per_page=20 max_board_concurrency=${maxBoardRequests.get()} result_count=${result.size}")
    }

    private fun board(page: Int): String = (0 until 20).joinToString(
        prefix = "<form id='fboardlist'><div class='list-container'>",
        postfix = "</div></form>",
    ) { index ->
        val id = page * 1_000 + index
        "<div class='list-item'><h2><a href='/bbs/board.php?bo_table=javfc2&wr_id=$id'>Synthetic $id</a></h2><div class='meta'><span class='comments'><i class='fa fa-comment'></i><b>${id % 37}</b></span><span>10,000</span></div></div>"
    }

    private fun emptyBoard(): String = "<form id='fboardlist'></form>"

    private fun detail(id: String, date: LocalDate): String =
        "<h1>JAV-$id</h1><div id='bo_v_info'>$date 12:00</div>"

    private fun dateForPage(page: Int): LocalDate = when (page) {
        1 -> LocalDate.of(2026, 9, 16)
        2 -> LocalDate.of(2026, 9, 15)
        3 -> LocalDate.of(2026, 9, 14)
        4 -> LocalDate.of(2026, 9, 13)
        5 -> LocalDate.of(2026, 9, 12)
        6 -> LocalDate.of(2026, 9, 11)
        7 -> LocalDate.of(2026, 9, 10)
        else -> LocalDate.of(2026, 9, 9)
    }
}
