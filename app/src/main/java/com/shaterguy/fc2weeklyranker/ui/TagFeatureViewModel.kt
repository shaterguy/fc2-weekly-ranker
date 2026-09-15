package com.shaterguy.fc2weeklyranker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.shaterguy.fc2weeklyranker.AppGraph
import com.shaterguy.fc2weeklyranker.data.PostEntity
import com.shaterguy.fc2weeklyranker.domain.ContentMode
import com.shaterguy.fc2weeklyranker.network.RemoteTag
import com.shaterguy.fc2weeklyranker.network.RemoteTagPost
import com.shaterguy.fc2weeklyranker.network.isTransientNetworkError
import com.shaterguy.fc2weeklyranker.repo.AppRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class DetailSyncResult(
    val refreshStartedAtEpochMillis: Long,
    val hasActiveMedia: Boolean,
    val tags: List<RemoteTag>,
)

class TagFeatureViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = AppGraph.repository
    private val source = AppGraph.sourceClient
    private val mutableTagQuery = MutableStateFlow("")
    private val mutableTagResults = MutableStateFlow<List<RemoteTagPost>>(emptyList())
    private val mutableTagMessage = MutableStateFlow<String?>(null)
    private val tagLoading = MutableStateFlow(false)
    private val mutableTagOpeningPostId = MutableStateFlow<String?>(null)
    private val detailLoading = MutableStateFlow(false)
    private val mutableDetailMessage = MutableStateFlow<String?>(null)
    private var currentMode = ContentMode.FC2
    private var modeVersion = 0L
    private var tagSearchVersion = 0L
    private var detailVersion = 0L
    private var tagSearchJob: Job? = null

    val tagQuery = mutableTagQuery.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val tagResults = mutableTagResults.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tagMessage = mutableTagMessage.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val isTagLoading = tagLoading.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val tagOpeningPostId = mutableTagOpeningPostId.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val favoriteTags = repo.settings.javFavoriteTags.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val isDetailLoading = detailLoading.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val detailMessage = mutableDetailMessage.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onContentModeChanged(mode: ContentMode) {
        if (mode == currentMode) return
        currentMode = mode
        modeVersion += 1
        if (mode != ContentMode.JAV) clearTagFeatureState()
    }

    fun searchTagPosts(query: String) {
        if (currentMode != ContentMode.JAV) return
        val term = query.trim()
        if (term.isEmpty()) {
            mutableTagQuery.value = ""
            mutableTagResults.value = emptyList()
            mutableTagMessage.value = null
            tagLoading.value = false
            return
        }

        tagSearchJob?.cancel()
        tagSearchVersion += 1
        val searchVersion = tagSearchVersion
        val startModeVersion = modeVersion
        mutableTagQuery.value = term
        mutableTagResults.value = emptyList()
        mutableTagMessage.value = null
        mutableTagOpeningPostId.value = null
        tagLoading.value = true

        tagSearchJob = viewModelScope.launch {
            try {
                val baseUrl = repo.settings.baseUrl(ContentMode.JAV).first()
                val results = source.searchTagPosts(baseUrl, term).map { post ->
                    post.copy(id = ContentMode.JAV.localPostId(post.id))
                }
                if (!isCurrentTagRequest(searchVersion, startModeVersion)) return@launch
                mutableTagResults.value = results
                mutableTagMessage.value = if (results.isEmpty()) "태그 검색 결과가 없습니다." else null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrentTagRequest(searchVersion, startModeVersion)) {
                    mutableTagMessage.value = "태그 검색 실패: ${safeMessage(error)}"
                }
            } finally {
                if (isCurrentTagRequest(searchVersion, startModeVersion)) tagLoading.value = false
            }
        }
    }

    fun openTagPost(post: RemoteTagPost, onReady: (String) -> Unit) {
        if (currentMode != ContentMode.JAV || mutableTagOpeningPostId.value != null) return
        val searchVersion = tagSearchVersion
        val startModeVersion = modeVersion
        val localId = ContentMode.JAV.localPostId(ContentMode.JAV.remotePostId(post.id))
        mutableTagOpeningPostId.value = localId
        mutableTagMessage.value = null

        viewModelScope.launch {
            try {
                val dao = AppGraph.database.postDao()
                if (dao.byId(localId) == null) {
                    val now = System.currentTimeMillis()
                    val placeholder = PostEntity(
                        id = localId,
                        url = post.url,
                        title = post.title,
                        postedAtEpochMillis = now,
                        recommendationCount = 0,
                        dailyRate = 0.0,
                        snapshotKey = SEARCH_SNAPSHOT_KEY,
                        fetchedAtEpochMillis = now,
                        sourceKey = ContentMode.JAV.sourceKey,
                    )
                    AppGraph.database.withTransaction {
                        if (dao.byId(localId) == null) dao.upsert(listOf(placeholder))
                    }
                }
                if (isCurrentTagRequest(searchVersion, startModeVersion)) onReady(localId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrentTagRequest(searchVersion, startModeVersion)) {
                    mutableTagMessage.value = "게시물 열기 실패: ${safeMessage(error)}"
                }
            } finally {
                if (mutableTagOpeningPostId.value == localId) mutableTagOpeningPostId.value = null
            }
        }
    }

    internal suspend fun loadDetail(postId: String): DetailSyncResult? {
        detailVersion += 1
        val operation = detailVersion
        val refreshStartedAt = System.currentTimeMillis()
        detailLoading.value = true
        mutableDetailMessage.value = null
        return try {
            val detail = repo.loadVideos(postId) ?: error("게시물 정보를 찾을 수 없습니다.")
            val mode = contentModeForLocalPostId(postId)
            val dao = AppGraph.database.postDao()
            val stored = dao.byId(postId)
            if (stored?.snapshotKey == SEARCH_SNAPSHOT_KEY) {
                dao.upsert(listOf(searchPostEntity(detail, System.currentTimeMillis(), mode)))
            }
            val current = repo.videos(postId).first()
            if (operation == detailVersion) mutableDetailMessage.value = null
            DetailSyncResult(
                refreshStartedAtEpochMillis = refreshStartedAt,
                hasActiveMedia = current.any {
                    it.sourceKind == AppRepository.SOURCE_DIRECT || it.sourceKind == AppRepository.SOURCE_IFRAME
                },
                tags = if (mode == ContentMode.JAV) detail.tags else emptyList(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (operation == detailVersion) mutableDetailMessage.value = "작업 실패: ${safeMessage(error)}"
            null
        } finally {
            if (operation == detailVersion) detailLoading.value = false
        }
    }

    fun toggleFavoriteTag(query: String) {
        if (currentMode != ContentMode.JAV || query.isBlank()) return
        viewModelScope.launch { repo.settings.toggleJavFavoriteTag(query) }
    }

    fun clearTagMessage() {
        mutableTagMessage.value = null
    }

    fun clearDetailMessage() {
        mutableDetailMessage.value = null
    }

    private fun clearTagFeatureState() {
        tagSearchVersion += 1
        tagSearchJob?.cancel()
        tagSearchJob = null
        mutableTagQuery.value = ""
        mutableTagResults.value = emptyList()
        mutableTagMessage.value = null
        mutableTagOpeningPostId.value = null
        tagLoading.value = false
    }

    private fun isCurrentTagRequest(searchVersion: Long, startModeVersion: Long): Boolean =
        currentMode == ContentMode.JAV &&
            modeVersion == startModeVersion &&
            tagSearchVersion == searchVersion

    private fun safeMessage(error: Throwable): String =
        if (isTransientNetworkError(error)) {
            "네트워크 연결이 불안정합니다. 연결을 확인한 뒤 다시 시도해 주세요."
        } else {
            error.message?.take(120) ?: error::class.java.simpleName
        }
}