package com.shaterguy.fc2weeklyranker.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.LinkedHashMap

data class TagSearchSnapshot(
    val posts: List<RemoteTagPost>,
    val completedPages: Int,
    val totalPages: Int,
)

class TagSearchStreamer(
    http: OkHttpClient,
    private val parser: AvseeClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retrySleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val http = http.newBuilder()
        .retryOnConnectionFailure(false)
        .build()
    private val networkPermits = Semaphore(MAX_CONCURRENT_HTTP_REQUESTS)

    fun search(
        baseUrl: String,
        query: String,
        boardTable: String = "javc",
    ): Flow<TagSearchSnapshot> = flow {
        val term = query.trim()
        require(term.isNotEmpty()) { "태그를 입력해 주세요." }

        val firstUrl = parser.buildTagSearchUrl(baseUrl, term, 1)
        val first = withContextIo {
            parser.parseTagPage(fetch(firstUrl), firstUrl, boardTable)
        }
        val pages = mutableMapOf(1 to first)
        var discoveredLastPage = first.totalPages

        fun snapshot(): TagSearchSnapshot {
            val out = LinkedHashMap<String, RemoteTagPost>()
            pages.toSortedMap().values.forEach { page ->
                page.posts.forEach { post -> out.putIfAbsent(post.id, post) }
            }
            return TagSearchSnapshot(
                posts = out.values.toList(),
                completedPages = pages.size,
                totalPages = discoveredLastPage,
            )
        }

        emit(snapshot())
        if (discoveredLastPage <= 1) return@flow

        coroutineScope {
            var nextPage = 2
            val inFlight = linkedMapOf<Int, Deferred<TagPage>>()

            fun launchMore() {
                while (inFlight.size < MAX_CONCURRENT_HTTP_REQUESTS && nextPage <= discoveredLastPage) {
                    val currentPage = nextPage++
                    inFlight[currentPage] = async(ioDispatcher) {
                        val pageUrl = parser.buildTagSearchUrl(baseUrl, term, currentPage)
                        parser.parseTagPage(fetch(pageUrl, firstUrl), pageUrl, boardTable)
                    }
                }
            }

            launchMore()
            while (inFlight.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val (completedPage, parsed) = select<Pair<Int, TagPage>> {
                    inFlight.forEach { (page, deferred) ->
                        deferred.onAwait { result -> page to result }
                    }
                }
                inFlight.remove(completedPage)
                pages[completedPage] = parsed
                discoveredLastPage = maxOf(discoveredLastPage, parsed.totalPages)
                emit(snapshot())
                launchMore()
            }
        }
    }

    private suspend fun <T> withContextIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(ioDispatcher) { block() }

    private suspend fun fetch(url: String, referer: String? = null): String {
        val request = Request.Builder().url(url)
            .get()
            .header("User-Agent", AvseeClient.USER_AGENT)
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7")
            .apply { if (referer != null) header("Referer", referer) }
            .build()
        return retryTransientGet(sleep = retrySleep) {
            executeRequest(request)
        }
    }

    private suspend fun executeRequest(request: Request): String = networkPermits.withPermit {
        val call = http.newCall(request)
        try {
            runInterruptible(ioDispatcher) {
                call.execute().use { response ->
                    check(response.isSuccessful) { "HTTP_${response.code}" }
                    response.body.string()
                }
            }
        } catch (error: CancellationException) {
            call.cancel()
            throw error
        }
    }

    companion object {
        private const val MAX_CONCURRENT_HTTP_REQUESTS = 4
    }
}
