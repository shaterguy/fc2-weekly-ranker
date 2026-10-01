package com.shaterguy.fc2weeklyranker.network

import java.net.URI
import java.net.URLDecoder

internal val OFFICIAL_APPLICATION_HOSTS = setOf("01.avsee.is", "02.avsee.is")

internal fun officialApplicationStartUrl(baseUrl: String): String? = runCatching {
    val uri = URI(baseUrl)
    require(uri.scheme.equals("https", ignoreCase = true))
    require(uri.userInfo == null)
    val host = uri.host.orEmpty().lowercase()
    require(host in OFFICIAL_APPLICATION_HOSTS)
    "https://$host/bbs/login.php"
}.getOrNull()

internal fun isOfficialApplicationUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.userInfo == null &&
        uri.host.orEmpty().lowercase() in OFFICIAL_APPLICATION_HOSTS
}.getOrDefault(false)
internal fun isAuthenticationRequiredSearchResponse(status: Int, targetUrl: String): Boolean =
    runCatching {
        val uri = URI(targetUrl)
        status in setOf(401, 403) &&
            uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.orEmpty().lowercase() in OFFICIAL_APPLICATION_HOSTS &&
            uri.path.orEmpty() == "/bbs/search.php"
    }.getOrDefault(false)

internal fun isAuthenticationRedirect(url: String): Boolean = runCatching {
    val uri = URI(url)
    val path = uri.path.orEmpty().lowercase()
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.userInfo == null &&
        uri.host.orEmpty().lowercase() in OFFICIAL_APPLICATION_HOSTS &&
        (path.endsWith("/login.php") || "/login/" in path)
}.getOrDefault(false)

internal fun isMatchingApplicationDocument(
    allowedHosts: Set<String>,
    requestedUrl: String,
    observedUrl: String,
): Boolean = runCatching {
    val requested = URI(requestedUrl)
    val observed = URI(observedUrl)
    if (
        !requested.scheme.equals("https", ignoreCase = true) ||
        !observed.scheme.equals("https", ignoreCase = true) ||
        requested.host.orEmpty().lowercase() !in allowedHosts ||
        observed.host.orEmpty().lowercase() !in allowedHosts ||
        requested.path.orEmpty() != observed.path.orEmpty()
    ) return@runCatching false
    val req = queryMap(requested)
    val obs = queryMap(observed)
    when (requested.path.orEmpty()) {
        "/bbs/search.php" ->
            same(req, obs, "sfl") &&
                same(req, obs, "stx") &&
                same(req, obs, "sop") &&
                same(req, obs, "result_type") &&
                same(req, obs, "result_sort") &&
                same(req, obs, "gr_id") &&
                same(req, obs, "srows") &&
                same(req, obs, "onetable") &&
                page(req) == page(obs)
        "/bbs/tag.php" ->
            same(req, obs, "q") &&
                same(req, obs, "eq") &&
                same(req, obs, "onetable") &&
                same(req, obs, "result_sort") &&
                page(req) == page(obs)
        else -> req == obs
    }
}.getOrDefault(false)

private fun same(a: Map<String,List<String>>, b: Map<String,List<String>>, key: String) =
    a[key]?.firstOrNull() == b[key]?.firstOrNull()

private fun page(values: Map<String,List<String>>) =
    values["page"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
private fun queryMap(uri: URI): Map<String,List<String>> {
    if (uri.rawQuery.isNullOrEmpty()) return emptyMap()
    val out = linkedMapOf<String,MutableList<String>>()
    uri.rawQuery.split('&').filter(String::isNotEmpty).forEach { pair ->
        val parts = pair.split('=', limit=2)
        val key = decode(parts[0])
        val value = decode(parts.getOrElse(1){""})
        out.getOrPut(key){ mutableListOf() } += value
    }
    return out
}

private fun decode(value: String) =
    runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
