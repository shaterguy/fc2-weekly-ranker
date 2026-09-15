package com.shaterguy.fc2weeklyranker.ui

import com.shaterguy.fc2weeklyranker.domain.ContentMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class PagePrefetchCoordinator(
    private val scope: CoroutineScope,
    private val ensurePage: suspend (ContentMode, Int) -> Unit,
) {
    private data class Target(val mode: ContentMode, val pageIndex: Int)

    private var target: Target? = null
    private var job: Job? = null
    private var succeeded = false

    fun start(mode: ContentMode, pageIndex: Int) {
        if (pageIndex < 0) return
        val requested = Target(mode, pageIndex)
        if (target == requested && job?.isActive == true) return
        cancel()
        target = requested
        succeeded = false
        job = scope.launch {
            succeeded = runCatching { ensurePage(mode, pageIndex) }.isSuccess
        }
    }

    suspend fun consume(mode: ContentMode, pageIndex: Int): Boolean {
        val requested = Target(mode, pageIndex)
        if (target != requested) return false
        val current = job ?: return false
        current.join()
        val result = target == requested && succeeded
        if (target == requested) reset()
        return result
    }

    fun cancel() {
        job?.cancel()
        reset()
    }

    private fun reset() {
        job = null
        target = null
        succeeded = false
    }
}
