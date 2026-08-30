package com.shaterguy.fc2weeklyranker.network

import com.shaterguy.fc2weeklyranker.domain.windowFor
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
    fun `crawl reports all detail parse failures instead of returning an empty ranking`() = runBlocking {
        val client = AvseeClient(fakeClient(detailHtml = "<h1>Broken detail</h1>"))
        val window = windowFor(Instant.parse("2026-08-30T08:44:02Z"), 0)
        val failure = runCatching { client.crawlWindow("https://example.test", window) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("모두 실패") == true)
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
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
        .build()
}
