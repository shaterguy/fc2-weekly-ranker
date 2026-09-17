package com.shaterguy.fc2weeklyranker.network

import com.shaterguy.fc2weeklyranker.domain.DateWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

private const val SEARCH_PATH = "/bbs/search.php"
private const val TAG_PATH = "/bbs/tag.php"
private const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/151 Mobile Safari/537.36"
private val SEOUL = ZoneId.of("Asia/Seoul")
private val COUNT_TOKEN = Regex("(?<![A-Za-z0-9])(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?![A-Za-z0-9])")
private val GET_RETRY_DELAYS_MILLIS = listOf(250L, 750L)

private fun boardPath(boardTable: String): String =
    "/bbs/board.php?bo_table=$boardTable&sop=and&sst=wr_datetime&sod=desc"

data class RemoteMedia(val url: String, val referer: String, val kind: String, val ordinal: Int)
data class RemoteTag(val query: String, val label: String)
data class RemotePost(
    val id: String,
    val url: String,
    val title: String,
    val postedAt: Instant,
    val recommendationCount: Int,
    val media: List<RemoteMedia>,
    val tags: List<RemoteTag> = emptyList(),
    val detailRecommendationCount: Int? = null,
    val detailCommentCount: Int? = null,
)
data class RemoteRankPost(val id: String, val url: String, val title: String, val postedAt: Instant, val commentCount: Int)
data class RemoteSearchPost(val id: String, val url: String, val title: String, val occurrenceCount: Int = 1)
data class RemoteTagPost(
    val id: String,
    val url: String,
    val title: String,
    val commentCount: Int,
    val viewCount: Int,
)
internal data class BoardRow(val id: String, val url: String, val title: String, val commentCount: Int)
internal data class SearchPage(val posts: List<RemoteSearchPost>, val totalPages: Int)
internal data class TagPage(val posts: List<RemoteTagPost>, val totalPages: Int)

