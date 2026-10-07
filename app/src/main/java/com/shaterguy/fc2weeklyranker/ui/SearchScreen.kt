package com.shaterguy.fc2weeklyranker.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.shaterguy.fc2weeklyranker.network.RemoteSearchPost
import com.shaterguy.fc2weeklyranker.network.applicationLoginStartUrl
import com.shaterguy.fc2weeklyranker.network.applicationPageOrigin
import com.shaterguy.fc2weeklyranker.network.configuredApplicationOrigin
import com.shaterguy.fc2weeklyranker.network.isApplicationNavigationAllowed
import com.shaterguy.fc2weeklyranker.network.rejectUnconfiguredMainFrame

internal enum class SearchSortMode { FIRST_SEEN, OCCURRENCE }

internal fun sortSearchResults(
    results: List<RemoteSearchPost>,
    mode: SearchSortMode,
): List<RemoteSearchPost> = when (mode) {
    SearchSortMode.FIRST_SEEN -> results
    SearchSortMode.OCCURRENCE -> results.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<RemoteSearchPost>> { it.value.occurrenceCount }
                .thenBy { it.index },
        )
        .map { it.value }
}

internal fun isVisitedPost(postId: String, visitedPostIds: Set<String>): Boolean = postId in visitedPostIds

// Bounded diagnostics offer first-party recovery; HTTP 403 alone is not proof of its cause.
internal fun searchNeedsAuthentication(message: String?): Boolean = message != null &&
    listOf("[DOCUMENT/AUTHENTICATION_REQUIRED]", "[DOCUMENT/HTTP_401]", "[DOCUMENT/HTTP_403]")
        .any(message::contains)

