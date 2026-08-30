package com.shaterguy.fc2weeklyranker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shaterguy.fc2weeklyranker.AppGraph
import com.shaterguy.fc2weeklyranker.repo.AppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = AppGraph.repository
    private val page = MutableStateFlow(0)
    private val localAnchor = MutableStateFlow<Long?>(null)
    private val mutableMessage = MutableStateFlow<String?>(null)
    private val loading = MutableStateFlow(false)

    val pageIndex = page.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val message = mutableMessage.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val isLoading = loading.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val baseUrl = repo.settings.baseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "https://01.avsee.is")
    val anchorEpochMillis = combine(repo.settings.anchorEpochMillis, localAnchor) { stored, local -> local ?: stored }.filterNotNull().stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())
    val posts = combine(anchorEpochMillis, page) { anchor, index -> anchor to index }
        .flatMapLatest { (anchor, index) -> repo.posts(anchor, index) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites = repo.favorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { viewModelScope.launch { val anchor = repo.ensureAnchor(); localAnchor.value = anchor; runOperation { repo.ensurePage(0) } } }
    fun olderPage() { page.value += 1; viewModelScope.launch { runOperation { repo.ensurePage(page.value) } } }
    fun newerPage() { if (page.value == 0) return; page.value -= 1; viewModelScope.launch { runOperation { repo.ensurePage(page.value) } } }
    fun refreshAnchor() { viewModelScope.launch { page.value = 0; runOperation { localAnchor.value = repo.manualRefresh() } } }
    fun toggleFavorite(postId: String) { viewModelScope.launch { runOperation { repo.toggleFavorite(postId) } } }
    fun videos(postId: String) = repo.videos(postId)
    fun download(videoId: String) = repo.download(videoId)
    fun loadVideos(postId: String) { viewModelScope.launch { runOperation { repo.loadVideos(postId) } } }
    fun registerProbedVideo(postId: String, wrapperVideoId: String, url: String, referer: String, ordinal: Int) {
        viewModelScope.launch { repo.registerProbedVideo(postId, wrapperVideoId, url, referer, ordinal) }
    }
    fun queueDownload(videoId: String) {
        viewModelScope.launch {
            runCatching { repo.queueDownload(videoId) }.onFailure { mutableMessage.value = "다운로드 요청 실패: ${safeMessage(it)}" }
        }
    }
    fun saveBaseUrl(input: String) {
        viewModelScope.launch {
            loading.value = true
            repo.setBaseUrl(input).onSuccess { mutableMessage.value = "사이트 주소를 저장했습니다. 기준시각은 그대로 유지됩니다."; runCatching { repo.refreshPage(page.value) }.onFailure { mutableMessage.value = "주소는 저장했지만 목록 갱신에 실패했습니다: ${safeMessage(it)}" } }.onFailure { mutableMessage.value = "주소 저장 실패: ${safeMessage(it)}" }
            loading.value = false
        }
    }
    fun testConnection() { viewModelScope.launch { loading.value = true; mutableMessage.value = repo.testCurrentBaseUrl().fold({ "게시판 연결에 성공했습니다." }, { "연결 실패: ${safeMessage(it)}" }); loading.value = false } }
    fun clearMessage() { mutableMessage.value = null }
    private suspend fun runOperation(block: suspend () -> Unit) { loading.value = true; runCatching { block() }.onFailure { mutableMessage.value = "작업 실패: ${safeMessage(it)}" }; loading.value = false }
    private fun safeMessage(error: Throwable): String = error.message?.take(100) ?: error::class.java.simpleName
}
