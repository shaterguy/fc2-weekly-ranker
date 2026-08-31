package com.shaterguy.fc2weeklyranker.network

import com.shaterguy.fc2weeklyranker.domain.windowFor
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AvseeClientCrawlTest {
    @Test
    fun `connection probe rejects board when detail contract is not parseable`() = runBlocking {
        val client = AvseeClient(fakeClient(detailHtml = "<h1>Broken detail</h1>"))
        val result = client.testConnection("https://example.test")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("게시시각") == true)
    }

    @Test
    fun `connection probe accepts current relative timestamp contract`() = runBlocking {
        val observedAt = Instant.parse("2026-08-30T13:00:00Z")
        val client = AvseeClient(
            http = fakeClient(detailHtml = "<h1>Live-shaped detail</h1><div id='bo_v_info'>M Manager 1 1262 1 3시간전</div>"),
            now = { observedAt },
        )
        assertTrue(client.testConnection("https://example.test").isSuccess)
    }

    @Test
    fun `crawl reports all detail parse failures instead of returning an empty ranking`() = runBlocking {
        val client = AvseeClient(fakeClient(detailHtml = "<h1>Broken detail</h1>"))
        val window = windowFor(Instant.parse("2026-08-30T08:44:02Z"), 0)
        val failure = runCatching { client.crawlWindow("https://example.test", window) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("모두 실패") == true)
    }

    @Test
    fun `crawl continues past a mixed page instead of stopping on one old pinned row`() = runBlocking {
        val client = AvseeClient(chronologyClient())
        val window = windowFor(Instant.parse("2026-08-30T08:44:02Z"), 1)
        val posts = client.crawlWindow("https://example.test", window)
        assertEquals(listOf("301"), posts.map { it.id })
    }

    @Test
    fun `historical crawl does not reinterpret a current relative post inside the old window`() = runBlocking {
        val observedAt = Instant.parse("2026-08-30T13:00:00Z")
        val client = AvseeClient(
            http = relativeThenOldClient(),
            now = { observedAt },
        )
        val window = windowFor(Instant.parse("2026-08-30T08:44:02Z"), 1)
        val posts = client.crawlWindow("https://example.test", window)
        assertEquals(listOf("302"), posts.map { it.id })
    }

    private fun fakeClient(detailHtml: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val isDetail = request.url.queryParameter("wr_id") != null
            val body = if (isDetail) {
                detailHtml
            } else {
                "<a href='/bbs/board.php?bo_table=javfc2&wr_id=123'>Synthetic item</a>"
            }
            response(request, body)
        }
        .build()

    private fun chronologyClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val id = request.url.queryParameter("wr_id")
            val body = if (id != null) {
                val posted = when (id) {
                    "101" -> "2026-08-29 12:00:00"
                    "201" -> "2026-08-01 12:00:00"
                    "202" -> "2026-08-25 12:00:00"
                    "301" -> "2026-08-20 12:00:00"
                    else -> "2026-08-01 12:00:00"
                }
                "<h1>Post $id</h1><div id='bo_v_info'>작성일 $posted 추천 7</div>"
            } else {
                when (request.url.queryParameter("page")?.toIntOrNull() ?: 1) {
                    1 -> board("101")
                    2 -> board("201", "202")
                    3 -> board("301")
                    else -> board("401")
                }
            }
            response(request, body)
        }
        .build()

    private fun relativeThenOldClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val id = request.url.queryParameter("wr_id")
            val body = if (id != null) {
                when (id) {
                    "301" -> "<h1>Current relative</h1><div id='bo_v_info'>M Manager 1 1262 1 3시간전</div>"
                    "302" -> "<h1>Historical target</h1><div id='bo_v_info'>M Manager 1 900 5 08.20 12:00</div>"
                    else -> "<h1>Old stop row</h1><div id='bo_v_info'>M Manager 1 100 1 08.01 12:00</div>"
                }
            } else {
                when (request.url.queryParameter("page")?.toIntOrNull() ?: 1) {
                    1 -> board("301")
                    2 -> board("302")
                    else -> board("303")
                }
            }
            response(request, body)
        }
        .build()

    private fun board(vararg ids: String): String = ids.joinToString(prefix = "<div id='bo_list'>", postfix = "</div>") { id ->
        "<a href='/bbs/board.php?bo_table=javfc2&wr_id=$id'>Post $id</a>"
    }

    private fun response(request: okhttp3.Request, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
        .build()
    @Test
    fun `crawl stops when the board repeats links from the previous page`() = runBlocking {
        var boardRequests = 0
        var detailRequests = 0
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val id = request.url.queryParameter("wr_id")
                val body = if (id == null) {
                    boardRequests += 1
                    board("501")
                } else {
                    detailRequests += 1
                    "<h1>Repeated post</h1><div id='bo_v_info'>M Manager 1 100 4 2026-08-29 12:00:00</div>"
                }
                response(request, body)
            }
            .build()
        val client = AvseeClient(http)
        val window = windowFor(Instant.parse("2026-08-30T08:44:02Z"), 0)

        assertEquals(listOf("501"), client.crawlWindow("https://example.test", window).map { it.id })
        assertEquals(2, boardRequests)
        assertEquals(1, detailRequests)
    }

}
