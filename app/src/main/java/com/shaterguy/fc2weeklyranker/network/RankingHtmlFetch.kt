package com.shaterguy.fc2weeklyranker.network

import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

/** Only ranking GETs may use the HTML-only path. Search/login/media keep navigation. */
internal fun isRankingHtmlTarget(url: String): Boolean = runCatching {
    val uri = URI(url)
    val path = uri.path.orEmpty()
    val query = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
    val board = query.firstOrNull { it[0] == "bo_table" }?.getOrNull(1)
        ?.let { URLDecoder.decode(it, "UTF-8") }
    (path == "/bbs/board.php" && board in setOf("javfc2", "javc")) ||
        Regex("^/(javfc2|javc)/[0-9]+/?$").matches(path)
}.getOrDefault(false)

internal fun isUsableRankingHtml(html: String, url: String): Boolean {
    val doc = Jsoup.parse(html, url)
    val target = URI(url)
    val isList = target.path == "/bbs/board.php" &&
        target.rawQuery.orEmpty().split('&').none { it.substringBefore('=') == "wr_id" }
    if (isList) {
        val board = doc.selectFirst("#fboardlist") ?: return false
        // Empty or script-populated lists take the established browser path.
        val rows = board.select(".list-item")
        return rows.isNotEmpty() && rows.all { it.selectFirst("h2 a[href]") != null }
    }
    return doc.selectFirst("[itemprop=datePublished][content], time[datetime], #bo_v_info") != null
}

/** Response HTML is data; it is never inserted into the current document. */
internal fun rankingHtmlStartScript(url: String, referer: String?, slot: String, maxBodyChars: Int): String {
    val encodedUrl = JSONObject.quote(url)
    val encodedReferer = JSONObject.quote(referer.orEmpty())
    val encodedSlot = JSONObject.quote(slot)
    return """
        (() => {
            const target = new URL($encodedUrl);
            const key = $encodedSlot;
            if (target.origin !== location.origin || target.protocol !== "https:") {
                return false;
            }
            const state = {controller: new AbortController(), result: null};
            window[key] = state;
            const options = {
                method: "GET", mode: "same-origin", credentials: "same-origin",
                redirect: "error", cache: "no-store", signal: state.controller.signal
            };
            const referer = $encodedReferer;
            if (referer && new URL(referer).origin === target.origin) options.referrer = referer;
            (async () => {
                let reader;
                try {
                    const response = await fetch(target.href, options);
                    if (!response.ok) {
                        state.result = {status: response.status, url: response.url, body: ""};
                        await response.body?.cancel();
                        return;
                    }
                    const type = response.headers.get("content-type") || "";
                    if (!/text\/html|application\/xhtml\+xml/i.test(type)) throw new Error("not_html");
                    reader = response.body.getReader();
                    const decoder = new TextDecoder();
                    let body = "";
                    while (true) {
                        const part = await reader.read();
                        body += decoder.decode(part.value, {stream: !part.done});
                        if (body.length > $maxBodyChars) throw new Error("body_limit");
                        if (part.done) break;
                    }
                    state.result = {status: response.status, url: response.url, body};
                } catch (_) {
                    if (window[key] === state) state.result = {status: 0, url: "", body: ""};
                } finally {
                    if (reader) { try { await reader.cancel(); } catch (_) {} }
                }
            })();
            return true;
        })()
    """.trimIndent()
}

internal fun rankingHtmlPollScript(slot: String): String =
    "JSON.stringify(window[" + JSONObject.quote(slot) + "]?.result || null)"

internal fun rankingHtmlCleanupScript(slot: String): String =
    "(() => { const key = " + JSONObject.quote(slot) + "; const state = window[key]; delete window[key]; if (state) state.controller.abort(); })()"
