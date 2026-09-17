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

// W1 diagnostic fixture: production source remains the exact dev35 baseline.
class AvseeClientMixedDateBaselineTest {
    @Test
    fun `mixed-date latest page emits reproducible baseline metric`() {
        runSample(delayMillis = 100L)
        val samples = (1..5).map { runSample(delayMillis = 100L) }

        samples.forEach { sample ->
            assertEquals(expectedIds(), sample.ids)
            assertTrue("HTTP concurrency must stay bounded: ${sample.maxActive}", sample.maxActive <= 4)
        }
        val medianMillis = samples.map { it.elapsedMillis }.sorted()[samples.size / 2]
        val medianRequests = samples.map { it.requestCount }.sorted()[samples.size / 2]
        val maxConcurrency = samples.maxOf { it.maxActive }
        println(
            "FC2_BULK_CRAWL_METRIC scenario=dev36-mixed-baseline source=dev35 count=24 delay_ms=100 " +
                "median_ms=$medianMillis median_requests=$medianRequests max_concurrency=$maxConcurrency",
        )
    }

    private fun runSample(delayMillis: Long): Sample {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val requests = AtomicInteger()
        val firstDate = LocalDate.of(2026, 9, 16)
        val client = AvseeClient(
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests.incrementAndGet()
                val current = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    Thread.sleep(delayMillis)
                    val id = request.url.queryParameter("wr_id")
                    val body = if (id != null) {
                        val index = id.removePrefix("m").toInt()
                        val dayOffset = (index - 1) / 4
                        detail(id, firstDate.minusDays(dayOffset.toLong()))
                    } else {
                        val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
                        if (page == 1) board() else emptyBoard()
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                        .build()
                } finally {
                    active.decrementAndGet()
                }
            }.build(),
        )

        var ids = emptyList<String>()
        val started = System.nanoTime()
        runBlocking {
            ids = client.crawlWindow(
                "https://example.test",
                windowFor(Instant.parse("2026-09-16T03:00:00Z"), 0),
                boardTable = "javc",
            ).map { it.id }
        }
        return Sample(
            ids = ids,
            elapsedMillis = (System.nanoTime() - started) / 1_000_000L,
            requestCount = requests.get(),
            maxActive = maxActive.get(),
        )
    }

    private fun board(): String = (1..24).joinToString(
        prefix = "<form id='fboardlist'><div class='list-container'>",
        postfix = "</div></form>",
    ) { index ->
        "<div class='list-item'><h2><a href='/bbs/board.php?bo_table=javc&wr_id=m$index'>Synthetic m$index</a></h2>" +
            "<div class='meta'><span class='comments'><i class='fa fa-comment'></i><b>$index</b></span><span>10,000</span></div></div>"
    }

    private fun emptyBoard(): String = "<form id='fboardlist'></form>"

    private fun detail(id: String, date: LocalDate): String =
        "<meta itemprop='datePublished' content='${date}KST12:00:00'><h1>$id</h1>"

    private fun expectedIds(): List<String> = (1..24).map { "m$it" }

    private data class Sample(
        val ids: List<String>,
        val elapsedMillis: Long,
        val requestCount: Int,
        val maxActive: Int,
    )
}
