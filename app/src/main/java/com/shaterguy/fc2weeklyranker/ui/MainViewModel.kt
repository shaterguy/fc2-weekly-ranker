package com.shaterguy.fc2weeklyranker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.shaterguy.fc2weeklyranker.AppGraph
import com.shaterguy.fc2weeklyranker.data.PostEntity
import com.shaterguy.fc2weeklyranker.network.RemotePost
import com.shaterguy.fc2weeklyranker.network.RemoteSearchPost
import com.shaterguy.fc2weeklyranker.network.isTransientNetworkError
import com.shaterguy.fc2weeklyranker.repo.AppRepository
import com.shaterguy.fc2weeklyranker.search.SearchRecoveryPolicy
import com.shaterguy.fc2weeklyranker.search.SearchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal const val SEARCH_SNAPSHOT_KEY = "search-cache-v1"

data class VideoSyncResult(
    val refreshStartedAtEpochMillis: Long,
    val hasActiveMedia: Boolean,
)

data class SearchProgress(
    val query: String,
    val completedPages: Int,
    val totalPages: Int,
)

internal sealed interface RetryIntent {
    data class Refresh(
        val targetAnchorMillis: Long,
        val refreshToken: Long,
        val repositoryToken: Long,
        val uiToken: Long,
    ) : RetryIntent
}

internal class ForegroundRetryCoordinator {
    private var foreground = false
    private var backgroundEpoch = 0L
    private var actionVersion = 0L
    private var actionStartBackgroundEpoch = 0L
    private var pending: RetryIntent? = null

    fun actionStarted(): Long {
        actionVersion += 1
        actionStartBackgroundEpoch = backgroundEpoch
        pending = null
        return actionVersion
    }

    fun retryStarted(): Long {
        actionStartBackgroundEpoch = backgroundEpoch
        return actionVersion
    }

    fun onBackground() {
        if (foreground) {
            foreground = false
            backgroundEpoch += 1
        }
    }

    fun onForeground(): RetryIntent? {
        if (foreground) return null
        foreground = true
        return pending.also { pending = null }
    }

    fun failed(intent: RetryIntent, startedActionVersion: Long): RetryIntent? {
        if (startedActionVersion != actionVersion) return null
        if (backgroundEpoch <= actionStartBackgroundEpoch) return null
        pending = intent
        return if (foreground) pending.also { pending = null } else null
    }

    fun invalidate() {
        actionVersion += 1
        actionStartBackgroundEpoch = backgroundEpoch
        pending = null
    }
}

internal class LatestOperationTracker {
    private var latest = 0L

    fun next(): Long {
        latest += 1
        return latest
    }

    fun isLatest(token: Long): Boolean = token == latest
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = AppGraph.repository
    private val searchSource = AppGraph.sourceClient
    private val searchDao = AppGraph.searchDatabase.searchDao()
    private val page = MutableStateFlow(0)
    private val localAnchor = MutableStateFlow<Long?>(null)
    private val mutableMessage = MutableStateFlow<String?>(null)
    private val loading = MutableStateFlow(false)
    private val mutableSearchResults = MutableStateFlow<List<RemoteSearchPost>>(emptyList())
    private val mutableSearchMessage = MutableStateFlow<String?>(null)
    private val searchLoading = MutableStateFlow(false)
    private val searchCancelling = MutableStateFlow(false)
    private val mutableSearchProgress = MutableStateFlow<SearchProgress?>(null)
    private val mutableSearchOpeningPostId = MutableStateFlow<String?>(null)
    private var currentSearchToken: String? = null
    private val retryCoordinator = ForegroundRetryCoordinator()
    private val refreshOperations = LatestOperationTracker()
    private val uiOperations = LatestOperationTracker()
    private val probeRegistrationJobs = mutableMapOf<String, MutableList<Job>>()
    private val pagePrefetch = PagePrefetchCoordinator(viewModelScope, repo::ensurePage)

