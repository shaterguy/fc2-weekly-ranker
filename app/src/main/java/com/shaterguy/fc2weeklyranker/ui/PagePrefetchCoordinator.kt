package com.shaterguy.fc2weeklyranker.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class PagePrefetchCoordinator(
    private val scope: CoroutineScope,
    private val ensurePage: suspend (Int) -> Unit,
) {
    private var targetPage: Int? = null
    private var job: Job? = null
    private var succeeded = false

    fun start(pageIndex: Int) {
        if (pageIndex < 0) return
        if (targetPage == pageIndex && job?.isActive == true) return
        cancel()
        targetPage = pageIndex
        succeeded = false
        job = scope.launch {
            succeeded = runCatching { ensurePage(pageIndex) }.isSuccess
        }
    }

    suspend fun consume(pageIndex: Int): Boolean {
        if (targetPage != pageIndex) return false
        val current = job ?: return false
        current.join()
        val result = targetPage == pageIndex && succeeded
        if (targetPage == pageIndex) {
            targetPage = null
            job = null
            succeeded = false
        }
        return result
    }

    fun cancel() {
        job?.cancel()
        job = null
        targetPage = null
        succeeded = false
    }
}
