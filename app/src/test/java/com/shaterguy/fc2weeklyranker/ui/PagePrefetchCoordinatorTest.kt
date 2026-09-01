package com.shaterguy.fc2weeklyranker.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagePrefetchCoordinatorTest {
    @Test
    fun `successful adjacent prefetch is consumed once`() = runTest {
        val calls = mutableListOf<Int>()
        val coordinator = PagePrefetchCoordinator(this) { page -> calls += page }

        coordinator.start(1)

        assertTrue(coordinator.consume(1))
        assertFalse(coordinator.consume(1))
        assertEquals(listOf(1), calls)
    }

    @Test
    fun `failed prefetch falls back to foreground load`() = runTest {
        val coordinator = PagePrefetchCoordinator(this) { error("network") }

        coordinator.start(2)

        assertFalse(coordinator.consume(2))
    }
}