    val pageIndex = page.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val message = mutableMessage.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val isLoading = loading.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val searchResults = mutableSearchResults.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val searchMessage = mutableSearchMessage.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val isSearchLoading = searchLoading.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val isSearchCancelling = searchCancelling.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val searchProgress = mutableSearchProgress.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val searchOpeningPostId = mutableSearchOpeningPostId.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val baseUrl = repo.settings.baseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "https://01.avsee.is")
    val anchorEpochMillis = combine(repo.settings.anchorEpochMillis, localAnchor) { stored, local -> local ?: stored }
        .filterNotNull()
        .stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())
    val posts = combine(anchorEpochMillis, page) { anchor, index -> anchor to index }
        .flatMapLatest { (anchor, index) -> repo.posts(anchor, index) }
        .map(::rankingVisiblePosts)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites = repo.favorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val visitedPostIds = repo.visitedPostIds().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        val token = beginGeneralOperation()
        viewModelScope.launch { runCatching { repo.recoverDownloads() } }
        viewModelScope.launch {
            combine(searchDao.observeSession(), searchDao.observeResults()) { session, results -> session to results }
                .collect { (session, results) ->
                    currentSearchToken = session?.token
                    mutableSearchResults.value = results.map { it.toRemote() }
                    searchLoading.value = session?.status == SearchStatus.RUNNING
                    if (session?.status != SearchStatus.RUNNING) searchCancelling.value = false
                    mutableSearchProgress.value = session?.let {
                        SearchProgress(
                            query = it.query,
                            completedPages = (it.nextPage - 1).coerceAtLeast(0),
                            totalPages = it.totalPages.coerceAtLeast(0),
                        )
                    }
                    mutableSearchMessage.value = when {
                        session?.status == SearchStatus.FAILED ->
                            "검색 실패: ${session.errorMessage ?: "알 수 없는 오류"}"
                        session?.status == SearchStatus.CANCELLED ->
                            session.errorMessage ?: "검색을 중지했습니다."
                        session?.status == SearchStatus.INTERRUPTED ->
                            session.errorMessage ?: "이전 검색 작업을 종료했습니다. 새 검색을 시작할 수 있습니다."
                        session?.status == SearchStatus.COMPLETED && results.isEmpty() ->
                            "검색 결과가 없습니다."
                        else -> null
                    }
                }
        }
        viewModelScope.launch {
            val anchor = repo.ensureAnchor()
            if (!uiOperations.isLatest(token)) return@launch
            localAnchor.value = anchor
            if (loadPage(0, token)) pagePrefetch.start(1)
        }
    }

    fun onAppForegrounded() {
        retryCoordinator.onForeground()?.let(::retry)
        reconcileSearchSession()
    }

    fun onAppBackgrounded() {
        retryCoordinator.onBackground()
    }

    fun olderPage() {
        val target = page.value + 1
        val token = beginGeneralOperation()
        page.value = target
        viewModelScope.launch {
            if (loadPage(target, token)) pagePrefetch.start(target + 1)
        }
    }

    fun newerPage() {
        if (page.value == 0) return
        val target = page.value - 1
        val token = beginGeneralOperation()
        page.value = target
        viewModelScope.launch {
            if (loadPage(target, token)) pagePrefetch.start(target + 1)
        }
    }

    fun refreshAnchor() {
        pagePrefetch.cancel()
        page.value = 0
        val refreshToken = refreshOperations.next()
        val repositoryToken = repo.beginManualRefresh()
        val uiToken = uiOperations.next()
        val intent = RetryIntent.Refresh(System.currentTimeMillis(), refreshToken, repositoryToken, uiToken)
        loading.value = false
        mutableMessage.value = null
        val startedAt = retryCoordinator.actionStarted()
        launchRefresh(intent, startedAt)
    }

    private fun launchRefresh(intent: RetryIntent.Refresh, startedAt: Long) {
        viewModelScope.launch {
            if (!refreshOperations.isLatest(intent.refreshToken) || !uiOperations.isLatest(intent.uiToken)) return@launch
            loading.value = true
            var retryIntent: RetryIntent? = null
            try {
                val anchor = repo.manualRefresh(intent.targetAnchorMillis, intent.repositoryToken)
                if (refreshOperations.isLatest(intent.refreshToken)) {
                    localAnchor.value = anchor
                }
                if (uiOperations.isLatest(intent.uiToken)) {
                    mutableMessage.value = null
                    pagePrefetch.start(1)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (
                    refreshOperations.isLatest(intent.refreshToken) &&
                    uiOperations.isLatest(intent.uiToken)
                ) {
                    mutableMessage.value = "작업 실패: ${safeMessage(error)}"
                    if (isTransientNetworkError(error)) {
                        retryIntent = retryCoordinator.failed(intent, startedAt)
                    }
                }
            } finally {
                if (uiOperations.isLatest(intent.uiToken)) loading.value = false
            }
            retryIntent?.let(::retry)
        }
    }

    fun searchPosts(query: String) {
        val term = query.trim()
        if (term.isEmpty()) {
            mutableSearchResults.value = emptyList()
            mutableSearchMessage.value = null
            searchLoading.value = false
            return
        }
        if (searchCancelling.value) return

        uiOperations.next()
        loading.value = false
        mutableMessage.value = null
        mutableSearchResults.value = emptyList()
        mutableSearchMessage.value = null
        mutableSearchProgress.value = SearchProgress(term, 0, 0)
        searchLoading.value = true

        val schedule = AppGraph.searchScheduler.start(term, baseUrl.value)
        currentSearchToken = schedule.request.token
        if (!schedule.scheduled) {
            searchLoading.value = false
            mutableSearchMessage.value = "검색 실패: 백그라운드 검색 작업을 시작하지 못했습니다."
        }
        viewModelScope.launch {
            searchDao.prepareSession(schedule.request)
            if (!schedule.scheduled) {
                searchDao.fail(
                    schedule.request.token,
                    "백그라운드 검색 작업을 시작하지 못했습니다.",
                    System.currentTimeMillis(),
                )
            }
        }
    }

    fun cancelSearch() {
        val token = currentSearchToken ?: return
        if (!searchLoading.value || searchCancelling.value) return
        searchCancelling.value = true
        viewModelScope.launch {
            try {
                searchDao.cancel(token, System.currentTimeMillis())
            } finally {
                AppGraph.searchScheduler.cancel(token)
                searchCancelling.value = false
            }
        }
    }

    private fun reconcileSearchSession() {
        viewModelScope.launch {
            val session = searchDao.currentSession() ?: return@launch
            if (session.status != SearchStatus.RUNNING) return@launch
            val active = AppGraph.searchScheduler.isActive(session.token)
            if (SearchRecoveryPolicy.shouldInterrupt(session.status, active)) {
                AppGraph.searchScheduler.cancelActive()
                searchDao.interrupt(session.token, System.currentTimeMillis())
            }
        }
    }

    private fun retry(intent: RetryIntent) {
        when (intent) {
            is RetryIntent.Refresh -> if (
                refreshOperations.isLatest(intent.refreshToken) &&
                uiOperations.isLatest(intent.uiToken)
            ) {
                launchRefresh(intent, retryCoordinator.retryStarted())
            }
        }
    }

    fun openSearchPost(post: RemoteSearchPost, onReady: (String) -> Unit) {
        if (mutableSearchOpeningPostId.value != null) return
        uiOperations.next()
        loading.value = false
        mutableMessage.value = null
        mutableSearchMessage.value = null
        val searchToken = currentSearchToken
        mutableSearchOpeningPostId.value = post.id
        viewModelScope.launch {
            try {
                ensureSearchPost(post)
                if (searchToken == currentSearchToken) {
                    mutableSearchMessage.value = null
                    onReady(post.id)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (searchToken == currentSearchToken) {
                    mutableSearchMessage.value = "게시물 열기 실패: ${safeMessage(error)}"
                }
            } finally {
                if (mutableSearchOpeningPostId.value == post.id) mutableSearchOpeningPostId.value = null
            }
        }
    }

    fun toggleFavorite(postId: String) {
        val token = beginGeneralOperation()
        viewModelScope.launch { runOperation(token) { repo.toggleFavorite(postId) } }
    }

    fun openPost(postId: String) {
        viewModelScope.launch { repo.markPostVisited(postId) }
    }

    fun post(postId: String) = repo.post(postId)
    fun previousPost(postId: String) = repo.previousPost(postId)
    fun nextPost(postId: String) = repo.nextPost(postId)
    fun isFavorite(postId: String) = repo.isFavorite(postId)
    fun videos(postId: String) = repo.videos(postId)
    fun download(videoId: String) = repo.download(videoId)

    suspend fun loadVideos(postId: String): VideoSyncResult? {
        cancelPendingProbeRegistrations(probeRegistrationJobs.remove(postId))
        val refreshStartedAt = System.currentTimeMillis()
        val token = beginGeneralOperation()
        loading.value = true
        return try {
            mutableSearchResults.value.firstOrNull { it.id == postId }?.let { ensureSearchPost(it) }
            repo.loadVideos(postId)
            val current = repo.videos(postId).first()
            if (uiOperations.isLatest(token)) mutableMessage.value = null
            VideoSyncResult(
                refreshStartedAtEpochMillis = refreshStartedAt,
                hasActiveMedia = current.any {
                    it.sourceKind == AppRepository.SOURCE_DIRECT || it.sourceKind == AppRepository.SOURCE_IFRAME
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (uiOperations.isLatest(token)) mutableMessage.value = "작업 실패: ${safeMessage(error)}"
            null
        } finally {
            if (uiOperations.isLatest(token)) loading.value = false
        }
    }

    fun registerProbedVideo(postId: String, url: String, referer: String, ordinal: Int) {
        val jobs = probeRegistrationJobs.getOrPut(postId) { mutableListOf() }
        jobs.removeAll { it.isCompleted }
        jobs += viewModelScope.launch { repo.registerProbedVideo(postId, url, referer, ordinal) }
    }

    fun queueDownload(videoId: String) { viewModelScope.launch { repo.queueDownload(videoId) } }
    fun pauseDownload(videoId: String) { viewModelScope.launch { repo.pauseDownload(videoId) } }
    fun stopDownload(videoId: String) { viewModelScope.launch { repo.stopDownload(videoId) } }

    fun saveBaseUrl(input: String) {
        val token = beginGeneralOperation()
        viewModelScope.launch {
            if (!uiOperations.isLatest(token)) return@launch
            pagePrefetch.cancel()
            loading.value = true
            var refreshed = false
            try {
                repo.setBaseUrl(input).onSuccess {
                    if (uiOperations.isLatest(token)) {
                        mutableMessage.value = "사이트 주소를 저장했습니다. 기준시각은 그대로 유지됩니다."
                    }
                    runCatching { repo.refreshPage(page.value) }
                        .onSuccess {
                            refreshed = true
                            if (uiOperations.isLatest(token)) mutableMessage.value = null
                        }
                        .onFailure {
                            if (uiOperations.isLatest(token)) {
                                mutableMessage.value = "주소는 저장했지만 목록 갱신에 실패했습니다: ${safeMessage(it)}"
                            }
                        }
                }.onFailure {
                    if (uiOperations.isLatest(token)) mutableMessage.value = "주소 저장 실패: ${safeMessage(it)}"
                }
            } finally {
                if (uiOperations.isLatest(token)) loading.value = false
            }
            if (refreshed && uiOperations.isLatest(token)) pagePrefetch.start(page.value + 1)
        }
    }

    fun testConnection() {
        val token = beginGeneralOperation()
        viewModelScope.launch {
            if (!uiOperations.isLatest(token)) return@launch
            loading.value = true
            try {
                val result = repo.testCurrentBaseUrl()
                if (uiOperations.isLatest(token)) {
                    mutableMessage.value = result.fold(
                        { "게시판 연결에 성공했습니다." },
                        { "연결 실패: ${safeMessage(it)}" },
                    )
                }
            } finally {
                if (uiOperations.isLatest(token)) loading.value = false
            }
        }
    }

    fun clearMessage() {
        uiOperations.next()
        loading.value = false
        retryCoordinator.invalidate()
        mutableMessage.value = null
    }

    fun clearSearchMessage() {
        mutableSearchMessage.value = null
    }

    private suspend fun ensureSearchPost(post: RemoteSearchPost) {
        val dao = AppGraph.database.postDao()
        if (dao.byId(post.id) != null) return

        val detail = searchSource.loadDetail(post.url)
        check(detail.id == post.id) { "검색 게시물 식별자가 일치하지 않습니다." }
        val candidate = searchPostEntity(detail, System.currentTimeMillis())
        AppGraph.database.withTransaction {
            if (dao.byId(post.id) == null) dao.upsert(listOf(candidate))
        }
    }

    private fun beginGeneralOperation(): Long {
        val token = uiOperations.next()
        loading.value = false
        retryCoordinator.invalidate()
        mutableMessage.value = null
        return token
    }

    private suspend fun loadPage(pageIndex: Int, token: Long): Boolean {
        if (!uiOperations.isLatest(token)) return false
        loading.value = true
        return try {
            val prefetched = pagePrefetch.consume(pageIndex)
            if (!prefetched) repo.ensurePage(pageIndex)
            if (uiOperations.isLatest(token)) mutableMessage.value = null
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (uiOperations.isLatest(token)) mutableMessage.value = "작업 실패: ${safeMessage(error)}"
            false
        } finally {
            if (uiOperations.isLatest(token)) loading.value = false
        }
    }

    private suspend fun runOperation(token: Long, block: suspend () -> Unit): Boolean {
        if (!uiOperations.isLatest(token)) return false
        loading.value = true
        return try {
            block()
            if (uiOperations.isLatest(token)) mutableMessage.value = null
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (uiOperations.isLatest(token)) mutableMessage.value = "작업 실패: ${safeMessage(error)}"
            false
        } finally {
            if (uiOperations.isLatest(token)) loading.value = false
        }
    }

    private fun safeMessage(error: Throwable): String =
        if (isTransientNetworkError(error)) {
            "네트워크 연결이 불안정합니다. 연결을 확인한 뒤 다시 시도해 주세요."
        } else {
            error.message?.take(100) ?: error::class.java.simpleName
        }
}

internal fun searchPostEntity(detail: RemotePost, fetchedAtEpochMillis: Long): PostEntity = PostEntity(
    id = detail.id,
    url = detail.url,
    title = detail.title,
    postedAtEpochMillis = detail.postedAt.toEpochMilli(),
    recommendationCount = 0,
    dailyRate = 0.0,
    snapshotKey = SEARCH_SNAPSHOT_KEY,
    fetchedAtEpochMillis = fetchedAtEpochMillis,
)

internal fun rankingVisiblePosts(posts: List<PostEntity>): List<PostEntity> =
    posts.filterNot { it.snapshotKey == SEARCH_SNAPSHOT_KEY }

internal fun cancelPendingProbeRegistrations(jobs: Collection<Job>?) {
    jobs?.forEach { it.cancel() }
}
