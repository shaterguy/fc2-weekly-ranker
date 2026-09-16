package com.shaterguy.fc2weeklyranker.network

import com.shaterguy.fc2weeklyranker.domain.DateWindow
import com.shaterguy.fc2weeklyranker.domain.windowFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

class AvseeClientHistoricalPerformanceTest {
    @Test
    fun `historical page sixty four first lookup is at most seventy five percent of exact dev34 traversal`() = runBlocking {
        val serialSamples = mutableListOf<Long>()
        val candidateSamples = mutableListOf<Long>()
        val expectedIds = (64..70).map { (10_000 - it).toString() }

        repeat(5) {
            val fixture = historicalFixture(delayMillis = 100L)
            val parser = AvseeClient(fixture.http)
            val startedAt = System.nanoTime()
            val ids = legacyDev34Historical(fixture.http, parser, fixture.window)
            serialSamples += (System.nanoTime() - startedAt) / 1_000_000L
            assertEquals(expectedIds, ids)
        }
        repeat(5) {
            val fixture = historicalFixture(delayMillis = 100L)
            val client = AvseeClient(fixture.http)
            val startedAt = System.nanoTime()
            val ids = client.crawlWindow("https://example.test", fixture.window).map { it.id }
            candidateSamples += (System.nanoTime() - startedAt) / 1_000_000L
            assertEquals(expectedIds, ids)
            assertTrue("historical concurrency must stay bounded, max=${fixture.maxActive.get()}", fixture.maxActive.get() <= 4)
        }

        val serialMedian = median(serialSamples)
        val candidateMedian = median(candidateSamples)
        println(
            "FC2_BULK_CRAWL_METRIC scenario=historical64 delay_ms=100 " +
                "serial_dev34_median_ms=$serialMedian candidate_median_ms=$candidateMedian " +
                "ratio=${"%.4f".format(candidateMedian.toDouble() / serialMedian.coerceAtLeast(1L))}",
        )
        assertTrue(
            "expected <= 75% of exact dev34 traversal; serial=$serialMedian candidate=$candidateMedian",
            candidateMedian * 4 <= serialMedian * 3,
        )
    }

    private suspend fun legacyDev34Historical(
        http: OkHttpClient,
        parser: AvseeClient,
        window: DateWindow,
    ): List<String> = withContext(Dispatchers.IO) {
        val htmlCache = mutableMapOf<String, String>()
        val dateCache = mutableMapOf<String, LocalDate>()

        fun fetchCached(url: String): String = htmlCache.getOrPut(url) { get(http, url) }

        fun load(page: Int): LegacyProbe {
            val boardUrl = "https://example.test/bbs/board.php?bo_table=javfc2&sop=and&sst=wr_datetime&sod=desc&page=$page"
            val rows = parser.parseBoardRows(fetchCached(boardUrl), boardUrl)
            if (rows.isEmpty()) return LegacyProbe(page, null, null)
            val row = rows.single()
            val date = dateCache.getOrPut(row.id) {
                parser.parseDetail(
                    html = fetchCached(row.url),
                    detailUrl = row.url,
                    referenceInstant = window.upperInclusive,
                    includeMedia = false,
                ).postedAt.atZone(SEOUL).toLocalDate()
            }
            return LegacyProbe(page, row.id, date)
        }

        fun lastNonEmptyPage(lowNonEmpty: Int, highEmpty: Int): Int {
            var low = lowNonEmpty + 1
            var high = highEmpty - 1
            var result = lowNonEmpty
            while (low <= high) {
                val mid = low + (high - low) / 2
                if (load(mid).date == null) {
                    high = mid - 1
                } else {
                    result = mid
                    low = mid + 1
                }
            }
            return result
        }

        val first = load(1)
        val firstDate = checkNotNull(first.date)
        var startPage = 1
        if (firstDate.isAfter(window.endDate)) {
            var low = 1
            var high = 2
            while (true) {
                val probe = load(high)
                if (probe.date == null) {
                    val tailPage = lastNonEmptyPage(low, high)
                    val tail = load(tailPage)
                    val tailDate = checkNotNull(tail.date)
                    if (tailDate.isAfter(window.endDate)) return@withContext emptyList()
                    high = tailPage
                    break
                }
                if (!probe.date.isAfter(window.endDate)) break
                low = high
                high *= 2
            }
            var left = low + 1
            var right = high
            while (left < right) {
                val mid = left + (right - left) / 2
                val date = checkNotNull(load(mid).date)
                if (!date.isAfter(window.endDate)) right = mid else left = mid + 1
            }
            startPage = left
        }

        val ids = mutableListOf<String>()
        var page = startPage
        while (true) {
            val probe = load(page)
            val date = probe.date ?: break
            if (date.isBefore(window.startDate)) break
            if (!date.isAfter(window.endDate)) ids += checkNotNull(probe.id)
            page += 1
        }
        ids
    }

    private fun historicalFixture(delayMillis: Long): HistoricalFixture {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val firstDate = LocalDate.of(2026, 9, 1)
        val http = interceptingClient { request ->
            val current = active.incrementAndGet()
            maxActive.updateAndGet { previous -> maxOf(previous, current) }
            try {
                Thread.sleep(delayMillis)
                val id = request.url.queryParameter("wr_id")
                if (id != null) {
                    val page = 10_000 - id.toInt()
                    detail(id, "${firstDate.minusDays((page - 1).toLong())} 12:00")
                } else {
                    val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
                    if (page <= 100) singleRowBoard(page) else emptyBoard()
                }
            } finally {
                active.decrementAndGet()
            }
        }
        return HistoricalFixture(
            http = http,
            window = windowFor(Instant.parse("2026-09-01T00:00:00Z"), 9),
            maxActive = maxActive,
        )
    }

    private fun get(http: OkHttpClient, url: String): String = http.newCall(
        Request.Builder().url(url).get().build(),
    ).execute().use { response ->
        check(response.isSuccessful)
        response.body.string()
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

    private fun singleRowBoard(page: Int): String {
        val id = (10_000 - page).toString()
        return "<form id='fboardlist'><div class='list-item'><h2><a href='/bbs/board.php?bo_table=javfc2&wr_id=$id'>Synthetic item $id</a></h2><div class='meta'>M Manager <span class='comments'><i class='fa fa-comment'></i><b>$page</b></span> <span>10,000</span></div></div></form>"
    }

    private fun emptyBoard(): String = "<form id='fboardlist'></form>"

    private fun detail(id: String, postedAt: String): String =
        "<h1>FC2PPV-$id</h1><div id='bo_v_info'>$postedAt</div>"

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private data class LegacyProbe(val page: Int, val id: String?, val date: LocalDate?)

    private data class HistoricalFixture(
        val http: OkHttpClient,
        val window: DateWindow,
        val maxActive: AtomicInteger,
    )

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
    }
}
