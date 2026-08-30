package com.shaterguy.fc2weeklyranker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RankingTest {
    private val anchor = Instant.parse("2026-08-30T04:29:40Z")
    @Test fun `page windows are non overlapping seven day blocks in Seoul`() { val page0 = windowFor(anchor, 0); val page1 = windowFor(anchor, 1); assertEquals("2026-08-24", page0.startDate.toString()); assertEquals("2026-08-30", page0.endDate.toString()); assertEquals("2026-08-17", page1.startDate.toString()); assertEquals("2026-08-23", page1.endDate.toString()); assertTrue(page0.contains(anchor)); assertFalse(page0.contains(anchor.plusSeconds(1))); assertFalse(page1.contains(page0.startInclusive)) }
    @Test fun `daily rate floors elapsed time at one day`() { assertEquals(12.0, dailyRate(anchor, anchor.minusSeconds(3600), 12), 0.0001); assertEquals(6.0, dailyRate(anchor, anchor.minusSeconds(172800), 12), 0.0001) }
    @Test fun `ranking uses rate recommendation time then stable id`() { val items = listOf(RankCandidate("a", anchor.minusSeconds(172800), 20, "1"), RankCandidate("b", anchor.minusSeconds(86400), 15, "2"), RankCandidate("c", anchor.minusSeconds(86400), 15, "3")); assertEquals(listOf("c", "b", "a"), rank(anchor, items).map { it.first.value }) }
}
