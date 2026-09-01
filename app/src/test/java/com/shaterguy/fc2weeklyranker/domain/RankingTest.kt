package com.shaterguy.fc2weeklyranker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RankingTest {
    private val anchor = Instant.parse("2026-08-30T04:29:40Z")

    @Test
    fun `page windows are non overlapping seven day blocks in Seoul`() {
        val page0 = windowFor(anchor, 0)
        val page1 = windowFor(anchor, 1)
        assertEquals("2026-08-24", page0.startDate.toString())
        assertEquals("2026-08-30", page0.endDate.toString())
        assertEquals("2026-08-17", page1.startDate.toString())
        assertEquals("2026-08-23", page1.endDate.toString())
        assertTrue(page0.contains(anchor))
        assertFalse(page0.contains(anchor.plusSeconds(1)))
        assertFalse(page1.contains(page0.startInclusive))
    }

    @Test
    fun `daily rate uses Seoul calendar days and floors denominator at one`() {
        val sameDateMorning = Instant.parse("2026-08-29T15:10:00Z")
        val twoCalendarDaysAgo = Instant.parse("2026-08-27T15:10:00Z")
        assertEquals(12.0, dailyRate(anchor, sameDateMorning, 12), 0.0001)
        assertEquals(6.0, dailyRate(anchor, twoCalendarDaysAgo, 12), 0.0001)
    }

    @Test
    fun `same posting date always gets same elapsed day denominator`() {
        val early = Instant.parse("2026-08-28T15:01:00Z")
        val late = Instant.parse("2026-08-29T14:59:00Z")
        assertEquals(10.0, dailyRate(anchor, early, 10), 0.0001)
        assertEquals(10.0, dailyRate(anchor, late, 10), 0.0001)
    }

    @Test
    fun `ranking uses comment daily rate comment count date then stable id`() {
        val items = listOf(
            RankCandidate("a", Instant.parse("2026-08-27T15:00:00Z"), 20, "1"),
            RankCandidate("b", Instant.parse("2026-08-29T01:00:00Z"), 15, "2"),
            RankCandidate("c", Instant.parse("2026-08-29T13:00:00Z"), 15, "3"),
        )
        assertEquals(listOf("c", "b", "a"), rank(anchor, items).map { it.first.value })
    }
}
