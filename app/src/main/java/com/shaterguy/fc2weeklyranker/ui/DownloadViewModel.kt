package com.shaterguy.fc2weeklyranker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shaterguy.fc2weeklyranker.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = AppGraph.repository
    private val dao = AppGraph.database.downloadDao()

    val activeDownloads = dao.activeDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val completedDownloads = dao.completedDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun pause(videoId: String) {
        viewModelScope.launch { repo.pauseDownload(videoId) }
    }

    fun resume(videoId: String) {
        viewModelScope.launch { repo.queueDownload(videoId) }
    }

    fun cancel(videoId: String) {
        viewModelScope.launch { repo.stopDownload(videoId) }
    }

    fun deleteHistory(videoId: String) {
        viewModelScope.launch { dao.deleteCompletedHistory(videoId) }
    }
}
