package com.shaterguy.fc2weeklyranker.network

import com.shaterguy.fc2weeklyranker.domain.windowFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AvseeClientCancellationTest {
    @Test
    fun `cancelling coroutine cancels active okhttp call`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val captured = AtomicReference<Call?>()
        val http = OkHttpClient.Builder()
            .eventListener(
                object : EventListener() {
                    override fun callStart(call: Call) {
                        captured.compareAndSet(null, call)
                        started.countDown()
                    }
                },
            )
            .addInterceptor { chain ->
                release.await(5, TimeUnit.SECONDS)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(emptyTagPage().toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
        val client = AvseeClient(http)
        val job = launch {
            client.searchTagPosts("https://example.test", "cancel")
        }

        assertTrue(withContext(Dispatchers.IO) { started.await(2, TimeUnit.SECONDS) })
        job.cancel()
        delay(100)
        val cancelledAtBoundary = captured.get()?.isCanceled() == true
        release.countDown()
        job.join()

        assertTrue("active HTTP call must be cancelled with coroutine", cancelledAtBoundary)
    }

    @Test
    fun `clearing crawl cache prevents an older in flight response from repopulating it`() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val boardRequests = AtomicInteger()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val requestNumber = boardRequests.incrementAndGet()
                if (requestNumber == 1) {
                    firstStarted.countDown()
                    firstRelease.await(5, TimeUnit.SECONDS)
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(emptyBoard().toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
        val client = AvseeClient(http)
        val window = windowFor(Instant.parse("2026-09-16T03:00:00Z"), 0)
        val first = async { client.crawlWindow("https://example.test", window) }

        assertTrue(withContext(Dispatchers.IO) { firstStarted.await(2, TimeUnit.SECONDS) })
        client.clearCrawlCache()
        firstRelease.countDown()
        first.await()
        client.crawlWindow("https://example.test", window)

        assertEquals("stale pre-clear response must not repopulate cache", 2, boardRequests.get())
    }

    private fun emptyBoard(): String = "<form id='fboardlist'></form>"

    private fun emptyTagPage(): String = "<div class='tagbox-media'></div>"
}