@Composable
fun SearchScreen(
    vm: MainViewModel,
    onPost: (RemoteSearchPost, List<String>) -> Unit,
) {
    val mode by vm.selectedContentMode.collectAsState()
    val results by vm.searchResults.collectAsState()
    val loading by vm.isSearchLoading.collectAsState()
    val cancelling by vm.isSearchCancelling.collectAsState()
    val progress by vm.searchProgress.collectAsState()
    val message by vm.searchMessage.collectAsState()
    val openingPostId by vm.searchOpeningPostId.collectAsState()
    val visitedPostIds by vm.visitedPostIds.collectAsState()
    val savedOrigin by vm.savedApplicationOrigin.collectAsState()
    val failedSearchOrigin by vm.failedSearchOrigin.collectAsState()
    val loginSite = savedOrigin?.takeIf { it.mode == mode }
    val loginStartUrl = remember(loginSite) { loginSite?.let { applicationLoginStartUrl(it.baseUrl) } }
    var query by rememberSaveable(mode.sourceKey) { mutableStateOf("") }
    var hasSearched by rememberSaveable(mode.sourceKey) { mutableStateOf(false) }
    // Wait for the saved origin before restoring a prompt's persisted scope.
    var loginPrompt by if (loginSite != null) {
        rememberSaveable(
            mode.sourceKey,
            loginSite,
            stateSaver = Saver<SearchLoginPromptState, List<String>>(
                save = { listOf(mode.sourceKey, loginStartUrl.orEmpty()) + it.savedValue() },
                restore = {
                    if (it.getOrNull(0) == mode.sourceKey && it.getOrNull(1) == loginStartUrl.orEmpty()) {
                        SearchLoginPromptState.restore(it.drop(2))
                    } else SearchLoginPromptState()
                },
            ),
        ) { mutableStateOf(SearchLoginPromptState()) }
    } else {
        remember(mode.sourceKey) { mutableStateOf(SearchLoginPromptState()) }
    }
    val loginFailure = failedSearchOrigin?.takeIf { failure ->
        loginSite != null && failure.mode == mode &&
            configuredApplicationOrigin(failure.baseUrl) == loginSite.baseUrl && failure.message == message
    }
    var sortName by rememberSaveable(mode.sourceKey) { mutableStateOf(SearchSortMode.FIRST_SEEN.name) }
    val sortMode = remember(sortName) {
        runCatching { SearchSortMode.valueOf(sortName) }.getOrDefault(SearchSortMode.FIRST_SEEN)
    }
    val displayedResults = remember(results, sortMode) { sortSearchResults(results, sortMode) }
    val resultIds = remember(displayedResults) { displayedResults.map { it.id } }

    LaunchedEffect(progress?.query, mode) {
        progress?.query?.let { restored ->
            if (query.isBlank() || loading) query = restored
            hasSearched = true
        }
    }

    LaunchedEffect(loginFailure, loading, cancelling, loginSite) {
        loginPrompt = loginPrompt.observe(
            loginFailure?.requestKey,
            loginFailure?.message,
            loading || cancelling,
            loginStartUrl != null,
        )
    }

    if (loginPrompt.visible && loginStartUrl != null && loginSite != null) {
        key(loginStartUrl, loginSite.generation) {
            ApplicationLoginDialog(
                startUrl = loginStartUrl,
                onDismiss = {
                    if (vm.isCurrentApplicationOrigin(loginSite)) {
                        loginPrompt = loginPrompt.dismiss()
                        CookieManager.getInstance().flush()
                    }
                },
                onDone = {
                    if (vm.isCurrentApplicationOrigin(loginSite)) {
                        val (next, retrySearch) = loginPrompt.complete()
                        loginPrompt = next
                        if (retrySearch) {
                            CookieManager.getInstance().flush()
                            vm.clearSearchMessage()
                            if (query.isNotBlank()) {
                                hasSearched = true
                                vm.searchPosts(query)
                            }
                        }
                    }
                },
            )
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("${mode.sourceKey} 검색", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "사이트 통합검색 전체 결과에서 ${mode.sourceKey} 게시물만 모아 중복을 합산합니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("검색어") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    hasSearched = true
                    vm.searchPosts(query)
                },
                enabled = loginSite != null && query.isNotBlank() && !loading && !cancelling,
            ) { Text("검색") }
        }

        if (loginStartUrl != null) {
            OutlinedButton(
                onClick = {
                    if (loginSite != null && vm.isCurrentApplicationOrigin(loginSite)) {
                        loginPrompt = loginPrompt.open()
                    }
                },
                enabled = !loading && !cancelling,
            ) { Text("사이트 로그인") }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sortMode == SearchSortMode.FIRST_SEEN) {
                Button(
                    onClick = { sortName = SearchSortMode.FIRST_SEEN.name },
                    modifier = Modifier.weight(1f),
                ) { Text("기본순") }
            } else {
                OutlinedButton(
                    onClick = { sortName = SearchSortMode.FIRST_SEEN.name },
                    modifier = Modifier.weight(1f),
                ) { Text("기본순") }
            }
            if (sortMode == SearchSortMode.OCCURRENCE) {
                Button(
                    onClick = { sortName = SearchSortMode.OCCURRENCE.name },
                    modifier = Modifier.weight(1f),
                ) { Text("중복횟수순") }
            } else {
                OutlinedButton(
                    onClick = { sortName = SearchSortMode.OCCURRENCE.name },
                    modifier = Modifier.weight(1f),
                ) { Text("중복횟수순") }
            }
        }

        if (loading) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.padding(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("검색 중: ${progress?.query ?: query}")
                    val current = progress
                    if (current != null && current.totalPages > 0) {
                        Text(
                            "${current.completedPages.coerceAtMost(current.totalPages)} / ${current.totalPages}페이지 확인",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("검색 범위를 확인하는 중…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = vm::cancelSearch, enabled = !cancelling) {
                    Text(if (cancelling) "중지 중…" else "중지")
                }
            }
        }
        if (message != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = vm::clearSearchMessage) { Text(message!!) }
            }
        }
        if (!loading && hasSearched && message == null && displayedResults.isEmpty()) {
            Text("검색 결과가 없습니다.")
        }
        if (!loading && displayedResults.isNotEmpty()) {
            Text("${mode.sourceKey} 게시물 ${displayedResults.size}건", fontWeight = FontWeight.SemiBold)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(displayedResults) { post ->
                val visited = isVisitedPost(post.id, visitedPostIds)
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = openingPostId == null) { onPost(post, resultIds) }
                        .semantics {
                            contentDescription = if (visited) {
                                "방문한 검색 결과 게시물: ${post.title}"
                            } else {
                                "검색 결과 게시물: ${post.title}"
                            }
                        },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            post.title,
                            fontWeight = FontWeight.SemiBold,
                            color = if (visited) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        )
                        Text("검색 결과 출현 ${post.occurrenceCount}회", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (openingPostId == post.id) "게시물을 여는 중…" else "앱에서 게시물 보기",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ApplicationLoginDialog(
    startUrl: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val loginOrigin = remember(startUrl) { requireNotNull(applicationPageOrigin(startUrl)) }
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("사이트 로그인", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "사이트가 제공하는 로그인 화면입니다. 앱은 비밀번호를 읽거나 저장하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? = rejectUnconfiguredMainFrame(loginOrigin, request)
                                    ?: super.shouldInterceptRequest(view, request)

                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    if (!request.isForMainFrame) return false
                                    return !isApplicationNavigationAllowed(loginOrigin, request.url.toString())
                                }
                            }
                            loadUrl(startUrl)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(500.dp),
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.destroy()
                    },
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("닫기")
                    }
                    Button(onClick = onDone, modifier = Modifier.weight(1f)) {
                        Text("로그인 완료")
                    }
                }
            }
        }
    }
}
