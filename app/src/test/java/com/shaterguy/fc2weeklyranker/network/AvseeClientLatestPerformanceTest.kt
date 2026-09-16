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
import java.util.LinkedHashMap

class AvseeClientLatestPerformanceTest {
    @Test
    fun `latest three hundred cold and warm medians stay within seventy five percent of exact dev34`() = runBlocking {
        benchmarkLatest(warm = false)
        benchmarkLatest(warm = true)
    }

    private suspend fun benchmarkLatest(warm: Boolean) {
        val expectedIds = expectedLatestIds()
        val knownDates = if (warm) allKnownDates() else emptyMap()
        val serialSamples = mutableListOf<Long>()
        val candidateSamples = mutableListOf<Long>()

        repeat(5) {
            val fixture = latestFixture(delayMillis = 100L)
            val parser = AvseeClient(fixture.http)
            val startedAt = System.nanoTime()
            val ids = legacyDev34Latest(fixture.http, parser, fixture.window, knownDates)
            serialSamples += (System.nanoTime() - startedAt) / 1_000_000L
            assertEquals(expectedIds, ids)
        }
        repeat(5) {
            val fixture = latestFixture(delayMillis = 100L)
            val client = AvseeClient(fixture.http)
            val startedAt = System.nanoTime()
            val ids = client.crawlWindow(
                "https://example.test",
                fixture.window,
                knownDates = knownDates,
            ).map { it.id }
            candidateSamples += (System.nanoTime() - startedAt) / 1_000_000L
            assertEquals(expectedIds, ids)
        }

        val serialMedian = median(serialSamples)
        val candidateMedian = median(candidateSamples)
        val scenario = if (warm) "latest300-warm" else "latest300-cold"
        println(
            "FC2_BULK_CRAWL_METRIC scenario=$scenario delay_ms=100 " +
                "serial_dev34_median_ms=$serialMedian candidate_median_ms=$candidateMedian " +
                "ratio=${"%.4f".format(candidateMedian.toDouble() / serialMedian.coerceAtLeast(1L))}",
        )
        assertTrue(
            "expected <= 75% of exact dev34 $scenario; serial=$serialMedian candidate=$candidateMedian",
            candidateMedian * 4 <= serialMedian * 3,
        )
    }

    private suspend fun legacyDev34Latest(
        http: OkHttpClient,
        parser: AvseeClient,
        window: DateWindow,
        knownDates: Map<String, LocalDate>,
    ): List<String> = withContext(Dispatchers.IO) {
        val dateCache = knownDates.toMutableMap()
        val out = LinkedHashMap<String, String>()
        var page = 1
        var previousLastDate: LocalDate? = null

        fun resolve(row: BoardRow, boardUrl: String): LocalDate = dateCache.getOrPut(row.id) {
            parser.parseDetail(
                html = get(http, row.url, boardUrl),
                detailUrl = row.url,
                referenceInstant = window.upperInclusive,
                includeMedia = false,
            ).postedAt.atZone(SEOUL).toLocalDate()
        }

        while (true) {
            val boardUrl = "https://example.test/bbs/board.php?bo_table=javfc2&sop=and&sst=wr_datetime&sod=desc&page=$page"
            val rows = parser.parseBoardRows(get(http, boardUrl), boardUrl)
            if (rows.isEmpty()) break
            val firstDate = resolve(rows.first(), boardUrl)
            val lastDate = if (rows.size == 1) firstDate else resolve(rows.last(), boardUrl)
            previousLastDate?.let { previous -> check(!previous.isBefore(firstDate)) }
            if (firstDate.isBefore(window.startDate)) break
            val dates = if (firstDate == lastDate) {
                List(rows.size) { firstDate }
            } else {
                rows.map { row -> resolve(row, boardUrl) }
            }
            rows.forEachIndexed { index, row ->
                val date = dates[index]
                if (!date.isBefore(window.startDate) && !date.isAfter(window.endDate)) {
                    out.putIfAbsent(row.id, row.id)
                }
            }
            previousLastDate = dates.last()
            if (dates.last().isBefore(window.startDate)) break
            page += 1
        }
        out.values.toList()
    }

    private fun latestFixture(delayMillis: Long): LatestFixture {
        val http = interceptingClient { request ->
            Thread.sleep(delayMillis)
            val id = request.url.queryParameter("wr_id")
            if (id != null) {
                val page = id.toInt() / 1_000
                val date = dateForPage(page)
                detail(id, "$date 12:00")
            } else {
                val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
                when (page) {
                    in 1..7 -> fiftyRowBoard(page)
                    else -> emptyBoard()
                }
            }
        }
        return LatestFixture(
            http = http,
            window = windowFor(Instant.parse("2026-09-16T03:00:00Z"), 0),
        )
    }

    private fun allKnownDates(): Map<String, LocalDate> = buildMap {
        (1..7).forEach { page ->
            idsForPage(page).forEach { id -> put(id, dateForPage(page)) }
        }
    }

    private fun expectedLatestIds(): List<String> = (1..6).flatMap(::idsForPage)

    private fun idsForPage(page: Int): List<String> =
        (0 until 50).map { index -> (page * 1_000 + index).toString() }

    private fun dateForPage(page: Int): LocalDate = when (page) {
        1 -> LocalDate.of(2026, 9, 16)
        2 -> LocalDate.of(2026, 9, 15)
        3 -> LocalDate.of(2026, 9, 14)
        4 -> LocalDate.of(2026, 9, 13)
        5 -> LocalDate.of(2026, 9, 12)
        6 -> LocalDate.of(2026, 9, 11)
        else -> LocalDate.of(2026, 9, 9)
    }

    private fun fiftyRowBoard(page: Int): String = idsForPage(page).joinToString(
        prefix = "<form id='fboardlist'><div class='list-container'>",
        postfix = "</div></form>",
    ) { id ->
        "<div class='list-item'><h2><a href='/bbs/board.php?bo_table=javfc2&wr_id=$id'>Synthetic item $id</a></h2><div class='meta'>M Manager <span class='comments'><i class='fa fa-comment'></i><b>${id.toInt() % 41}</b></span> <span>10,000</span></div></div>"
    }

    private fun emptyBoard(): String = "<form id='fboardlist'></form>"

    private fun detail(id: String, postedAt: String): String =
        "<h1>FC2PPV-$id</h1><div id='bo_v_info'>$postedAt</div>"

    private fun get(http: OkHttpClient, url: String, referer: String? = null): String = http.newCall(
        Request.Builder().url(url).get().apply { if (referer != null) header("Referer", referer) }.build(),
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

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private data class LatestFixture(
        val http: OkHttpClient,
        val window: DateWindow,
    )

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
    }
}
