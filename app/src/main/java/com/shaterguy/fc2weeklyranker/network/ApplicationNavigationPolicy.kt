package com.shaterguy.fc2weeklyranker.network

import java.net.URI
import java.net.URLDecoder

internal fun configuredApplicationOrigin(baseUrl: String): String? =
    BaseUrlPolicy.normalize(baseUrl).getOrNull()

internal fun applicationLoginStartUrl(baseUrl: String): String? =
    configuredApplicationOrigin(baseUrl)?.let { "$it/bbs/login.php" }

// Validate the URI authority without migrating it to a different site.
internal fun applicationPageOrigin(url: String): String? = runCatching {
    val uri = URI(url)
    require(uri.scheme.equals("https", ignoreCase = true))
    require(uri.userInfo == null)
    require(uri.port == -1 || uri.port == 443)
    val host = requireNotNull(uri.host).lowercase()
    require(BaseUrlPolicy.normalize("https://$host").isSuccess)
    "https://$host"
}.getOrNull()

internal fun isApplicationNavigationAllowed(configuredOrigin: String, url: String): Boolean =
    applicationPageOrigin(configuredOrigin)?.let { origin -> applicationPageOrigin(url) == origin } == true

private fun isConfiguredGeneralSearchUrl(targetUrl: String, configuredOrigin: String): Boolean = runCatching {
    val uri = URI(targetUrl)
    isApplicationNavigationAllowed(configuredOrigin, targetUrl) &&
        uri.path.orEmpty() == "/bbs/search.php"
}.getOrDefault(false)

internal fun isAuthenticationRequiredSearchResponse(status: Int, targetUrl: String, configuredOrigin: String): Boolean =
    status in setOf(401, 403) && isConfiguredGeneralSearchUrl(targetUrl, configuredOrigin)

internal fun shouldDeferSearchChallengeHttpFailure(
    status: Int,
    targetUrl: String,
    currentDocumentObserved: Boolean,
    challenge: Boolean,
    configuredOrigin: String,
): Boolean {
    val eligibleTarget = isConfiguredGeneralSearchUrl(targetUrl, configuredOrigin)
    return status == 403 && eligibleTarget && (!currentDocumentObserved || challenge)
}

internal fun shouldResetDeferredSearchChallengeHttpFailure(
    status: Int,
    requestedUrl: String,
    observedUrl: String,
    configuredOrigin: String,
): Boolean = runCatching {
    if (status != 403) return@runCatching false
    isConfiguredGeneralSearchUrl(requestedUrl, configuredOrigin) &&
        isConfiguredGeneralSearchUrl(observedUrl, configuredOrigin)
}.getOrDefault(false)

internal fun classifyApplicationDocumentFailure(
    status: Int,
    targetUrl: String,
    challenge: Boolean,
    configuredOrigin: String,
): PageFailureReason? = when {
    !isApplicationNavigationAllowed(configuredOrigin, targetUrl) -> PageFailureReason.ERROR
    challenge -> if (status >= 400) PageFailureReason.HTTP else PageFailureReason.ERROR
    isAuthenticationRequiredSearchResponse(status, targetUrl, configuredOrigin) -> PageFailureReason.AUTHENTICATION
    status !in 200..299 -> PageFailureReason.HTTP
    else -> null
}

internal fun isAuthenticationRedirect(url: String, configuredOrigin: String): Boolean = runCatching {
    val uri = URI(url)
    val path = uri.path.orEmpty().lowercase()
    isApplicationNavigationAllowed(configuredOrigin, url) &&
        (path.endsWith("/login.php") || "/login/" in path)
}.getOrDefault(false)

internal fun isMatchingApplicationDocument(
    allowedHosts: Set<String>,
    requestedUrl: String,
    observedUrl: String,
): Boolean = runCatching {
    val requested = URI(requestedUrl)
    val observed = URI(observedUrl)
    val origin = applicationPageOrigin(requestedUrl) ?: return@runCatching false
    if (
        applicationPageOrigin(observedUrl) != origin ||
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
