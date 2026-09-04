package com.shaterguy.fc2weeklyranker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundRetryCoordinatorTest {
    @Test
    fun `background refresh failure is consumed once on foreground`() {
        val coordinator = ForegroundRetryCoordinator()
        coordinator.onForeground()
        val action = coordinator.actionStarted()
        val intent = RetryIntent.Refresh(1234L, 7L, 8L, 9L)

        coordinator.onBackground()
        assertNull(coordinator.failed(intent, action))
        assertSame(intent, coordinator.onForeground())
        assertNull(coordinator.onForeground())
    }

    @Test
    fun `return before refresh failure still consumes missed edge exactly once`() {
        val coordinator = ForegroundRetryCoordinator()
        coordinator.onForeground()
        val action = coordinator.actionStarted()
        val intent = RetryIntent.Refresh(1234L, 9L, 11L, 10L)

        coordinator.onBackground()
        assertNull(coordinator.onForeground())
        assertSame(intent, coordinator.failed(intent, action))
        assertNull(coordinator.onForeground())
        assertEquals(1234L, intent.targetAnchorMillis)
    }

    @Test
    fun `foreground only refresh failure never becomes a future retry`() {
        val coordinator = ForegroundRetryCoordinator()
        coordinator.onForeground()
        val action = coordinator.actionStarted()
        val intent = RetryIntent.Refresh(1234L, 1L, 2L, 3L)

        assertNull(coordinator.failed(intent, action))
        coordinator.onBackground()
        assertNull(coordinator.onForeground())
    }

    @Test
    fun `invalidate rejects a late refresh failure and clears pending work`() {
        val coordinator = ForegroundRetryCoordinator()
        coordinator.onForeground()
        val oldAction = coordinator.actionStarted()
        coordinator.onBackground()
        coordinator.invalidate()
        val intent = RetryIntent.Refresh(1234L, 1L, 2L, 3L)

        assertNull(coordinator.failed(intent, oldAction))
        assertNull(coordinator.onForeground())
    }

    @Test
    fun `single UI sequence rejects cross operation stale error and loading writes`() {
        val tracker = LatestOperationTracker()
        var message: String? = "old"
        var loading = true
        val general = tracker.next()
        val refresh = tracker.next()

        if (tracker.isLatest(refresh)) {
            message = null
            loading = false
        }
        if (tracker.isLatest(general)) {
            message = "stale general failure"
            loading = true
        }

        assertNull(message)
        assertFalse(loading)

        val oldRefresh = tracker.next()
        val newerGeneral = tracker.next()
        assertFalse(tracker.isLatest(oldRefresh))
        assertTrue(tracker.isLatest(newerGeneral))
    }

    @Test
    fun `init token captured first cannot supersede a later user refresh`() {
        val tracker = LatestOperationTracker()
        val init = tracker.next()
        val refresh = tracker.next()

        assertFalse(tracker.isLatest(init))
        assertTrue(tracker.isLatest(refresh))
    }
}
