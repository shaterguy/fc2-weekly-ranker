package com.shaterguy.fc2weeklyranker.domain

import com.shaterguy.fc2weeklyranker.data.PostEntity
import com.shaterguy.fc2weeklyranker.data.RankObservationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AdaptiveRankingTest {
    private val now = Instant.parse("2026-09-09T00:00:00Z").toEpochMilli()

    @Test
    fun `popularity ranks higher cumulative comments at the same age`() {
        val high = post("high", ageHours = 48, legacyRate = 1.0)
        val low = post("low", ageHours = 48, legacyRate = 20.0)
        val observations = listOf(obs(high, 40), obs(low, 10))

        val ranked = AdaptiveRanking.rank(listOf(low, high), observations, RankingMode.POPULARITY, now)

        assertEquals("high", ranked.first().post.id)
        assertTrue(ranked.first().popularityScore > ranked.last().popularityScore)
    }

    @Test
    fun `trending prefers a rising post over a popular but stagnant post`() {
        val stagnant = post("stagnant", ageHours = 72, legacyRate = 100.0)
        val rising = post("rising", ageHours = 72, legacyRate = 1.0)
        val observations = listOf(
            obs(stagnant, 100, observedHoursAgo = 12),
            obs(stagnant, 100, observedHoursAgo = 0),
            obs(rising, 10, observedHoursAgo = 12),
            obs(rising, 22, observedHoursAgo = 0),
        )

        val ranked = AdaptiveRanking.rank(listOf(stagnant, rising), observations, RankingMode.TRENDING, now)
        val stagnantResult = ranked.first { it.post.id == "stagnant" }
        val risingResult = ranked.first { it.post.id == "rising" }

        assertEquals("rising", ranked.first().post.id)
        assertEquals(0.0, stagnantResult.trendingScore, 0.0001)
        assertTrue(risingResult.trendingObserved)
        assertTrue(risingResult.trendingScore > stagnantResult.trendingScore)
    }

    @Test
    fun `more same-age peer data raises learned popularity confidence`() {
        val target = post("target", ageHours = 48, legacyRate = 1.0)
        val sparse = mutableListOf(obs(target, 20))
        repeat(3) { index ->
            sparse += obs(post("sparse-$index", ageHours = 48, legacyRate = 1.0), 10 + index)
        }
        val dense = mutableListOf(obs(target, 20))
        repeat(30) { index ->
            dense += obs(post("dense-$index", ageHours = 48, legacyRate = 1.0), 5 + index)
        }

        val sparseConfidence = AdaptiveRanking.rank(listOf(target), sparse, RankingMode.POPULARITY, now)
            .single().popularityConfidence
        val denseConfidence = AdaptiveRanking.rank(listOf(target), dense, RankingMode.POPULARITY, now)
            .single().popularityConfidence

        assertTrue(sparseConfidence > 0.0)
        assertTrue(denseConfidence > sparseConfidence)
    }

    @Test
    fun `comment decreases and stale observations are not learned as positive trend`() {
        val decreasing = post("decreasing", ageHours = 240, legacyRate = 2.0)
        val stale = post("stale", ageHours = 2_400, legacyRate = 3.0)
        val observations = listOf(
            obs(decreasing, 20, observedHoursAgo = 12),
            obs(decreasing, 15, observedHoursAgo = 0),
            obs(stale, 50, observedHoursAgo = 91 * 24),
        )

        val ranked = AdaptiveRanking.rank(listOf(decreasing, stale), observations, RankingMode.TRENDING, now)
        val decreasingResult = ranked.first { it.post.id == "decreasing" }
        val staleResult = ranked.first { it.post.id == "stale" }

        assertFalse(decreasingResult.trendingObserved)
        assertNull(staleResult.commentCount)
    }

    @Test
    fun `duplicate bucket and sub-thirty-minute burst cannot fabricate trending`() {
        val post = post("duplicate", ageHours = 48, legacyRate = 0.0)
        val first = obsAt(post, comments = 1, observedMinutesAgo = 20)
        val duplicateLater = obsAt(post, comments = 200, observedMinutesAgo = 10).copy(
            observedBucketEpochMillis = first.observedBucketEpochMillis,
        )

        val duplicated = AdaptiveRanking.rank(
            listOf(post),
            listOf(first, duplicateLater),
            RankingMode.TRENDING,
            now,
        ).single()
        val baseline = AdaptiveRanking.rank(
            listOf(post),
            listOf(duplicateLater),
            RankingMode.TRENDING,
            now,
        ).single()

        assertFalse(duplicated.trendingObserved)
        assertEquals(baseline.trendingScore, duplicated.trendingScore, 0.0001)
        assertEquals(baseline.commentCount, duplicated.commentCount)
    }

    @Test
    fun `legacy daily rate remains a cold-start safety net`() {
        val first = post("legacy-high", ageHours = 48, legacyRate = 10.0)
        val second = post("legacy-low", ageHours = 48, legacyRate = 1.0)

        val ranked = AdaptiveRanking.rank(listOf(second, first), emptyList(), RankingMode.POPULARITY, now)

        assertEquals("legacy-high", ranked.first().post.id)
    }

    private fun post(id: String, ageHours: Int, legacyRate: Double): PostEntity = PostEntity(
        id = id,
        url = "https://example.test/$id",
        title = id,
        postedAtEpochMillis = now - ageHours * 3_600_000L,
        recommendationCount = 0,
        dailyRate = legacyRate,
        snapshotKey = "test",
        fetchedAtEpochMillis = now,
    )

    private fun obs(post: PostEntity, comments: Int, observedHoursAgo: Int = 0): RankObservationEntity =
        obsAt(post, comments, observedHoursAgo * 60)

    private fun obsAt(post: PostEntity, comments: Int, observedMinutesAgo: Int): RankObservationEntity {
        val observedAt = now - observedMinutesAgo * 60_000L
        return RankObservationEntity(
            datasetKey = "avsee:javfc2",
            postId = post.id,
            postedAtEpochMillis = post.postedAtEpochMillis,
            commentCount = comments,
            observedAtEpochMillis = observedAt,
            observedBucketEpochMillis = observedAt - observedAt % (30L * 60L * 1_000L),
        )
    }
}
