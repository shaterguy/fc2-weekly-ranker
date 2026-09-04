package com.shaterguy.fc2weeklyranker.repo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRepositoryRefreshTest {
    @Test
    fun `failed refresh does not commit and same request retries one target once`() = runTest {
        val guard = LatestRefreshCommitGuard()
        val token = guard.next()
        val target = 123456789L
        val commits = mutableListOf<Long>()
        var fail = true

        val first = runCatching {
            refreshThenCommitLatestAnchor(
                targetAnchorMillis = target,
                requestToken = token,
                refresh = {
                    if (fail) error("network")
                },
                commitIfLatest = { anchor, request ->
                    guard.commitIfLatest(request) { commits += anchor }
                },
            )
        }

        assertTrue(first.isFailure)
        assertTrue(commits.isEmpty())

        fail = false
        val committed = refreshThenCommitLatestAnchor(
            targetAnchorMillis = target,
            requestToken = token,
            refresh = { _ -> },
            commitIfLatest = { anchor, request ->
                guard.commitIfLatest(request) { commits += anchor }
            },
        )

        assertTrue(committed)
        assertEquals(listOf(target), commits)
    }

    @Test
    fun `older overlapping refresh cannot commit after a newer request is issued`() = runTest {
        val guard = LatestRefreshCommitGuard()
        val oldToken = guard.next()
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val commits = mutableListOf<Long>()

        val old = async {
            refreshThenCommitLatestAnchor(
                targetAnchorMillis = 100L,
                requestToken = oldToken,
                refresh = {
                    oldStarted.complete(Unit)
                    releaseOld.await()
                },
                commitIfLatest = { anchor, request ->
                    guard.commitIfLatest(request) { commits += anchor }
                },
            )
        }
        oldStarted.await()

        val newToken = guard.next()
        val newest = async {
            refreshThenCommitLatestAnchor(
                targetAnchorMillis = 200L,
                requestToken = newToken,
                refresh = { _ -> },
                commitIfLatest = { anchor, request ->
                    guard.commitIfLatest(request) { commits += anchor }
                },
            )
        }

        assertTrue(newest.await())
        releaseOld.complete(Unit)
        assertFalse(old.await())
        assertEquals(listOf(200L), commits)
    }
}
