package com.shaterguy.fc2weeklyranker.network

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import kotlin.coroutines.resume

interface ApplicationPageTransport {
    suspend fun fetch(url: String, referer: String? = null): String
}

internal class WebViewApplicationPageTransport(
    context: Context,
    private val userAgent: String,
    private val hasUsableSearchResults: (String, String) -> Boolean = { _, _ -> false },
    private val fixtureResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    private val fixtureDiagnostic: ((String) -> Unit)? = null,
) : ApplicationPageTransport {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var session: BrowserSession? = null

    private data class BrowserSession(
        val origin: String,
        val allowedHosts: Set<String>,
        val webView: WebView,
        val navigation: NavigationState,
        var hasNavigated: Boolean = false,
    )

    // Read and written only on the WebView's main thread.
    private class NavigationState {
        var requestedUrl: String? = null
        var httpStatus: Int = 200
        var loadError: Int? = null

        fun begin(url: String) {
            requestedUrl = url
            httpStatus = 200
            loadError = null
        }
    }

    private data class PageResult(
        val status: Int,
        val finalUrl: String,
        val body: String,
    )

    override suspend fun fetch(url: String, referer: String?): String = mutex.withLock {
        val target = validatedTarget(url)
        var lastStatus = 0
        var lastFailure: Throwable? = null
        repeat(MAX_SESSION_ATTEMPTS) { attempt ->
            try {
                val current = ensureSession(target)
                val result = fetchInSession(current, target.toString(), referer)
                lastStatus = result.status
                if (!isChallenge(result)) {
                    check(result.status in 200..299) { "HTTP_${result.status}" }
                    requireAllowedFinalUrl(current, result.finalUrl)
                    return@withLock result.body
                }
                lastFailure = IllegalStateException("HTTP_${result.status}: 사이트 보안 확인 응답")
            } catch (error: TimeoutCancellationException) {
                lastFailure = error
            } catch (error: CancellationException) {
                resetSession()
                throw error
            } catch (error: Throwable) {
                lastFailure = error
            }
            resetSession()
            if (attempt + 1 < MAX_SESSION_ATTEMPTS) delay(REBOOT_DELAY_MILLIS)
        }
        val suffix = if (lastStatus > 0) " (HTTP_$lastStatus)" else ""
        throw IllegalStateException(
            "사이트 페이지를 브라우저 세션으로 불러오지 못했습니다.$suffix",
            lastFailure,
        )
    }

    private fun validatedTarget(url: String): URI {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "HTTPS 페이지가 필요합니다." }
        require(!uri.host.isNullOrBlank()) { "페이지 호스트가 없습니다." }
        require(uri.userInfo == null) { "사용자 정보가 포함된 주소는 사용할 수 없습니다." }
        return uri
    }

    private suspend fun ensureSession(target: URI): BrowserSession {
        if (fixtureResponse != null || fixtureDiagnostic != null) {
            require(target.host.equals("fixture.invalid", ignoreCase = true)) {
                "Synthetic WebView hooks are restricted to the reserved fixture host."
            }
        }
        val origin = "https://${target.host.lowercase()}"
        session?.takeIf { it.origin == origin }?.let { return it }
        resetSession()
        val allowedHosts = allowedHostsFor(target.host.lowercase())
        val navigation = NavigationState()
        val webView = createWebView(allowedHosts, navigation)
        val created = BrowserSession(origin, allowedHosts, webView, navigation)
        session = created
        if (target.host.lowercase() in OFFICIAL_HOSTS) {
            bootstrap(created)
        }
        return created
    }

    private fun allowedHostsFor(host: String): Set<String> =
        if (host in OFFICIAL_HOSTS) OFFICIAL_HOSTS else setOf(host)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun createWebView(allowedHosts: Set<String>, navigation: NavigationState): WebView = withContext(Dispatchers.Main.immediate) {
        WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = userAgent
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    fixtureResponse?.invoke(request) ?: super.shouldInterceptRequest(view, request)

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    fixtureDiagnostic?.invoke("started url=${diagnosticUrl(url)}")
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    fixtureDiagnostic?.invoke("finished url=${diagnosticUrl(url)}")
                    super.onPageFinished(view, url)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    fixtureDiagnostic?.invoke("load_error code=${error.errorCode} main=${request.isForMainFrame} url=${diagnosticUrl(request.url.toString())}")
                    if (isCurrentMainFrame(request, allowedHosts, navigation)) {
                        navigation.loadError = error.errorCode
                    }
                    super.onReceivedError(view, request, error)
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    fixtureDiagnostic?.invoke("http_error status=${response.statusCode} main=${request.isForMainFrame} url=${diagnosticUrl(request.url.toString())}")
                    if (isCurrentMainFrame(request, allowedHosts, navigation)) {
                        navigation.httpStatus = response.statusCode
                    }
                    super.onReceivedHttpError(view, request, response)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val uri = request.url
                    return !uri.scheme.equals("https", ignoreCase = true) ||
                        uri.host.orEmpty().lowercase() !in allowedHosts
                }
            }
        }
    }

    private suspend fun bootstrap(current: BrowserSession) {
        withContext(Dispatchers.Main.immediate) {
            current.hasNavigated = true
            current.webView.loadUrl("${current.origin}/")
        }
        withTimeout(BOOTSTRAP_TIMEOUT_MILLIS) {
            while (true) {
                val snapshot = pageSnapshot(current.webView)
                if (
                    snapshot != null &&
                    snapshot.optString("ready") in READY_STATES &&
                    snapshot.optString("title") != CHALLENGE_TITLE &&
                    !looksLikeChallenge(snapshot.optString("title"), snapshot.optString("text")) &&
                    isAllowedUrl(current, snapshot.optString("url"))
                ) {
                    return@withTimeout
                }
                delay(POLL_MILLIS)
            }
        }
    }

    private suspend fun fetchInSession(
        current: BrowserSession,
        url: String,
        referer: String?,
    ): PageResult = withTimeout(FETCH_TIMEOUT_MILLIS) {
        val requestedPath = URI(url).path.orEmpty()
        val isSearchRequest = requestedPath == "/bbs/search.php" || requestedPath == "/bbs/tag.php"
        val isFirstNavigation = !current.hasNavigated
        val previousTimeOrigin = if (isFirstNavigation || !isSearchRequest) {
            0.0
        } else {
            pageSnapshot(current.webView)?.optDouble("timeOrigin", 0.0) ?: 0.0
        }
        val headers = referer?.takeIf { isAllowedUrl(current, it) }?.let { mapOf("Referer" to it) }.orEmpty()
        withContext(Dispatchers.Main.immediate) {
            current.hasNavigated = true
            current.navigation.begin(url)
            current.webView.loadUrl(url, headers)
        }
        var completed: PageResult? = null
        while (completed == null) {
            navigationFailure(current)?.let { return@withTimeout it }
            val snapshot = pageSnapshot(current.webView)
            fixtureDiagnostic?.invoke(
                "snapshot ready=${snapshot?.optString("ready")} url=${diagnosticUrl(snapshot?.optString("url"))} " +
                    "requested=${snapshot?.let { isRequestedDocument(current.allowedHosts, url, it.optString("url")) }} " +
                    "timeOrigin=${snapshot?.optDouble("timeOrigin", 0.0)} previous=$previousTimeOrigin first=$isFirstNavigation " +
                    "dcl=${snapshot?.optBoolean("domContentLoaded")} searchRows=${snapshot?.optBoolean("hasSearchRows")} tagRows=${snapshot?.optBoolean("hasTagRows")}",
            )
            if (
                snapshot != null &&
                isUsableApplicationDocument(
                    snapshot.optString("ready"),
                    snapshot.optDouble("timeOrigin", 0.0),
                    previousTimeOrigin,
                    snapshot.optBoolean("domContentLoaded"),
                    requestedPath,
                    when (requestedPath) {
                        "/bbs/search.php" -> snapshot.optBoolean("hasSearchRows")
                        "/bbs/tag.php" -> snapshot.optBoolean("hasTagRows")
                        else -> false
                    },
                    isFirstNavigation,
                ) &&
                isRequestedDocument(current.allowedHosts, url, snapshot.optString("url"))
            ) {
                val body = pageHtml(current.webView)
                if (body != null) {
                    check(body.length <= MAX_BODY_CHARS) { "페이지 응답이 안전 크기 상한을 초과했습니다." }
                    val contentReady = withContext(Dispatchers.Default) {
                        hasReadyApplicationSearchContent(
                            snapshot.optString("ready"), body, url, hasUsableSearchResults,
                        )
                    }
                    if (contentReady) {
                        completed = navigationFailure(current)
                            ?: PageResult(200, snapshot.optString("url"), body)
                    }
                }
            }
            if (completed == null) delay(POLL_MILLIS)
        }
        completed
    }

    private fun isCurrentMainFrame(
        request: WebResourceRequest,
        allowedHosts: Set<String>,
        navigation: NavigationState,
    ): Boolean = request.isForMainFrame &&
        navigation.requestedUrl?.let {
            isRequestedDocument(allowedHosts, it, request.url.toString())
        } == true

    private suspend fun navigationFailure(current: BrowserSession): PageResult? =
        withContext(Dispatchers.Main.immediate) {
            current.navigation.loadError?.let { throw IllegalStateException("WEBVIEW_ERROR_$it") }
            current.navigation.httpStatus.takeIf { it >= 400 }?.let {
                PageResult(it, current.navigation.requestedUrl.orEmpty(), "")
            }
        }

    private fun isRequestedDocument(
        allowedHosts: Set<String>,
        requestedUrl: String,
        observedUrl: String,
    ): Boolean = runCatching {
        val requested = Uri.parse(requestedUrl)
        val observed = Uri.parse(observedUrl)
        if (
            !observed.scheme.equals("https", ignoreCase = true) ||
            observed.host.orEmpty().lowercase() !in allowedHosts ||
            requested.path.orEmpty() != observed.path.orEmpty()
        ) {
            return@runCatching false
        }
        val requestedNames = requested.queryParameterNames
        val observedNames = observed.queryParameterNames
        requestedNames == observedNames &&
            requestedNames.all { name ->
                requested.getQueryParameters(name) == observed.getQueryParameters(name)
            }
    }.getOrDefault(false)

    private suspend fun pageHtml(webView: WebView): String? {
        val raw = evaluate(webView, "document.documentElement ? document.documentElement.outerHTML : null")
        return decodeEvaluateString(raw)
    }

    private suspend fun pageSnapshot(webView: WebView): JSONObject? {
        val raw = evaluate(
            webView,
            """JSON.stringify({
                title: document.title || "",
                url: location.href,
                ready: document.readyState,
                timeOrigin: performance.timeOrigin || 0,
                domContentLoaded: (performance.getEntriesByType("navigation")[0] || {}).domContentLoadedEventEnd > 0,
                hasSearchRows: !!document.querySelector("#at-main .search-media .media"),
                hasTagRows: !!document.querySelector(".tagbox-media .media, .post-wrap .media"),
                text: (document.body ? document.body.innerText : "").slice(0, 500)
            })""",
        )
        return decodeEvaluateString(raw)?.takeIf { it != "null" }?.let(::JSONObject)
    }

    private suspend fun evaluate(webView: WebView, script: String): String =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                webView.evaluateJavascript(script) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            }
        }

    private fun decodeEvaluateString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return when (val value = runCatching { JSONTokener(raw).nextValue() }.getOrNull()) {
            is String -> value
            null -> null
            else -> value.toString()
        }
    }

    private fun isChallenge(result: PageResult): Boolean =
        result.status == 403 || looksLikeChallenge("", result.body)

    private fun looksLikeChallenge(title: String, body: String): Boolean {
        val sample = (title + "\n" + body.take(CHALLENGE_SCAN_CHARS)).lowercase()
        return "just a moment" in sample ||
            "performing security verification" in sample ||
            "/cdn-cgi/challenge-platform/" in sample
    }

    private fun requireAllowedFinalUrl(current: BrowserSession, url: String) {
        check(isAllowedUrl(current, url)) { "허용되지 않은 사이트로 이동했습니다." }
    }

    private fun isAllowedUrl(current: BrowserSession, url: String): Boolean = runCatching {
        val uri = Uri.parse(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.orEmpty().lowercase() in current.allowedHosts
    }.getOrDefault(false)

    private fun diagnosticUrl(url: String?): String = runCatching {
        val uri = URI(url.orEmpty())
        "${uri.scheme}://${uri.host}${uri.path.orEmpty()}"
    }.getOrDefault("unavailable")

    internal suspend fun close() = mutex.withLock { resetSession() }

    private suspend fun resetSession() {
        val old = session
        session = null
        if (old != null) {
            withContext(Dispatchers.Main.immediate) {
                old.webView.stopLoading()
                old.webView.loadUrl("about:blank")
                old.webView.destroy()
            }
        }
    }

    companion object {
        private val OFFICIAL_HOSTS = setOf("01.avsee.is", "02.avsee.is")
        private val READY_STATES = setOf("interactive", "complete")
        private const val CHALLENGE_TITLE = "Just a moment..."
        private const val MAX_SESSION_ATTEMPTS = 2
        private const val MAX_BODY_CHARS = 2_000_000
        private const val CHALLENGE_SCAN_CHARS = 12_000
        private const val BOOTSTRAP_TIMEOUT_MILLIS = 50_000L
        private const val FETCH_TIMEOUT_MILLIS = 30_000L
        private const val POLL_MILLIS = 250L
        private const val REBOOT_DELAY_MILLIS = 250L
    }
}
