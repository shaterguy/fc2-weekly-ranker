package com.shaterguy.fc2weeklyranker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailPostNavigationTest {
    private val displayedOrder = listOf("rank-9", "rank-2", "rank-5")

    @Test
    fun middlePostUsesDisplayedNeighbors() {
        val neighbors = detailPostNeighbors(displayedOrder, "rank-2")

        assertEquals("rank-9", neighbors.previousId)
        assertEquals("rank-5", neighbors.nextId)
    }

    @Test
    fun nextWrapsFromLastToFirst() {
        val neighbors = detailPostNeighbors(displayedOrder, "rank-5")

        assertEquals("rank-2", neighbors.previousId)
        assertEquals("rank-9", neighbors.nextId)
    }

    @Test
    fun previousWrapsFromFirstToLast() {
        val neighbors = detailPostNeighbors(displayedOrder, "rank-9")

        assertEquals("rank-5", neighbors.previousId)
        assertEquals("rank-2", neighbors.nextId)
    }

    @Test
    fun missingCurrentPostDisablesTraversal() {
        val neighbors = detailPostNeighbors(displayedOrder, "outside")

        assertNull(neighbors.previousId)
        assertNull(neighbors.nextId)
    }

    @Test
    fun singlePostDisablesTraversal() {
        val neighbors = detailPostNeighbors(listOf("only"), "only")

        assertNull(neighbors.previousId)
        assertNull(neighbors.nextId)
    }
}