class AvseeClient(
    http: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retrySleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val http = http.newBuilder()
        .retryOnConnectionFailure(false)
        .build()
    private val networkPermits = Semaphore(MAX_CONCURRENT_HTTP_REQUESTS)
    private val crawlCacheGeneration = AtomicLong(0L)

    private data class CrawlBoardPage(
        val number: Int,
        val url: String,
        val rows: List<BoardRow>,
        val firstDate: LocalDate?,
        val lastDate: LocalDate?,
    )

    private val crawlHtmlCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MAX_CRAWL_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?,
            ): Boolean = size > MAX_CRAWL_CACHE_ENTRIES
        },
    )

    suspend fun testConnection(
        baseUrl: String,
        boardTable: String = "javfc2",
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val boardUrl = "$baseUrl${boardPath(boardTable)}"
            val rows = parseBoardRows(fetch(boardUrl), boardUrl)
            check(rows.isNotEmpty()) { "게시물 목록을 찾을 수 없습니다." }
            val first = rows.first()
            parsePostedDate(fetch(first.url, boardUrl), first.url, Instant.now())
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun crawlWindow(
        baseUrl: String,
        window: DateWindow,
        knownDates: Map<String, LocalDate> = emptyMap(),
        boardTable: String = "javfc2",
    ): List<RemoteRankPost> = withContext(ioDispatcher) {
        val out = LinkedHashMap<String, RemoteRankPost>()
        val dateCache = ConcurrentHashMap<String, LocalDate>().apply { putAll(knownDates) }
        val pageCache = mutableMapOf<Int, CrawlBoardPage>()
        var boardRequestCount = 0

        suspend fun loadPagesOrdered(pages: List<Int>): List<CrawlBoardPage> {
            val requested = pages.distinct()
            requested.forEach { page ->
                require(page in 1..MAX_BOARD_PAGE) { "게시판 페이지가 안전 범위를 벗어났습니다: $page" }
            }
            val missing = requested.filterNot(pageCache::containsKey)
            if (missing.isNotEmpty()) {
                check(boardRequestCount <= MAX_CRAWL_BOARD_REQUESTS - missing.size) {
                    "게시판 탐색 요청이 안전 상한을 초과했습니다. 원문 정렬 상태를 확인해 주세요."
                }
                boardRequestCount += missing.size
                val loaded = coroutineScope {
                    missing.map { page ->
                        async {
                            val boardUrl = "$baseUrl${boardPath(boardTable)}&page=$page"
                            val rows = parseBoardRows(fetchForCrawl(boardUrl), boardUrl)
                            if (rows.isEmpty()) {
                                CrawlBoardPage(page, boardUrl, rows, null, null)
                            } else {
                                val dates = if (rows.size == 1) {
                                    val date = resolveBoardRowDate(
                                        rows.first(),
                                        boardUrl,
                                        window.upperInclusive,
                                        dateCache,
                                    )
                                    date to date
                                } else {
                                    coroutineScope {
                                        val first = async {
                                            resolveBoardRowDate(
                                                rows.first(),
                                                boardUrl,
                                                window.upperInclusive,
                                                dateCache,
                                            )
                                        }
                                        val last = async {
                                            resolveBoardRowDate(
                                                rows.last(),
                                                boardUrl,
                                                window.upperInclusive,
                                                dateCache,
                                            )
                                        }
                                        first.await() to last.await()
                                    }
                                }
                                val firstDate = dates.first
                                val lastDate = dates.second
                                check(!firstDate.isBefore(lastDate)) {
                                    "게시판 작성일 내림차순 전제가 깨졌습니다. 안전을 위해 날짜 자동 분류를 중단합니다."
                                }
                                CrawlBoardPage(page, boardUrl, rows, firstDate, lastDate)
                            }
                        }
                    }.awaitAll()
                }
                loaded.forEach { snapshot -> pageCache[snapshot.number] = snapshot }
            }
            return requested.map(pageCache::getValue)
        }

        suspend fun loadPage(page: Int): CrawlBoardPage = loadPagesOrdered(listOf(page)).single()

        suspend fun lastNonEmptyPage(lowNonEmpty: Int, highEmpty: Int): Int {
            var low = lowNonEmpty + 1
            var high = highEmpty - 1
            var result = lowNonEmpty
            while (low <= high) {
                val mid = low + (high - low) / 2
                if (loadPage(mid).rows.isEmpty()) {
                    high = mid - 1
                } else {
                    result = mid
                    low = mid + 1
                }
            }
            return result
        }

        val firstPage = loadPage(1)
        if (firstPage.rows.isEmpty()) return@withContext emptyList()

        var startPage = 1
        if (firstPage.lastDate!!.isAfter(window.endDate)) {
            var low = 1
            var high = 2
            val second = loadPage(2)
            if (second.rows.isEmpty()) {
                val tailPage = lastNonEmptyPage(1, 2)
                val tail = loadPage(tailPage)
                if (tail.lastDate!!.isAfter(window.endDate)) return@withContext emptyList()
                high = tailPage
            } else if (!second.lastDate!!.isAfter(window.endDate)) {
                high = 2
            } else {
                low = 2
                val fourth = loadPage(4)
                if (fourth.rows.isEmpty()) {
                    val tailPage = lastNonEmptyPage(low, 4)
                    val tail = loadPage(tailPage)
                    if (tail.lastDate!!.isAfter(window.endDate)) return@withContext emptyList()
                    high = tailPage
                } else if (!fourth.lastDate!!.isAfter(window.endDate)) {
                    high = 4
                } else {
                    low = 4
                    var nextProbe = 8
                    var bracketFound = false
                    while (!bracketFound) {
                        val probePages = mutableListOf<Int>()
                        var candidate = nextProbe
                        while (probePages.size < MAX_CONCURRENT_HTTP_REQUESTS) {
                            probePages += candidate
                            if (candidate >= MAX_BOARD_PAGE) break
                            candidate = (candidate * 2).coerceAtMost(MAX_BOARD_PAGE)
                        }
                        val probes = loadPagesOrdered(probePages)
                        for (probe in probes) {
                            if (probe.rows.isEmpty()) {
                                val tailPage = lastNonEmptyPage(low, probe.number)
                                val tail = loadPage(tailPage)
                                if (tail.lastDate!!.isAfter(window.endDate)) return@withContext emptyList()
                                high = tailPage
                                bracketFound = true
                                break
                            }
                            if (!probe.lastDate!!.isAfter(window.endDate)) {
                                high = probe.number
                                bracketFound = true
                                break
                            }
                            low = probe.number
                        }
                        if (!bracketFound) {
                            val lastProbe = probePages.last()
                            check(lastProbe < MAX_BOARD_PAGE) {
                                "게시판 과거 탐색이 페이지 안전 상한에 도달했습니다."
                            }
                            nextProbe = (lastProbe * 2).coerceAtMost(MAX_BOARD_PAGE)
                        }
                    }
                }
            }

            var left = low + 1
            var right = high
            while (left < right) {
                val mid = left + (right - left) / 2
                val probe = loadPage(mid)
                check(probe.rows.isNotEmpty()) { "게시판 페이지 연속성이 깨졌습니다: $mid" }
                if (!probe.lastDate!!.isAfter(window.endDate)) right = mid else left = mid + 1
            }
            startPage = left
        }

        val useBulkCollection =
            startPage >= HISTORICAL_BULK_START_PAGE ||
                (startPage == 1 && firstPage.rows.size >= BULK_BOARD_ROW_THRESHOLD)

        if (useBulkCollection) {
            var page = startPage
            var previousLastDate: LocalDate? = null
            var stop = false
            while (page <= MAX_BOARD_PAGE && !stop) {
                val batchEnd = minOf(MAX_BOARD_PAGE, page + MAX_CONCURRENT_HTTP_REQUESTS - 1)
                val snapshots = loadPagesOrdered((page..batchEnd).toList())
                val included = mutableListOf<CrawlBoardPage>()
                for (snapshot in snapshots) {
                    if (snapshot.rows.isEmpty()) {
                        stop = true
                        break
                    }
                    val firstDate = snapshot.firstDate ?: run {
                        stop = true
                        break
                    }
                    previousLastDate?.let { previous ->
                        check(!previous.isBefore(firstDate)) {
                            "게시판 페이지 간 작성일 내림차순 전제가 깨졌습니다. 안전을 위해 크롤링을 중단합니다."
                        }
                    }
                    if (firstDate.isBefore(window.startDate)) {
                        stop = true
                        break
                    }
                    included += snapshot
                    previousLastDate = snapshot.lastDate
                    if (snapshot.lastDate!!.isBefore(window.startDate)) {
                        stop = true
                        break
                    }
                }

                val resolved = coroutineScope {
                    included.map { snapshot ->
                        async {
                            snapshot to resolveBoardDates(
                                snapshot.rows,
                                snapshot.url,
                                window.upperInclusive,
                                dateCache,
                            )
                        }
                    }.awaitAll()
                }
                resolved.forEach { (snapshot, dates) ->
                    snapshot.rows.forEachIndexed { index, row ->
                        val date = dates[index]
                        if (!date.isBefore(window.startDate) && !date.isAfter(window.endDate)) {
                            out.putIfAbsent(
                                row.id,
                                RemoteRankPost(
                                    id = row.id,
                                    url = row.url,
                                    title = row.title,
                                    postedAt = date.atStartOfDay(SEOUL).toInstant(),
                                    commentCount = row.commentCount,
                                ),
                            )
                        }
                    }
                    if (dates.last().isBefore(window.startDate)) stop = true
                }

                if (!stop) {
                    check(batchEnd < MAX_BOARD_PAGE) {
                        "게시판 목표 구간 수집이 페이지 안전 상한에 도달했습니다."
                    }
                    page = batchEnd + 1
                }
            }
        } else {
            var page = startPage
            var previousLastDate: LocalDate? = null
            while (page <= MAX_BOARD_PAGE) {
                val snapshot = loadPage(page)
                if (snapshot.rows.isEmpty()) break
                val firstDate = snapshot.firstDate ?: break
                previousLastDate?.let { previous ->
                    check(!previous.isBefore(firstDate)) {
                        "게시판 페이지 간 작성일 내림차순 전제가 깨졌습니다. 안전을 위해 크롤링을 중단합니다."
                    }
                }
                if (firstDate.isBefore(window.startDate)) break

                val dates = resolveBoardDates(snapshot.rows, snapshot.url, window.upperInclusive, dateCache)
                snapshot.rows.forEachIndexed { index, row ->
                    val date = dates[index]
                    if (!date.isBefore(window.startDate) && !date.isAfter(window.endDate)) {
                        out.putIfAbsent(
                            row.id,
                            RemoteRankPost(
                                id = row.id,
                                url = row.url,
                                title = row.title,
                                postedAt = date.atStartOfDay(SEOUL).toInstant(),
                                commentCount = row.commentCount,
                            ),
                        )
                    }
                }

                previousLastDate = dates.last()
                if (dates.last().isBefore(window.startDate)) break
                check(page < MAX_BOARD_PAGE) { "게시판 목표 구간 수집이 페이지 안전 상한에 도달했습니다." }
                page += 1
            }
        }
        out.values.toList()
    }

    suspend fun searchPosts(
        baseUrl: String,
        query: String,
        boardTable: String = "javfc2",
    ): List<RemoteSearchPost> = withContext(ioDispatcher) {
        val term = query.trim()
        require(term.isNotEmpty()) { "검색어를 입력해 주세요." }

        val firstUrl = buildSearchUrl(baseUrl, term, 1, boardTable)
        val first = parseSearchPage(fetch(firstUrl), firstUrl)
        val out = LinkedHashMap<String, RemoteSearchPost>()
        first.posts.forEach { post -> mergeSearchPost(out, post) }

        var page = 2
        while (page <= first.totalPages) {
            currentCoroutineContext().ensureActive()
            val pageUrl = buildSearchUrl(baseUrl, term, page, boardTable)
            val parsed = parseSearchPage(fetch(pageUrl, firstUrl), pageUrl)
            parsed.posts.forEach { post -> mergeSearchPost(out, post) }
            page += 1
        }
        out.values.toList()
    }

    suspend fun searchTagPosts(
        baseUrl: String,
        query: String,
        boardTable: String = "javc",
    ): List<RemoteTagPost> = withContext(ioDispatcher) {
        val term = query.trim()
        require(term.isNotEmpty()) { "태그를 입력해 주세요." }

        val firstUrl = buildTagSearchUrl(baseUrl, term, 1)
        val first = parseTagPage(fetch(firstUrl), firstUrl, boardTable)
        val out = LinkedHashMap<String, RemoteTagPost>()
        first.posts.forEach { post -> out.putIfAbsent(post.id, post) }

        var discoveredLastPage = first.totalPages
        var page = 2
        while (page <= discoveredLastPage) {
            currentCoroutineContext().ensureActive()
            val batchEnd = minOf(discoveredLastPage, page + MAX_CONCURRENT_HTTP_REQUESTS - 1)
            val parsedPages = coroutineScope {
                (page..batchEnd).map { currentPage ->
                    async {
                        val pageUrl = buildTagSearchUrl(baseUrl, term, currentPage)
                        parseTagPage(fetch(pageUrl, firstUrl), pageUrl, boardTable)
                    }
                }.awaitAll()
            }
            parsedPages.forEach { parsed ->
                parsed.posts.forEach { post -> out.putIfAbsent(post.id, post) }
                discoveredLastPage = maxOf(discoveredLastPage, parsed.totalPages)
            }
            page = batchEnd + 1
        }
        out.values.toList()
    }

    internal fun buildSearchUrl(
        baseUrl: String,
        query: String,
        page: Int,
        boardTable: String = "javfc2",
    ): String {
        require(page >= 1)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val onetable = boardTable
        return "$baseUrl$SEARCH_PATH?sfl=wr_subject%7C%7Cwr_content&stx=$encoded&sop=and&gr_id=&srows=1000&onetable=$onetable&page=$page"
    }

    internal fun buildTagSearchUrl(baseUrl: String, query: String, page: Int): String {
        require(page >= 1)
        val term = query.trim()
        require(term.isNotEmpty())
        val encoded = URLEncoder.encode(term, "UTF-8")
        return "$baseUrl$TAG_PATH?q=$encoded&eq=&page=$page"
    }

    internal fun parseSearchPage(html: String, pageUrl: String): SearchPage {
        val doc = Jsoup.parse(html, pageUrl)
        val posts = LinkedHashMap<String, RemoteSearchPost>()
        val boardTable = decodedQueryParam(pageUrl, "onetable")?.takeIf(String::isNotBlank) ?: "javfc2"
        doc.select("#at-main .search-media .media").forEach { row ->
            val link = row.selectFirst(".media-heading a[href*='bo_table=$boardTable'][href*='wr_id=']") ?: return@forEach
            val url = link.absUrl("href").takeIf(String::isNotBlank) ?: return@forEach
            if (decodedQueryParam(url, "bo_table") != boardTable) return@forEach
            val id = decodedQueryParam(url, "wr_id")?.takeIf(String::isNotBlank) ?: return@forEach
            val title = link.text().trim().takeIf(String::isNotBlank) ?: "게시물 $id"
            val normalized = RemoteSearchPost(id, url.substringBefore('#'), title)
            val existing = posts[id]
            posts[id] = if (existing == null) normalized else existing.copy(occurrenceCount = existing.occurrenceCount + 1)
        }

        val currentPage = decodedQueryParam(pageUrl, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val signature = searchSignature(pageUrl)
        val totalPages = doc.select("a[href]")
            .mapNotNull { link ->
                val url = link.absUrl("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
                if (!uri.path.orEmpty().endsWith(SEARCH_PATH)) return@mapNotNull null
                if (searchSignature(url) != signature) return@mapNotNull null
                decodedQueryParam(url, "page")?.toIntOrNull()
            }
            .maxOrNull()
            ?.coerceAtLeast(currentPage)
            ?: currentPage
        check(totalPages <= MAX_SEARCH_PAGE) { "검색 페이지 수가 안전 범위를 벗어났습니다: $totalPages" }
        return SearchPage(posts.values.toList(), totalPages)
    }

    internal fun parseTagPage(html: String, pageUrl: String, boardTable: String = "javc"): TagPage {
        val doc = Jsoup.parse(html, pageUrl)
        val posts = LinkedHashMap<String, RemoteTagPost>()
        doc.select(".tagbox-media .media").forEach { row ->
            val link = row.selectFirst(".media-heading a[href*='bo_table=$boardTable'][href*='wr_id=']") ?: return@forEach
            val url = link.absUrl("href").takeIf(String::isNotBlank) ?: return@forEach
            if (decodedQueryParam(url, "bo_table") != boardTable) return@forEach
            val id = decodedQueryParam(url, "wr_id")?.takeIf(String::isNotBlank) ?: return@forEach
            val title = link.text().trim().takeIf(String::isNotBlank) ?: "게시물 $id"
            val (commentCount, viewCount) = parseTagMetrics(row.selectFirst(".media-info")) ?: return@forEach
            posts.putIfAbsent(
                id,
                RemoteTagPost(
                    id = id,
                    url = url.substringBefore('#'),
                    title = title,
                    commentCount = commentCount,
                    viewCount = viewCount,
                ),
            )
        }

        val currentPage = decodedQueryParam(pageUrl, "page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val signature = tagSignature(pageUrl)
        val totalPages = doc.select("a[href]")
            .mapNotNull { link ->
                val url = link.absUrl("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
                if (!uri.path.orEmpty().endsWith(TAG_PATH)) return@mapNotNull null
                if (tagSignature(url) != signature) return@mapNotNull null
                decodedQueryParam(url, "page")?.toIntOrNull()
            }
            .maxOrNull()
            ?.coerceAtLeast(currentPage)
            ?: currentPage
        check(totalPages <= MAX_TAG_PAGE) { "태그 검색 페이지 수가 안전 범위를 벗어났습니다: $totalPages" }
        return TagPage(posts.values.toList(), totalPages)
    }

    internal fun clearCrawlCache() {
        synchronized(crawlHtmlCache) {
            crawlCacheGeneration.incrementAndGet()
            crawlHtmlCache.clear()
        }
    }

    suspend fun loadDetail(url: String): RemotePost = withContext(ioDispatcher) {
        parseDetail(fetch(url), url, Instant.now(), includeMedia = true)
    }

    internal fun parseBoardLinks(html: String, baseUrl: String): List<String> {
        val doc = Jsoup.parse(html, baseUrl)
        val boardTable = decodedQueryParam(baseUrl, "bo_table")?.takeIf(String::isNotBlank) ?: "javfc2"
        return doc.select("#fboardlist .list-item h2 a[href*='bo_table=$boardTable'][href*='wr_id=']")
            .mapNotNull { link ->
                val url = link.absUrl("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
                url.takeIf { decodedQueryParam(it, "bo_table") == boardTable }
            }
            .distinct()
    }

    internal fun parseBoardRows(html: String, baseUrl: String): List<BoardRow> {
        val doc = Jsoup.parse(html, baseUrl)
        val rows = LinkedHashMap<String, BoardRow>()
        val boardTable = decodedQueryParam(baseUrl, "bo_table")?.takeIf(String::isNotBlank) ?: "javfc2"
        doc.select("#fboardlist .list-item").forEach { item ->
            val link = item.selectFirst("h2 a[href*='bo_table=$boardTable'][href*='wr_id=']") ?: return@forEach
            val url = link.absUrl("href").takeIf(String::isNotBlank) ?: return@forEach
            if (decodedQueryParam(url, "bo_table") != boardTable) return@forEach
            val id = queryParam(url, "wr_id")?.takeIf(String::isNotBlank) ?: return@forEach
            val title = link.text().trim().takeIf(String::isNotBlank) ?: "게시물 $id"
            val commentCount = parseBoardCommentCount(item, title)
                ?: error("게시판 댓글수를 찾을 수 없습니다. 사이트 목록 형식이 변경되었는지 확인해 주세요. (post=$id)")
            rows.putIfAbsent(id, BoardRow(id, url, title, commentCount))
        }
        return rows.values.toList()
    }

    internal fun parseDetail(
        html: String,
        detailUrl: String,
        referenceInstant: Instant = Instant.now(),
        includeMedia: Boolean = true,
    ): RemotePost {
        val doc = Jsoup.parse(html, detailUrl)
        val id = queryParam(detailUrl, "wr_id") ?: detailUrl.substringAfterLast('=').take(80)
        val title = listOf("#bo_v_title .bo_v_tit", "#bo_v_title", "h1", "h2")
            .firstNotNullOfOrNull { selector -> doc.selectFirst(selector)?.text()?.trim()?.takeIf(String::isNotBlank) }
            ?: "게시물 $id"
        val postedAt = parsePostedAt(doc, referenceInstant) ?: error("게시시각을 찾을 수 없습니다.")
        val media = if (includeMedia) parseMedia(doc, detailUrl) else emptyList()
        val boardTable = decodedQueryParam(detailUrl, "bo_table")
        val tags = if (boardTable == "javc" || boardTable == "javfc2") parseDetailTags(doc) else emptyList()
        return RemotePost(
            id = id,
            url = detailUrl,
            title = title,
            postedAt = postedAt,
            recommendationCount = parseRecommendation(doc),
            media = media,
            tags = tags,
            detailRecommendationCount = parseDetailRecommendationCount(doc),
            detailCommentCount = parseDetailCommentCount(doc),
        )
    }

    private fun mergeSearchPost(out: LinkedHashMap<String, RemoteSearchPost>, post: RemoteSearchPost) {
        val existing = out[post.id]
        out[post.id] = if (existing == null) post else existing.copy(
            occurrenceCount = existing.occurrenceCount + post.occurrenceCount,
        )
    }

    private fun parseDetailTags(doc: Document): List<RemoteTag> {
        val tags = LinkedHashMap<String, RemoteTag>()
        doc.select("p.view-tag.view-padding a[href*='tag.php?q=']").forEach { link ->
            val url = link.absUrl("href").takeIf(String::isNotBlank) ?: return@forEach
            val uri = runCatching { URI(url) }.getOrNull() ?: return@forEach
            if (!uri.path.orEmpty().endsWith(TAG_PATH)) return@forEach
            val query = decodedQueryParam(url, "q")?.trim()?.takeIf(String::isNotBlank) ?: return@forEach
            val label = link.text().trim().takeIf(String::isNotBlank) ?: query
            tags.putIfAbsent(query, RemoteTag(query = query, label = label))
        }
        return tags.values.toList()
    }

    private fun parseTagMetrics(info: Element?): Pair<Int, Int>? {
        if (info == null || info.selectFirst(".fa-comment") == null || info.selectFirst(".fa-eye") == null) return null
        val metrics = COUNT_TOKEN.findAll(info.text())
            .mapNotNull { match -> match.value.replace(",", "").toIntOrNull() }
            .toList()
        if (metrics.size < 2) return null
        return metrics[0] to metrics[1]
    }

    private suspend fun resolveBoardRowDate(
        row: BoardRow,
        boardUrl: String,
        referenceInstant: Instant,
        dateCache: MutableMap<String, LocalDate>,
    ): LocalDate {
        dateCache[row.id]?.let { return it }
        val parsed = resolveBoardDateBoundary(row.id) {
            parsePostedDate(fetchForCrawl(row.url, boardUrl), row.url, referenceInstant)
        }
        dateCache[row.id] = parsed
        return parsed
    }

    private suspend fun resolveBoardDates(
        rows: List<BoardRow>,
        boardUrl: String,
        referenceInstant: Instant,
        dateCache: MutableMap<String, LocalDate>,
    ): List<LocalDate> {
        require(rows.isNotEmpty())
        val resolved = arrayOfNulls<LocalDate>(rows.size)
        val probeLocks = Array(rows.size) { Mutex() }

        suspend fun probe(index: Int): LocalDate = probeLocks[index].withLock {
            resolved[index]?.let { return@withLock it }
            val parsed = resolveBoardRowDate(rows[index], boardUrl, referenceInstant, dateCache)
            resolved[index] = parsed
            parsed
        }

        suspend fun resolveSequential(start: Int, end: Int) {
            coroutineScope {
                (start..end).map { index -> async { probe(index) } }.awaitAll()
            }
            for (index in start until end) {
                val current = resolved[index] ?: error("게시일자 판정 누락: ${rows[index].id}")
                val next = resolved[index + 1] ?: error("게시일자 판정 누락: ${rows[index + 1].id}")
                check(!current.isBefore(next)) {
                    "게시판 작성일 내림차순 전제가 깨졌습니다. 안전을 위해 날짜 자동 분류를 중단합니다. (${rows[index].id} -> ${rows[index + 1].id})"
                }
            }
        }

        suspend fun resolveSegment(start: Int, end: Int) {
            if (start == end) {
                probe(start)
                return
            }
            try {
                val startDate = probe(start)
                val endDate = probe(end)
                if (startDate.isBefore(endDate)) {
                    resolveSequential(start, end)
                    return
                }
                if (startDate == endDate) {
                    for (index in start..end) resolved[index] = startDate
                    return
                }
                if (end - start == 1) return

                val mid = (start + end) / 2
                val midDate = probe(mid)
                if (startDate.isBefore(midDate) || midDate.isBefore(endDate)) {
                    resolveSequential(start, end)
                    return
                }
                coroutineScope {
                    listOf(
                        async { resolveSegment(start, mid) },
                        async { resolveSegment(mid, end) },
                    ).awaitAll()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: IllegalStateException) {
                resolveSequential(start, end)
            }
        }

        resolveSegment(0, rows.lastIndex)
        val result = resolved.mapIndexed { index, value ->
            value ?: error("게시일자 판정 누락: ${rows[index].id}")
        }
        for (index in 0 until result.lastIndex) {
            check(!result[index].isBefore(result[index + 1])) {
                "게시판 작성일 내림차순 전제가 깨졌습니다. 안전을 위해 날짜 자동 분류를 중단합니다."
            }
        }
        return result
    }

    private fun parseBoardCommentCount(row: Element, title: String): Int? {
        row.select(".fa-comment, [class*=comment], [class*=cmt]").forEach { marker ->
            val texts = buildList {
                marker.text().takeIf(String::isNotBlank)?.let(::add)
                marker.nextElementSibling()?.text()?.takeIf(String::isNotBlank)?.let(::add)
                marker.parent()?.takeIf { it != row }?.text()?.takeIf(String::isNotBlank)?.let(::add)
            }
            texts.firstNotNullOfOrNull(::parseCountToken)?.let { return it }
        }

        val rowText = row.text()
        val titleIndex = rowText.indexOf(title)
        val trailing = if (titleIndex >= 0) rowText.substring(titleIndex + title.length) else rowText
        val metrics = COUNT_TOKEN.findAll(trailing)
            .mapNotNull { match -> match.value.replace(",", "").toIntOrNull() }
            .toList()
        return metrics.firstOrNull()?.takeIf { metrics.size >= 2 }
    }

    private fun parseCountToken(text: String): Int? =
        COUNT_TOKEN.find(text)?.value?.replace(",", "")?.toIntOrNull()

    private fun parsePostedDate(html: String, detailUrl: String, referenceInstant: Instant): LocalDate {
        val doc = Jsoup.parse(html, detailUrl)
        return parsePostedAt(doc, referenceInstant)?.atZone(SEOUL)?.toLocalDate()
            ?: error("게시시각을 찾을 수 없습니다.")
    }

    private fun parsePostedAt(doc: Document, referenceInstant: Instant): Instant? {
        doc.select("[itemprop=datePublished][content]").firstNotNullOfOrNull { node ->
            parsePublishedContent(node.attr("content"))
        }?.let { return it }

        doc.select("time[datetime]").firstNotNullOfOrNull { node ->
            runCatching { Instant.parse(node.attr("datetime")) }.getOrNull()
        }?.let { return it }

        val texts = listOfNotNull(
            doc.selectFirst("#bo_v_info")?.text()?.takeIf(String::isNotBlank),
            doc.body()?.text()?.takeIf(String::isNotBlank),
        ).distinct()

        for (text in texts) {
            Regex("(20\\d{2})[-./](\\d{1,2})[-./](\\d{1,2})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
                .find(text)?.let { match ->
                    val g = match.groupValues
                    return localInstant(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6])
                }
            Regex("(?<!\\d)(\\d{2})-(\\d{2})-(\\d{2})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
                .find(text)?.let { match ->
                    val g = match.groupValues
                    return localInstant(2000 + g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6])
                }
            Regex("(?<!\\d)(\\d{1,2})[.](\\d{1,2})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
                .find(text)?.let { match ->
                    val g = match.groupValues
                    return inferYearlessInstant(
                        month = g[1].toInt(),
                        day = g[2].toInt(),
                        hour = g[3].toInt(),
                        minute = g[4].toInt(),
                        secondText = g[5],
                        referenceInstant = referenceInstant,
                    )
                }
        }
        return null
    }

    private fun parsePublishedContent(value: String): Instant? {
        Regex("(20\\d{2})-(\\d{1,2})-(\\d{1,2})KST(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
            .matchEntire(value.trim())?.let { match ->
                val g = match.groupValues
                return localInstant(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6])
            }
        return runCatching { Instant.parse(value.trim()) }.getOrNull()
    }

    private fun localInstant(year: Int, month: Int, day: Int, hour: Int, minute: Int, secondText: String): Instant? =
        runCatching {
            LocalDateTime.of(year, month, day, hour, minute, secondText.ifBlank { "0" }.toInt())
                .atZone(SEOUL)
                .toInstant()
        }.getOrNull()

    private fun inferYearlessInstant(
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        secondText: String,
        referenceInstant: Instant,
    ): Instant? {
        val referenceYear = referenceInstant.atZone(SEOUL).year
        return (referenceYear - 1..referenceYear + 1)
            .mapNotNull { year -> localInstant(year, month, day, hour, minute, secondText) }
            .minByOrNull { candidate -> Duration.between(candidate, referenceInstant).abs() }
    }

    private fun parseDetailRecommendationCount(doc: Document): Int? {
        doc.selectFirst("#wr_good")?.text()?.let(::parseCountToken)?.let { return it }
        doc.selectFirst(".view-good")?.let { scope ->
            parseCountToken(scope.text())?.let { return it }
        }
        return parseMetricAfterMarker(doc.selectFirst(".view-head .panel-heading .ellipsis"), ".fa-thumbs-up")
    }

    private fun parseDetailCommentCount(doc: Document): Int? {
        parseMetricAfterMarker(doc.selectFirst(".view-head .panel-heading .ellipsis"), ".fa-comment")?.let { return it }
        val fallback = doc.selectFirst(".view-comment") ?: return null
        if (fallback.selectFirst(".fa-commenting, .fa-comment") == null) return null
        return parseCountToken(fallback.text())
    }

    private fun parseMetricAfterMarker(scope: Element?, markerSelector: String): Int? {
        val marker = scope?.selectFirst(markerSelector) ?: return null
        var node = marker.nextElementSibling()
        while (node != null) {
            parseCountToken(node.text())?.let { return it }
            if (node.selectFirst(".fa-comment, .fa-eye, .fa-thumbs-up") != null) break
            node = node.nextElementSibling()
        }
        return null
    }

    private fun parseRecommendation(doc: Document): Int {
        doc.select("#wr_good, [onclick*=apms_good] b, .view-good b, #good_button strong, #bo_v_act .bo_v_good strong, [id*=good] strong, [class*=good] strong").forEach { node ->
            Regex("\\d+").find(node.text())?.value?.toIntOrNull()?.let { return it }
        }

        val bodyText = doc.body()?.text().orEmpty()
        listOf(
            Regex("(?:추천|좋아요)\\s*(?:수)?\\s*[:：]?\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(\\d+)\\s*(?:추천|좋아요)", RegexOption.IGNORE_CASE),
        ).forEach { pattern ->
            pattern.find(bodyText)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }

        val infoText = doc.selectFirst("#bo_v_info, .bo_v_info")?.text().orEmpty()
        val dateStart = listOf(
            Regex("20\\d{2}[-./]\\d{1,2}[-./]\\d{1,2}\\s+\\d{1,2}:\\d{2}"),
            Regex("(?<!\\d)\\d{2}-\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{2}"),
            Regex("(?<!\\d)\\d{1,2}[.]\\d{1,2}\\s+\\d{1,2}:\\d{2}"),
        ).firstNotNullOfOrNull { it.find(infoText)?.range?.first }
        if (dateStart != null) {
            val metrics = Regex("(?<![\\d.])\\d{1,9}(?![\\d.])")
                .findAll(infoText.substring(0, dateStart))
                .mapNotNull { it.value.toIntOrNull() }
                .toList()
            if (metrics.size >= 3) return metrics.last()
        }
        return 0
    }

    private fun parseMedia(doc: Document, detailUrl: String): List<RemoteMedia> {
        val raw = buildList {
            doc.select("video[src], source[src]").forEach { add(it.absUrl("src") to "DIRECT") }
            doc.select("iframe[src]").forEach { add(it.absUrl("src") to "IFRAME") }
            doc.select("a[href]").forEach {
                val url = it.absUrl("href")
                if (looksLikeMedia(url)) add(url to "DIRECT")
            }
        }
        return raw.filter { it.first.startsWith("https://") }
            .distinctBy { it.first }
            .mapIndexed { index, pair -> RemoteMedia(pair.first, detailUrl, pair.second, index) }
    }

    private fun looksLikeMedia(url: String): Boolean {
        val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
        return path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".webm")
    }

    private suspend fun fetchForCrawl(url: String, referer: String? = null): String {
        val generation = crawlCacheGeneration.get()
        synchronized(crawlHtmlCache) {
            crawlHtmlCache[url]?.let { return it }
        }
        val html = fetch(url, referer)
        synchronized(crawlHtmlCache) {
            if (crawlCacheGeneration.get() == generation) {
                crawlHtmlCache[url] = html
            }
        }
        return html
    }

    private suspend fun fetch(url: String, referer: String? = null): String {
        val request = Request.Builder().url(url)
            .get()
            .header("User-Agent", UA)
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

    private fun searchSignature(url: String): List<String> =
        listOf("sfl", "stx", "sop", "gr_id", "srows", "onetable").map { key -> decodedQueryParam(url, key).orEmpty() }

    private fun tagSignature(url: String): List<String> =
        listOf("q", "eq").map { key -> decodedQueryParam(url, key).orEmpty() }

    private fun decodedQueryParam(url: String, key: String): String? =
        queryParam(url, key)?.let { value -> runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value) }

    private fun queryParam(url: String, key: String): String? = runCatching {
        URI(url).rawQuery.orEmpty().split('&')
            .mapNotNull { item -> item.split('=', limit = 2).takeIf { it.size == 2 } }
            .firstOrNull { it[0] == key }?.get(1)
    }.getOrNull()

    companion object {
        const val USER_AGENT: String = UA
        private const val MAX_BOARD_PAGE = 1_000_000
        private const val MAX_SEARCH_PAGE = 1_000_000
        private const val MAX_TAG_PAGE = 1_000_000
        private const val MAX_CRAWL_BOARD_REQUESTS = 2_048
        private const val MAX_CRAWL_CACHE_ENTRIES = 256
        private const val MAX_CONCURRENT_HTTP_REQUESTS = 4
        private const val BULK_BOARD_ROW_THRESHOLD = 25
        private const val HISTORICAL_BULK_START_PAGE = 32
    }
}

internal suspend fun <T> resolveBoardDateBoundary(
    rowId: String,
    resolve: suspend () -> T,
): T = try {
    resolve()
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    throw IllegalStateException("게시일자 경계 판정 실패: $rowId", error)
}

internal fun isTransientNetworkError(error: Throwable): Boolean {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    var transientFound = false
    while (current != null && seen.add(current)) {
        if (
            current is CancellationException ||
            current is SSLException ||
            current is ProtocolException ||
            current is CertificateException
        ) {
            return false
        }
        if (
            current is UnknownHostException ||
            current is ConnectException ||
            current is NoRouteToHostException ||
            current is SocketTimeoutException ||
            current is SocketException
        ) {
            transientFound = true
        }
        current = current.cause
    }
    return transientFound
}

internal suspend fun <T> retryTransientGet(
    delaysMillis: List<Long> = GET_RETRY_DELAYS_MILLIS,
    sleep: suspend (Long) -> Unit = { delay(it) },
    request: suspend () -> T,
): T {
    var retryIndex = 0
    while (true) {
        try {
            return request()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!isTransientNetworkError(error) || retryIndex >= delaysMillis.size) throw error
            sleep(delaysMillis[retryIndex])
            retryIndex += 1
        }
    }
}