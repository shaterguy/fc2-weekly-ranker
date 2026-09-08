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
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class ObservationTimestampTest {
    @Test
    fun `cached detail reuse keeps the original actual observation timestamp`() = runBlocking {
        val detailRequests = AtomicInteger()
        val client = AvseeClient(
            interceptingClient { request ->
                val id = request.url.queryParameter("wr_id")
                if (id != null) {
                    detailRequests.incrementAndGet()
                    """
                        <h1>FC2PPV-$id</h1>
                        <div><i class='fa fa-comment'></i><b>7</b></div>
                        <div id='bo_v_info'>2026-09-08 12:00</div>
                    """.trimIndent()
                } else {
                    if ((request.url.queryParameter("page") ?: "1") == "1") {
                        "<form id='fboardlist'><div class='list-item'><h2><a href='/bbs/board.php?bo_table=javfc2&wr_id=101'>item</a></h2></div></form>"
                    } else {
                        ""
                    }
                }
            },
        )
        val window = windowFor(Instant.parse("2026-09-09T00:00:00Z"), 0)

        val first = client.crawlWindow("https://example.test", window).single()
        Thread.sleep(5)
        val second = client.crawlWindow("https://example.test", window).single()

        assertNotNull(first.observedAtEpochMillis)
        assertEquals(first.observedAtEpochMillis, second.observedAtEpochMillis)
        assertEquals(7, second.commentCount)
        assertEquals(1, detailRequests.get())
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
}
