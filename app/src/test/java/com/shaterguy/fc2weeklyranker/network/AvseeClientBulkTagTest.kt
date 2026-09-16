package com.shaterguy.fc2weeklyranker.network

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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class AvseeClientBulkTagTest {
    @Test
    fun `tag pages are fetched concurrently within four request bound and merged in page order`() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val requestedPages = Collections.synchronizedList(mutableListOf<Int>())
        val totalPages = 8
        val query = "bulk"
        val client = AvseeClient(
            interceptingClient { request ->
                val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
                requestedPages += page
                val current = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    Thread.sleep((totalPages - page + 1L) * 12L)
                    tagPage(page = page, totalPages = totalPages, rowsPerPage = 3, query = query)
                } finally {
                    active.decrementAndGet()
                }
            },
        )

        val posts = client.searchTagPosts("https://example.test", query)

        assertEquals((1..totalPages).flatMap { page -> idsForPage(page, 3) }, posts.map(RemoteTagPost::id))
        assertEquals((1..totalPages).toSet(), requestedPages.toSet())
        assertTrue("expected parallel page fetches, max=${maxActive.get()}", maxActive.get() >= 2)
        assertTrue("network concurrency must stay bounded, max=${maxActive.get()}", maxActive.get() <= 4)
    }

    @Test
    fun `tag pagination frontier expands when a later page reveals more matching pages`() = runBlocking {
        val requestedPages = Collections.synchronizedList(mutableListOf<Int>())
        val query = "frontier"
        val client = AvseeClient(
            interceptingClient { request ->
                val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
                requestedPages += page
                val advertisedLastPage = if (page >= 4) 8 else 4
                tagPage(page = page, totalPages = advertisedLastPage, rowsPerPage = 2, query = query)
            },
        )

        val posts = client.searchTagPosts("https://example.test", query)

        assertEquals((1..8).flatMap { page -> idsForPage(page, 2) }, posts.map(RemoteTagPost::id))
        assertEquals((1..8).toSet(), requestedPages.toSet())
    }

    @Test
    fun `ten thousand and thirty thousand tag fixtures finish within half of serial dev34 median`() = runBlocking {
        benchmarkTagCase(totalPages = 10, rowsPerPage = 1_000, expectedCount = 10_000)
        benchmarkTagCase(totalPages = 30, rowsPerPage = 1_000, expectedCount = 30_000)
    }

    private suspend fun benchmarkTagCase(totalPages: Int, rowsPerPage: Int, expectedCount: Int) {
        val query = "benchmark"
        val htmlByPage = (1..totalPages).associateWith { page ->
            tagPage(page = page, totalPages = totalPages, rowsPerPage = rowsPerPage, query = query)
        }
        val delayMillis = 100L
        val http = interceptingClient { request ->
            Thread.sleep(delayMillis)
            val page = request.url.queryParameter("page")?.toIntOrNull() ?: 1
            htmlByPage.getValue(page)
        }
        val parser = AvseeClient(http)

        serialTagSearch(http, parser, "https://example.test", query).also { posts ->
            assertEquals(expectedCount, posts.size)
        }
        parser.searchTagPosts("https://example.test", query).also { posts ->
            assertEquals(expectedCount, posts.size)
        }

        val serialSamples = mutableListOf<Long>()
        repeat(5) {
            val startedAt = System.nanoTime()
            val result = serialTagSearch(http, parser, "https://example.test", query)
            serialSamples += (System.nanoTime() - startedAt) / 1_000_000L
            assertEquals(expectedCount, result.size)
        }
        val candidateSamples = mutableListOf<Long>()
        repeat(5) {
            val startedAt = System.nanoTime()
            val result = parser.searchTagPosts("https://example.test", query)
            candidateSamples += (System.nanoTime() - startedAt) / 1_000_000L
            assertEquals(expectedCount, result.size)
        }
        val serialMedian = median(serialSamples)
        val candidateMedian = median(candidateSamples)
        println(
            "FC2_BULK_TAG_METRIC count=$expectedCount delay_ms=$delayMillis " +
                "serial_dev34_median_ms=$serialMedian candidate_median_ms=$candidateMedian " +
                "ratio=${"%.4f".format(candidateMedian.toDouble() / serialMedian.coerceAtLeast(1L))}",
        )
        assertTrue(
            "expected <= 50% of serial dev34 median for $expectedCount rows; serial=$serialMedian candidate=$candidateMedian",
            candidateMedian * 2 <= serialMedian,
        )
    }

    private suspend fun serialTagSearch(
        http: OkHttpClient,
        parser: AvseeClient,
        baseUrl: String,
        query: String,
    ): List<RemoteTagPost> = withContext(Dispatchers.IO) {
        val firstUrl = parser.buildTagSearchUrl(baseUrl, query, 1)
        val first = parser.parseTagPage(get(http, firstUrl), firstUrl)
        val out = LinkedHashMap<String, RemoteTagPost>()
        first.posts.forEach { post -> out.putIfAbsent(post.id, post) }
        var page = 2
        while (page <= first.totalPages) {
            val pageUrl = parser.buildTagSearchUrl(baseUrl, query, page)
            val parsed = parser.parseTagPage(get(http, pageUrl), pageUrl)
            parsed.posts.forEach { post -> out.putIfAbsent(post.id, post) }
            page += 1
        }
        out.values.toList()
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

    private fun tagPage(page: Int, totalPages: Int, rowsPerPage: Int, query: String): String = buildString {
        append("<div class='tagbox-media'>")
        idsForPage(page, rowsPerPage).forEachIndexed { index, id ->
            append("<div class='media'><div class='media-body'>")
            append("<div class='media-heading'><a href='/bbs/board.php?bo_table=javc&wr_id=")
            append(id)
            append("'>Title ")
            append(id)
            append("</a></div>")
            append("<div class='media-info text-muted'><i class='fa fa-comment'></i><span>")
            append((page + index) % 97)
            append("</span> <i class='fa fa-eye'></i> ")
            append(10_000 + page * rowsPerPage + index)
            append("</div></div></div>")
        }
        append("</div><ul class='pagination'><li><a href='/bbs/tag.php?q=")
        append(query)
        append("&eq=&page=")
        append(totalPages)
        append("'>")
        append(totalPages)
        append("</a></li></ul>")
    }

    private fun idsForPage(page: Int, rowsPerPage: Int): List<String> =
        (0 until rowsPerPage).map { index -> (page * 100_000 + index).toString() }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]
}
