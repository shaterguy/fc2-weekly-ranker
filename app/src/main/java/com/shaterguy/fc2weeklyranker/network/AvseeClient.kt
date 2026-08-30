package com.shaterguy.fc2weeklyranker.network

import com.shaterguy.fc2weeklyranker.domain.DateWindow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

private const val BOARD_PATH = "/bbs/board.php?bo_table=javfc2"
private const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/151 Mobile Safari/537.36"
private val SEOUL = ZoneId.of("Asia/Seoul")

data class RemoteMedia(val url: String, val referer: String, val kind: String, val ordinal: Int)
data class RemotePost(val id: String, val url: String, val title: String, val postedAt: Instant, val recommendationCount: Int, val media: List<RemoteMedia>)

class AvseeClient(
    private val http: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun testConnection(baseUrl: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val boardUrl = "$baseUrl$BOARD_PATH"
            val links = parseBoardLinks(fetch(boardUrl), baseUrl)
            check(links.isNotEmpty()) { "게시물 링크를 찾을 수 없습니다." }
            val detailUrl = links.first()
            parseDetail(fetch(detailUrl, boardUrl), detailUrl, Instant.now())
            Unit
        }
    }

    suspend fun crawlWindow(baseUrl: String, window: DateWindow): List<RemotePost> = withContext(ioDispatcher) {
        val out = LinkedHashMap<String, RemotePost>()
        for (page in 1..30) {
            val boardUrl = "$baseUrl$BOARD_PATH&page=$page"
            val links = parseBoardLinks(fetch(boardUrl), baseUrl)
            if (links.isEmpty()) break
            var sawOlder = false
            var parsedOnPage = 0
            var failedOnPage = 0
            for (link in links) {
                val detail = runCatching {
                    parseDetail(fetch(link, boardUrl), link, window.upperInclusive)
                }.onFailure {
                    failedOnPage += 1
                }.getOrNull() ?: continue
                parsedOnPage += 1
                if (detail.postedAt.isBefore(window.startInclusive)) sawOlder = true
                if (window.contains(detail.postedAt)) out.putIfAbsent(detail.id, detail)
            }
            check(parsedOnPage > 0 || failedOnPage == 0) {
                "게시물 상세 파싱에 모두 실패했습니다. 사이트 형식이 변경되었는지 확인해 주세요. (page=$page, failed=$failedOnPage)"
            }
            if (sawOlder && page >= 2) break
        }
        out.values.toList()
    }

    suspend fun loadDetail(url: String): RemotePost = withContext(ioDispatcher) {
        parseDetail(fetch(url), url, Instant.now())
    }

    internal fun parseBoardLinks(html: String, baseUrl: String): List<String> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("a[href*='bo_table=javfc2'][href*='wr_id=']")
            .mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }
            .distinct()
    }

    internal fun parseDetail(
        html: String,
        detailUrl: String,
        referenceInstant: Instant = Instant.now(),
    ): RemotePost {
        val doc = Jsoup.parse(html, detailUrl)
        val id = queryParam(detailUrl, "wr_id") ?: detailUrl.substringAfterLast('=').take(80)
        val title = listOf("#bo_v_title .bo_v_tit", "#bo_v_title", "h1", "h2")
            .firstNotNullOfOrNull { selector -> doc.selectFirst(selector)?.text()?.trim()?.takeIf(String::isNotBlank) }
            ?: "게시물 $id"
        val postedAt = parsePostedAt(doc, referenceInstant) ?: error("게시시각을 찾을 수 없습니다.")
        return RemotePost(id, detailUrl, title, postedAt, parseRecommendation(doc), parseMedia(doc, detailUrl))
    }

    private fun parsePostedAt(doc: Document, referenceInstant: Instant): Instant? {
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
        doc.select("#good_button strong, #bo_v_act .bo_v_good strong, [id*=good] strong, [class*=good] strong").forEach { node ->
            Regex("\\d+").find(node.text())?.value?.toIntOrNull()?.let { return it }
        }
        return Regex("추천\\s*(?:수)?\\s*[:：]?\\s*(\\d+)")
            .find(doc.body()?.text().orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
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

    companion object { const val USER_AGENT: String = UA }
}
