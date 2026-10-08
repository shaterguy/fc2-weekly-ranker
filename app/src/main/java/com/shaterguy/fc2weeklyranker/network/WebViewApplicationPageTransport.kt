package com.shaterguy.fc2weeklyranker.network

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

interface ApplicationPageTransport {
    suspend fun fetch(url: String, referer: String? = null): String

    suspend fun <T> withConnectionValidationOrigin(
        baseUrl: String,
        validate: suspend (ApplicationPageTransport) -> T,
    ): T = validate(this)
}

internal class WebViewApplicationPageTransport(
    context: Context,
    private val userAgent: String? = null,
    private val configuredOrigins: suspend () -> Set<String>,
    private val hasUsableSearchResults: (String, String) -> Boolean = { _, _ -> false },
    private val fixtureResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    private val fixtureDiagnostic: ((String) -> Unit)? = null,
) : ApplicationPageTransport {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var session: BrowserSession? = null

    override suspend fun <T> withConnectionValidationOrigin(
        baseUrl: String,
        validate: suspend (ApplicationPageTransport) -> T,
    ): T = coroutineScope {
        val origin = BaseUrlPolicy.normalize(baseUrl).getOrThrow()
        // A separate browser pins only this validation to the candidate. The application
        // transport and its configured-origin provider never gain temporary authority.
        val candidate = WebViewApplicationPageTransport(
            appContext, userAgent, configuredOrigins = { setOf(origin) },
            hasUsableSearchResults = hasUsableSearchResults,
            fixtureResponse = fixtureResponse, fixtureDiagnostic = fixtureDiagnostic,
        )
        val active = AtomicBoolean(true)
        val lifetime = currentCoroutineContext().job
        val scoped = object : ApplicationPageTransport {
            override suspend fun fetch(url: String, referer: String?): String {
                lifetime.ensureActive()
                check(active.get()) { "주소 연결 검증이 종료되었습니다." }
                require(isApplicationNavigationAllowed(origin, url)) { "검증 중인 사이트의 페이지만 허용됩니다." }
                require(referer == null || isApplicationNavigationAllowed(origin, referer)) {
                    "검증 중인 사이트의 참조 주소만 허용됩니다."
                }
                val html = candidate.fetch(url, referer)
                lifetime.ensureActive()
                check(active.get()) { "주소 연결 검증이 종료되었습니다." }
                return html
            }
        }
        try {
            validate(scoped)
        } finally {
            active.set(false)
            withContext(NonCancellable) { candidate.close() }
        }
    }

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

        fun resetHttpStatus() {
            httpStatus = 200
        }
    }

    private data class PageResult(
        val status: Int,
        val finalUrl: String,
        val body: String,
        val challenge: Boolean = false,
        val httpFailureAfterTimeout: Boolean = false,
    )

    override suspend fun fetch(url: String, referer: String?): String = mutex.withLock {
        val target = validatedTarget(url)
        var lastFailure: Throwable? = null
        repeat(MAX_SESSION_ATTEMPTS) { attempt ->
            var retryFailure = true
            try {
                val current = withPageFailureStage(PageFailureStage.SESSION) { ensureSession(target) }
                val result = withPageFailureStage(PageFailureStage.DOCUMENT) {
                    fetchInSession(current, target.toString(), referer)
                }
                retryFailure = !result.httpFailureAfterTimeout
                val failureReason = classifyApplicationDocumentFailure(
                    result.status,
                    target.toString(),
                    result.challenge || isChallenge(result),
                    configuredOrigin = current.origin,
                )
                if (failureReason == null) {
                    requireAllowedFinalUrl(current, result.finalUrl)
                    return@withLock result.body
                }
                if (failureReason == PageFailureReason.AUTHENTICATION) {
                    throw PageLoadException(PageFailureStage.DOCUMENT, PageFailureReason.AUTHENTICATION)
                }
                val failure = PageLoadException(
                    PageFailureStage.DOCUMENT,
                    failureReason,
                    result.status,
                )
                if (!retryFailure) throw failure
                lastFailure = failure
            } catch (error: TimeoutCancellationException) {
                lastFailure = error
            } catch (error: CancellationException) {
                resetSession()
                throw error
            } catch (error: PageLoadException) {
                if (error.reason == PageFailureReason.AUTHENTICATION || !retryFailure) {
                    resetSession()
                    throw error
                }
                lastFailure = error
            } catch (error: Throwable) {
                lastFailure = error
            }
            resetSession()
            if (attempt + 1 < MAX_SESSION_ATTEMPTS) delay(REBOOT_DELAY_MILLIS)
        }
        throw (lastFailure as? PageLoadException
            ?: PageLoadException(PageFailureStage.DOCUMENT, PageFailureReason.ERROR, cause = lastFailure))
    }

    private fun validatedTarget(url: String): URI {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "HTTPS 페이지가 필요합니다." }
        require(!uri.host.isNullOrBlank()) { "페이지 호스트가 없습니다." }
        require(uri.userInfo == null) { "사용자 정보가 포함된 주소는 사용할 수 없습니다." }
        require(applicationPageOrigin(url) != null) { "허용되는 HTTPS 사이트 주소가 필요합니다." }
        return uri
    }

    private suspend fun ensureSession(target: URI): BrowserSession {
        if (fixtureResponse != null || fixtureDiagnostic != null) {
            require(target.host.equals("fixture.invalid", ignoreCase = true)) {
                "Synthetic WebView hooks are restricted to the reserved fixture host."
            }
        }
        val origin = requireNotNull(applicationPageOrigin(target.toString()))
        val configured = configuredOrigins().mapNotNull(::configuredApplicationOrigin).toSet()
        require(origin in configured) { "설정되지 않은 사이트로 이동할 수 없습니다." }
        session?.takeIf { it.origin == origin }?.let { return it }
        resetSession()
        val allowedHosts = setOf(target.host.lowercase())
        val navigation = NavigationState()
        // Register a created browser before prompt dispatcher cancellation can discard
        // its return value. The fetch cancellation handler can then always destroy it.
        val created = withContext(NonCancellable) {
            val webView = createWebView(allowedHosts, navigation)
            BrowserSession(origin, allowedHosts, webView, navigation).also { session = it }
        }
        currentCoroutineContext().ensureActive()
        if (fixtureResponse == null && fixtureDiagnostic == null) {
            bootstrap(created)
        }
        return created
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun createWebView(allowedHosts: Set<String>, navigation: NavigationState): WebView = withContext(Dispatchers.Main.immediate) {
        WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgent?.takeIf(String::isNotBlank)?.let { settings.userAgentString = it }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val origin = "https://${allowedHosts.single()}"
                    rejectUnconfiguredMainFrame(origin, request)?.let { blocked ->
                        fixtureDiagnostic?.invoke(if (request.method == "POST") "origin_blocked method=POST" else "origin_blocked method=OTHER")
                        return blocked
                    }
                    return fixtureResponse?.invoke(request) ?: super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    // A main-frame HTTP callback can precede onPageStarted. Do not erase
                    // its status merely because the same URL starts loading again.
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
                    val origin = "https://${allowedHosts.single()}"
                    return !isApplicationNavigationAllowed(origin, request.url.toString())
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
    ): PageResult {
        var lastObservedChallenge = false
        return try {
            withTimeout(FETCH_TIMEOUT_MILLIS) {
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
            val snapshot = pageSnapshot(current.webView)
            if (
                snapshot != null &&
                requestedPath == "/bbs/search.php" &&
                isAuthenticationRedirect(snapshot.optString("url"), current.origin)
            ) {
                throw PageLoadException(PageFailureStage.DOCUMENT, PageFailureReason.AUTHENTICATION)
            }
            // HTTP callbacks can arrive while about:blank or the previous document is
            // still ready. Readiness alone must not end this request's 403 deferral.
            val currentDocumentObserved = snapshot != null &&
                isRequestedDocument(current.allowedHosts, url, snapshot.optString("url")) &&
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
                )
            val challenge = snapshot != null && looksLikeChallenge(
                snapshot.optString("title"),
                snapshot.optString("text"),
            )
            lastObservedChallenge = challenge
            val httpStatus = withContext(Dispatchers.Main.immediate) { current.navigation.httpStatus }
            if (
                shouldDeferSearchChallengeHttpFailure(
                    httpStatus,
                    url,
                    currentDocumentObserved,
                    challenge,
                    configuredOrigin = current.origin,
                )
            ) {
                delay(POLL_MILLIS)
                continue
            }
            val canInspectSearchRecovery = snapshot != null && !challenge && currentDocumentObserved &&
                isRequestedDocument(current.allowedHosts, url, snapshot.optString("url")) &&
                shouldResetDeferredSearchChallengeHttpFailure(
                    httpStatus, url, snapshot.optString("url"),
                    configuredOrigin = current.origin,
                )
            if (!canInspectSearchRecovery) {
                val failure = navigationFailure(current)
                if (failure != null) {
                    // A callback can change the status after the first Main-thread read.
                    // Apply the same deferral to that fresh status before ending the search.
                    if (shouldDeferSearchChallengeHttpFailure(
                            failure.status, url, currentDocumentObserved, challenge,
                            configuredOrigin = current.origin,
                        )
                    ) {
                        delay(POLL_MILLIS)
                        continue
                    }
                    return@withTimeout failure
                }
            }
            fixtureDiagnostic?.invoke(
                "snapshot ready=${snapshot?.optString("ready")} url=${diagnosticUrl(snapshot?.optString("url"))} " +
                    "requested=${snapshot?.let { isRequestedDocument(current.allowedHosts, url, it.optString("url")) }} " +
                    "timeOrigin=${snapshot?.optDouble("timeOrigin", 0.0)} previous=$previousTimeOrigin first=$isFirstNavigation " +
                    "dcl=${snapshot?.optBoolean("domContentLoaded")} searchRows=${snapshot?.optBoolean("hasSearchRows")} tagRows=${snapshot?.optBoolean("hasTagRows")}",
            )
            if (
                snapshot != null &&
                !challenge &&
                currentDocumentObserved &&
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
                    // Current-document HTTP success also proves a valid zero-result reload.
                    // Older providers report 0: keep the parsed-results fallback, never infer
                    // success from a generic complete-but-empty error document.
                    val observedStatus = snapshot.optInt("responseStatus", 0)
                    val recoveredSearch = canInspectSearchRecovery && (
                        observedStatus in 200..299 ||
                            (observedStatus == 0 && withContext(Dispatchers.Default) {
                                hasUsableSearchResults(body, url)
                            })
                        )
                    if (recoveredSearch) withContext(Dispatchers.Main.immediate) {
                        current.navigation.resetHttpStatus()
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
        } catch (error: TimeoutCancellationException) {
            // Do not turn a caller cancellation into a page result.
            currentCoroutineContext().ensureActive()
            navigationFailure(current)?.copy(
                challenge = lastObservedChallenge,
                httpFailureAfterTimeout = true,
            ) ?: throw error
        }
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
            current.navigation.loadError?.let {
                throw PageLoadException(PageFailureStage.DOCUMENT, PageFailureReason.WEBVIEW, it)
            }
            current.navigation.httpStatus.takeIf { it >= 400 }?.let {
                PageResult(it, current.navigation.requestedUrl.orEmpty(), "")
            }
        }

    private fun isRequestedDocument(
        allowedHosts: Set<String>,
        requestedUrl: String,
        observedUrl: String,
    ): Boolean = isMatchingApplicationDocument(allowedHosts, requestedUrl, observedUrl)

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
                responseStatus: (performance.getEntriesByType("navigation")[0] || {}).responseStatus || 0,
                domContentLoaded: (performance.getEntriesByType("navigation")[0] || {}).domContentLoadedEventEnd > 0,
                hasSearchRows: !!document.querySelector(".search-results .search-result, #at-main .search-media .media"),
                hasTagRows: !!document.querySelector(".tag-results .tag-result, .tagbox-media .media, .post-wrap .media"),
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
        looksLikeChallenge("", result.body)

    private fun looksLikeChallenge(title: String, body: String): Boolean {
        val sample = (title + "\n" + body.take(CHALLENGE_SCAN_CHARS)).lowercase()
        return "just a moment" in sample ||
            "performing security verification" in sample ||
            "/cdn-cgi/challenge-platform/" in sample
    }

    private fun requireAllowedFinalUrl(current: BrowserSession, url: String) {
        check(isAllowedUrl(current, url)) { "허용되지 않은 사이트로 이동했습니다." }
    }

    private fun isAllowedUrl(current: BrowserSession, url: String): Boolean =
        isApplicationNavigationAllowed(current.origin, url)

    private fun diagnosticUrl(url: String?): String = runCatching {
        val uri = URI(url.orEmpty())
        "${uri.scheme}://${uri.host}${uri.path.orEmpty()}"
    }.getOrDefault("unavailable")

    internal suspend fun close() = mutex.withLock { resetSession() }

    private suspend fun resetSession() {
        val old = session
        session = null
        if (old != null) {
            // Cancellation must not strand a browser after its session reference is cleared.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                old.webView.stopLoading()
                old.webView.loadUrl("about:blank")
                old.webView.destroy()
            }
        }
    }

    companion object {
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
