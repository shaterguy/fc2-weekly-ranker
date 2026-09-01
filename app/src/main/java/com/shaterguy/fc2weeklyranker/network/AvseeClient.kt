package com.shaterguy.fc2weeklyranker.network

import com.shaterguy.fc2weeklyranker.domain.DateWindow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.LinkedHashMap

private const val BOARD_PATH = "/bbs/board.php?bo_table=javfc2&sop=and&sst=wr_datetime&sod=desc"
private const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/151 Mobile Safari/537.36"
private val SEOUL = ZoneId.of("Asia/Seoul")

data class RemoteMedia(val url: String, val referer: String, val kind: String, val ordinal: Int)
data class RemotePost(val id: String, val url: String, val title: String, val postedAt: Instant, val recommendationCount: Int, val media: List<RemoteMedia>)

class AvseeClient(
    private val http: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val crawlHtmlCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MAX_CRAWL_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?,
            ): Boolean = size > MAX_CRAWL_CACHE_ENTRIES
        },
    )

    suspend fun testConnection(baseUrl: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val boardUrl = "$baseUrl$BOARD_PATH"
            val links = parseBoardLinks(fetch(boardUrl), baseUrl)
            check(links.isNotEmpty()) { "게시물 링크를 찾을 수 없습니다." }
            val detailUrl = links.first()
            parseDetail(fetch(detailUrl, boardUrl), detailUrl, Instant.now(), includeMedia = false)
            Unit
        }
    }

    suspend fun crawlWindow(baseUrl: String, window: DateWindow): List<RemotePost> = withContext(ioDispatcher) {
        val out = LinkedHashMap<String, RemotePost>()
        val detailLimiter = Semaphore(MAX_PARALLEL_DETAIL_REQUESTS)
        var prefetchedBoard: Pair<Int, Result<List<String>>>? = null
        for (page in 1..MAX_CRAWL_PAGES) {
            val boardUrl = "$baseUrl$BOARD_PATH&page=$page"
            val prefetched = prefetchedBoard?.takeIf { it.first == page }
            prefetchedBoard = null
            val links = prefetched?.second?.getOrThrow()
                ?: parseBoardLinks(fetchForCrawl(boardUrl), baseUrl)
            if (links.isEmpty()) break
            var parsedOnPage = 0
            var failedOnPage = 0
            var olderOnPage = 0
            val pageResult = coroutineScope {
                val detailDeferreds = links.map { link ->
                    async {
                        runCatching {
                            detailLimiter.withPermit {
                                parseDetail(
                                    fetchForCrawl(link, boardUrl),
                                    link,
                                    window.upperInclusive,
                                    includeMedia = false,
                                )
                            }
                        }
                    }
                }
                val nextBoardDeferred = if (page < MAX_CRAWL_PAGES) {
                    async {
                        val nextPage = page + 1
                        val nextBoardUrl = "$baseUrl$BOARD_PATH&page=$nextPage"
                        nextPage to runCatching {
                            parseBoardLinks(fetchForCrawl(nextBoardUrl), baseUrl)
                        }
                    }
                } else {
                    null
                }
                detailDeferreds.awaitAll() to nextBoardDeferred?.await()
            }
            prefetchedBoard = pageResult.second
            for (result in pageResult.first) {
                val detail = result.onFailure {
                    failedOnPage += 1
                }.getOrNull() ?: continue
                parsedOnPage += 1
                if (detail.postedAt.isBefore(window.startInclusive)) olderOnPage += 1
                if (window.contains(detail.postedAt)) out.putIfAbsent(detail.id, detail)
            }
            check(parsedOnPage > 0 || failedOnPage == 0) {
                "게시물 상세 파싱에 모두 실패했습니다. 사이트 형식이 변경되었는지 확인해 주세요. (page=$page, failed=$failedOnPage)"
            }
            if (failedOnPage == 0 && parsedOnPage > 0 && olderOnPage == parsedOnPage) break
        }
        out.values.toList()
    }

    internal fun clearCrawlCache() {
        crawlHtmlCache.clear()
    }

    suspend fun loadDetail(url: String): RemotePost = withContext(ioDispatcher) {
        parseDetail(fetch(url), url, Instant.now(), includeMedia = true)
    }

    internal fun parseBoardLinks(html: String, baseUrl: String): List<String> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("#fboardlist .list-item h2 a[href*='bo_table=javfc2'][href*='wr_id=']")
            .mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }
            .distinct()
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
        return RemotePost(id, detailUrl, title, postedAt, parseRecommendation(doc), media)
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

    private fun fetchForCrawl(url: String, referer: String? = null): String {
        crawlHtmlCache[url]?.let { return it }
        return fetch(url, referer).also { crawlHtmlCache[url] = it }
    }

    private fun fetch(url: String, referer: String? = null): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7")
            .apply { if (referer != null) header("Referer", referer) }
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP_${response.code}" }
            return response.body.string()
        }
    }

    private fun queryParam(url: String, key: String): String? = runCatching {
        URI(url).rawQuery.orEmpty().split('&')
            .mapNotNull { item -> item.split('=', limit = 2).takeIf { it.size == 2 } }
            .firstOrNull { it[0] == key }?.get(1)
    }.getOrNull()

    companion object {
        const val USER_AGENT: String = UA
        private const val MAX_CRAWL_PAGES = 30
        private const val MAX_PARALLEL_DETAIL_REQUESTS = 8
        private const val MAX_CRAWL_CACHE_ENTRIES = 256
    }
}
