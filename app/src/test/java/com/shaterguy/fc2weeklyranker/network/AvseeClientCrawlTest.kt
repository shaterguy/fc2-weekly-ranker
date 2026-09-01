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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun `adjacent seven day windows reuse board and detail html`() = runBlocking {
        val boardRequests = AtomicInteger()
        val detailRequests = AtomicInteger()
        val client = AvseeClient(
            interceptingClient { request ->
                val id = request.url.queryParameter("wr_id")
                if (id != null) {
                    detailRequests.incrementAndGet()
                    when (id) {
                        "101" -> detail("101", "2026-08-29 12:00")
                        "201" -> detail("201", "2026-08-20 12:00")
                        "301" -> detail("301", "2026-08-10 12:00")
                        else -> error("Unexpected detail id: $id")
                    }
                } else {
                    boardRequests.incrementAndGet()
                    when (request.url.queryParameter("page") ?: "1") {
                        "1" -> board("101")
                        "2" -> board("201")
                        "3" -> board("301")
                        else -> ""
                    }
                }
            },
        )
        val anchor = Instant.parse("2026-08-30T08:44:02Z")

        val current = client.crawlWindow("https://example.test", windowFor(anchor, 0))
        val previous = client.crawlWindow("https://example.test", windowFor(anchor, 1))

        assertEquals(listOf("101"), current.map { it.id })
        assertEquals(listOf("201"), previous.map { it.id })
        assertEquals(4, boardRequests.get())
        assertEquals(3, detailRequests.get())
    }

    @Test
    fun `detail requests run concurrently with a limit of eight`() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val currentIds = (101..108).map(Int::toString)
        val client = AvseeClient(
            interceptingClient { request ->
                val id = request.url.queryParameter("wr_id")
                if (id == null) {
                    when (request.url.queryParameter("page") ?: "1") {
                        "1" -> board(*currentIds.toTypedArray())
                        "2" -> board("201")
                        else -> ""
                    }
                } else {
                    val nowActive = active.incrementAndGet()
                    maximum.updateAndGet { previous -> maxOf(previous, nowActive) }
                    try {
                        Thread.sleep(75)
                        if (id == "201") detail(id, "2026-08-01 12:00")
                        else detail(id, "2026-08-29 12:00")
                    } finally {
                        active.decrementAndGet()
                    }
                }
            },
        )

        val posts = client.crawlWindow(
            "https://example.test",
            windowFor(Instant.parse("2026-08-30T08:44:02Z"), 0),
        )

        assertEquals(8, posts.size)
        assertTrue("expected more than the dev20 concurrency of four", maximum.get() >= 5)
        assertTrue("detail request limit exceeded", maximum.get() <= 8)
    }

    @Test
    fun `next board page is prefetched while current details are loading`() = runBlocking {
        val activeDetails = AtomicInteger()
        val firstDetailStarted = CountDownLatch(1)
        val nextBoardRequested = CountDownLatch(1)
        val prefetchedWhileActive = AtomicBoolean(false)
        val client = AvseeClient(
            interceptingClient { request ->
                val id = request.url.queryParameter("wr_id")
                if (id == null) {
                    when (request.url.queryParameter("page") ?: "1") {
                        "1" -> board("101", "102", "103", "104")
                        "2" -> {
                            check(firstDetailStarted.await(1, TimeUnit.SECONDS)) { "detail request did not start" }
                            prefetchedWhileActive.set(activeDetails.get() > 0)
                            nextBoardRequested.countDown()
                            board("201")
                        }
                        else -> ""
                    }
                } else {
                    if (id == "201") {
                        detail(id, "2026-08-01 12:00")
                    } else {
                        activeDetails.incrementAndGet()
                        firstDetailStarted.countDown()
                        try {
                            check(nextBoardRequested.await(1, TimeUnit.SECONDS)) { "next board page was not prefetched" }
                            Thread.sleep(25)
                            detail(id, "2026-08-29 12:00")
                        } finally {
                            activeDetails.decrementAndGet()
                        }
                    }
                }
            },
        )

        val posts = client.crawlWindow(
            "https://example.test",
            windowFor(Instant.parse("2026-08-30T08:44:02Z"), 0),
        )

        assertEquals(4, posts.size)
        assertTrue("next board page should overlap current detail loading", prefetchedWhileActive.get())
    }

    @Test
    fun `one older card does not stop later pages`() = runBlocking {
        val client = AvseeClient(
            interceptingClient { request ->
                val id = request.url.queryParameter("wr_id")
                if (id == null) {
                    assertEquals("wr_datetime", request.url.queryParameter("sst"))
                    assertEquals("desc", request.url.queryParameter("sod"))
                    when (request.url.queryParameter("page") ?: "1") {
                        "1" -> board("101", "102")
                        "2" -> board("201")
                        "3" -> board("301")
                        else -> ""
                    }
                } else {
                    when (id) {
                        "101" -> detail(id, "2026-08-29 12:00")
                        "102" -> detail(id, "2026-08-01 12:00")
                        "201" -> detail(id, "2026-08-28 12:00")
                        "301" -> detail(id, "2026-08-01 12:00")
                        else -> error("Unexpected detail id: $id")
                    }
                }
            },
        )

        val posts = client.crawlWindow(
            "https://example.test",
            windowFor(Instant.parse("2026-08-30T08:44:02Z"), 0),
        )

        assertEquals(listOf("101", "201"), posts.map { it.id })
    }

    private fun fakeClient(detailHtml: String): OkHttpClient = interceptingClient { request ->
        if (request.url.queryParameter("wr_id") != null) {
            detailHtml
        } else {
            board("123")
        }
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

    private fun board(vararg ids: String): String = ids.joinToString(
        prefix = "<form id='fboardlist'><div class='list-container'>",
        postfix = "</div></form>",
    ) { id -> "<div class='list-item'><h2><a href='/bbs/board.php?bo_table=javfc2&wr_id=$id'>Synthetic item $id</a></h2></div>" }

    private fun detail(id: String, postedAt: String): String =
        "<h1>FC2PPV-$id</h1><div id='bo_v_info'>$postedAt</div>"
}
