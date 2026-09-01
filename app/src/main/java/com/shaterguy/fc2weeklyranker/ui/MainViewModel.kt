package com.shaterguy.fc2weeklyranker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shaterguy.fc2weeklyranker.AppGraph
import com.shaterguy.fc2weeklyranker.repo.AppRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VideoSyncResult(
    val refreshStartedAtEpochMillis: Long,
    val hasActiveMedia: Boolean,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = AppGraph.repository
    private val page = MutableStateFlow(0)
    private val localAnchor = MutableStateFlow<Long?>(null)
    private val mutableMessage = MutableStateFlow<String?>(null)
    private val loading = MutableStateFlow(false)
    private val probeRegistrationJobs = mutableMapOf<String, MutableList<Job>>()
    private val pagePrefetch = PagePrefetchCoordinator(viewModelScope, repo::ensurePage)

    val pageIndex = page.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val message = mutableMessage.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val isLoading = loading.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val baseUrl = repo.settings.baseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "https://01.avsee.is")
    val anchorEpochMillis = combine(repo.settings.anchorEpochMillis, localAnchor) { stored, local -> local ?: stored }
        .filterNotNull()
        .stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())
    val posts = combine(anchorEpochMillis, page) { anchor, index -> AppRepository.snapshotKey(anchor, index) }
        .flatMapLatest(repo::posts)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites = repo.favorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val visitedPostIds = repo.visitedPostIds().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        viewModelScope.launch {
            val anchor = repo.ensureAnchor()
            localAnchor.value = anchor
            if (loadPage(0)) pagePrefetch.start(1)
        }
    }

    fun olderPage() {
        val target = page.value + 1
        page.value = target
        viewModelScope.launch {
            if (loadPage(target)) pagePrefetch.start(target + 1)
        }
    }

    fun newerPage() {
        if (page.value == 0) return
        val target = page.value - 1
        page.value = target
        viewModelScope.launch {
            if (loadPage(target)) pagePrefetch.start(target + 1)
        }
    }

    fun refreshAnchor() {
        viewModelScope.launch {
            pagePrefetch.cancel()
            page.value = 0
            val success = runOperation { localAnchor.value = repo.manualRefresh() }
            if (success) pagePrefetch.start(1)
        }
    }

    fun toggleFavorite(postId: String) {
        viewModelScope.launch { runOperation { repo.toggleFavorite(postId) } }
    }

    fun openPost(postId: String) {
        viewModelScope.launch { repo.markPostVisited(postId) }
    }

    fun post(postId: String) = repo.post(postId)
    fun isFavorite(postId: String) = repo.isFavorite(postId)
    fun videos(postId: String) = repo.videos(postId)
    fun download(videoId: String) = repo.download(videoId)
    suspend fun loadVideos(postId: String): VideoSyncResult? {
        cancelPendingProbeRegistrations(probeRegistrationJobs.remove(postId))
        val refreshStartedAt = System.currentTimeMillis()
        loading.value = true
        return try {
            repo.loadVideos(postId)
            val current = repo.videos(postId).first()
            VideoSyncResult(
                refreshStartedAtEpochMillis = refreshStartedAt,
                hasActiveMedia = current.any {
                    it.sourceKind == AppRepository.SOURCE_DIRECT || it.sourceKind == AppRepository.SOURCE_IFRAME
                },
            )
        } catch (error: Throwable) {
            mutableMessage.value = "작업 실패: ${safeMessage(error)}"
            null
        } finally {
            loading.value = false
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
        viewModelScope.launch {
            pagePrefetch.cancel()
            loading.value = true
            var refreshed = false
            repo.setBaseUrl(input).onSuccess {
                mutableMessage.value = "사이트 주소를 저장했습니다. 기준시각은 그대로 유지됩니다."
                runCatching { repo.refreshPage(page.value) }
                    .onSuccess { refreshed = true }
                    .onFailure { mutableMessage.value = "주소는 저장했지만 목록 갱신에 실패했습니다: ${safeMessage(it)}" }
            }.onFailure { mutableMessage.value = "주소 저장 실패: ${safeMessage(it)}" }
            loading.value = false
            if (refreshed) pagePrefetch.start(page.value + 1)
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            loading.value = true
            mutableMessage.value = repo.testCurrentBaseUrl().fold(
                { "게시판 연결에 성공했습니다." },
                { "연결 실패: ${safeMessage(it)}" },
            )
            loading.value = false
        }
    }

    fun clearMessage() { mutableMessage.value = null }

    private suspend fun loadPage(pageIndex: Int): Boolean {
        loading.value = true
        val prefetched = pagePrefetch.consume(pageIndex)
        val result = if (prefetched) Result.success(Unit) else runCatching { repo.ensurePage(pageIndex) }
        result.onFailure { mutableMessage.value = "작업 실패: ${safeMessage(it)}" }
        loading.value = false
        return result.isSuccess
    }

    private suspend fun runOperation(block: suspend () -> Unit): Boolean {
        loading.value = true
        val result = runCatching { block() }
        result.onFailure { mutableMessage.value = "작업 실패: ${safeMessage(it)}" }
        loading.value = false
        return result.isSuccess
    }

    private fun safeMessage(error: Throwable): String = error.message?.take(100) ?: error::class.java.simpleName
}

internal fun cancelPendingProbeRegistrations(jobs: Collection<Job>?) {
    jobs?.forEach { it.cancel() }
}
