package com.shaterguy.fc2weeklyranker.ui

import com.shaterguy.fc2weeklyranker.domain.ContentMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagePrefetchCoordinatorTest {
    @Test
    fun `successful adjacent prefetch is consumed once for the same mode`() = runTest {
        val calls = mutableListOf<Pair<ContentMode, Int>>()
        val coordinator = PagePrefetchCoordinator(this) { mode, page -> calls += mode to page }

        coordinator.start(ContentMode.FC2, 1)

        assertTrue(coordinator.consume(ContentMode.FC2, 1))
        assertFalse(coordinator.consume(ContentMode.FC2, 1))
        assertEquals(listOf(ContentMode.FC2 to 1), calls)
    }

    @Test
    fun `prefetch from another mode is never consumed`() = runTest {
        val coordinator = PagePrefetchCoordinator(this) { _, _ -> Unit }

        coordinator.start(ContentMode.FC2, 2)

        assertFalse(coordinator.consume(ContentMode.JAV, 2))
        assertTrue(coordinator.consume(ContentMode.FC2, 2))
    }

    @Test
    fun `failed prefetch falls back to foreground load`() = runTest {
        val coordinator = PagePrefetchCoordinator(this) { _, _ -> error("network") }

        coordinator.start(ContentMode.JAV, 2)

        assertFalse(coordinator.consume(ContentMode.JAV, 2))
    }
}
