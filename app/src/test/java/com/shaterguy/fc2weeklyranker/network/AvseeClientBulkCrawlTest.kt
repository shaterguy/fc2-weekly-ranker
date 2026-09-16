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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class AvseeClientBulkCrawlTest {
    @Test
    fun `latest three hundred rows are complete and fetched with bounded parallel requests`() = runBlocking {
        val sample = runLatestThreeHundred(delayMillis = 25L)

        assertEquals(300, sample.ids.size)
        assertEquals(expectedLatestIds(), sample.ids)
        assertTrue("latest crawl should issue concurrent requests, max=${sample.maxActive}", sample.maxActive >= 2)
        assertTrue("latest crawl concurrency must stay bounded, max=${sample.maxActive}", sample.maxActive <= 4)
        assertTrue("bounded batching should not explode request count: ${sample.requestCount}", sample.requestCount <= 24)
    }

    @Test
    fun `latest three hundred cold crawl emits five run median metric`() = runBlocking {
        runLatestThreeHundred(delayMillis = 100L)
        val samples = mutableListOf<CrawlSample>()
        repeat(5) { samples += runLatestThreeHundred(delayMillis = 100L) }
        samples.forEach { sample ->
            assertEquals(expectedLatestIds(), sample.ids)
            assertTrue(sample.maxActive <= 4)
            assertTrue(sample.requestCount <= 24)
        }
        val median = median(samples.map(CrawlSample::elapsedMillis))
        val medianRequests = median(samples.map { it.requestCount.toLong() })
        val maxConcurrency = samples.maxOf(CrawlSample::maxActive)
        println(
            "FC2_BULK_CRAWL_METRIC scenario=latest300 count=300 delay_ms=100 " +
                "median_ms=$median median_requests=$medianRequests max_concurrency=$maxConcurrency",
        )
        assertTrue("latest crawl should use bounded parallelism", maxConcurrency >= 2)
    }

    @Test
    fun `historical page sixty four target range keeps exact rows without speculative overfetch`() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val requestedPages = Collections.synchronizedList(mutableListOf<Int>())
        val detailRequests = AtomicInteger()
        val firstDate = LocalDate.of(2026, 9, 1)
        val client = AvseeClient(
            interceptingClient { request ->
                val current = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    Thread.sleep(25L)
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
                } finally {
                    active.decrementAndGet()
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
        assertTrue("historical concurrency must stay bounded, max=${maxActive.get()}", maxActive.get() <= 4)
    }

    private fun runLatestThreeHundred(delayMillis: Long): CrawlSample {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val requests = AtomicInteger()
        val client = AvseeClient(
            interceptingClient { request ->
                requests.incrementAndGet()
                val current = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    Thread.sleep(delayMillis)
                    val id = request.url.queryParameter("wr_id")
                    if (id != null) {
                        val page = id.toInt() / 1_000
                        val date = when (page) {
                            1 -> LocalDate.of(2026, 9, 16)
                            2 -> LocalDate.of(2026, 9, 15)
                            3 -> LocalDate.of(2026, 9, 14)
                            4 -> LocalDate.of(2026, 9, 13)
                            5 -> LocalDate.of(2026, 9, 12)
                            6 -> LocalDate.of(2026, 9, 11)
                            else -> LocalDate.of(2026, 9, 9)
                        }
                        detail(id, "$date 12:00")
                    } else {
                        val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
                        when (page) {
                            in 1..6 -> fiftyRowBoard(page)
                            7 -> fiftyRowBoard(page)
                            else -> emptyBoard()
                        }
                    }
                } finally {
                    active.decrementAndGet()
                }
            },
        )
        var ids: List<String> = emptyList()
        val startedAt = System.nanoTime()
        runBlocking {
            ids = client.crawlWindow(
                "https://example.test",
                windowFor(Instant.parse("2026-09-16T03:00:00Z"), 0),
            ).map { it.id }
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
        return CrawlSample(ids, elapsedMillis, requests.get(), maxActive.get())
    }

    private fun expectedLatestIds(): List<String> = (1..6).flatMap { page ->
        (0 until 50).map { index -> (page * 1_000 + index).toString() }
    }

    private fun fiftyRowBoard(page: Int): String = board(
        *(0 until 50).map { index -> (page * 1_000 + index).toString() to ((page + index) % 41) }.toTypedArray(),
    )

    private fun singleRowBoard(page: Int): String {
        val id = (10_000 - page).toString()
        return board(id to page)
    }

    private fun board(vararg rows: Pair<String, Int>): String = rows.joinToString(
        prefix = "<form id='fboardlist'><div class='list-container'>",
        postfix = "</div></form>",
    ) { (id, comments) ->
        "<div class='list-item'><h2><a href='/bbs/board.php?bo_table=javfc2&wr_id=$id'>Synthetic item $id</a></h2><div class='meta'>M Manager <span class='comments'><i class='fa fa-comment'></i><b>$comments</b></span> <span>10,000</span></div></div>"
    }

    private fun emptyBoard(): String = "<form id='fboardlist'></form>"

    private fun detail(id: String, postedAt: String): String =
        "<h1>FC2PPV-$id</h1><div id='bo_v_info'>$postedAt</div>"

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

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private data class CrawlSample(
        val ids: List<String>,
        val elapsedMillis: Long,
        val requestCount: Int,
        val maxActive: Int,
    )
}
