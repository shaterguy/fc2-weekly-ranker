package com.shaterguy.fc2weeklyranker.repo

import com.shaterguy.fc2weeklyranker.data.VideoEntity
import com.shaterguy.fc2weeklyranker.media.normalizeMediaCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoReconcileTest {
    @Test
    fun detailRefreshHidesLegacyProbeRowsWithoutDeletingTheirIds() {
        val detailUrl = "https://example.test/post"
        val static = video("static", "https://cdn.example/static.mp4", "DIRECT", 0, 10L, detailUrl)
        val legacyProbe = video("legacy", "https://cdn.example/session-a.mp4", "DIRECT", 1, 20L, "https://embed.example/player")
        val oldResolver = video("old-frame", "https://embed.example/player", "IFRAME", 1, 15L, detailUrl)
        val freshResolver = oldResolver.copy(discoveredAtEpochMillis = 30L)

        val result = AppRepository.reconcileVideoRows(
            existing = listOf(static, legacyProbe, oldResolver),
            fresh = listOf(static.copy(discoveredAtEpochMillis = 30L), freshResolver),
            detailUrl = detailUrl,
        )

        assertEquals("DIRECT", result.single { it.id == "static" }.sourceKind)
        assertEquals("HISTORICAL", result.single { it.id == "legacy" }.sourceKind)
        assertEquals("IFRAME", result.single { it.id == "old-frame" }.sourceKind)
    }

    @Test
    fun activeProbeFromCurrentEntrySurvivesConcurrentDetailRefresh() {
        val detailUrl = "https://example.test/post"
        val active = video("active", "https://cdn.example/live.mp4", "DIRECT", 1_001_000, 20L, "https://embed.example/player")
        val freshResolver = video("frame", "https://embed.example/player", "IFRAME", 1, 30L, detailUrl)

        val result = AppRepository.reconcileVideoRows(
            existing = listOf(active),
            fresh = listOf(freshResolver),
            detailUrl = detailUrl,
            activeProbeIds = setOf("active"),
        )

        assertEquals("DIRECT", result.single { it.id == "active" }.sourceKind)
    }

    @Test
    fun repeatedDynamicPathsReuseOneResolverSlotAndRetireLegacyDuplicates() {
        val referer = "https://embed.example/player"
        val legacyPreferred = video("legacy-preferred", "https://cdn.example/old-a.mp4", "HISTORICAL", 2, 10L, referer)
        val legacyDuplicate = video("legacy-duplicate", "https://cdn.example/old-b.mp4", "HISTORICAL", 2, 20L, referer)
        var state = listOf(legacyPreferred, legacyDuplicate)

        val first = AppRepository.reconcileProbeRows(
            existing = state,
            preferredLegacyIds = setOf("legacy-preferred"),
            postId = "post",
            referer = referer,
            resolverOrdinal = 2,
            candidates = listOf("https://cdn.example/session-one/video.mp4?token=1"),
            now = 100L,
        )
        state = applyUpdates(state, first)
        assertEquals(listOf("legacy-preferred"), state.filter { it.sourceKind == "DIRECT" }.map { it.id })
        assertEquals("HISTORICAL", state.single { it.id == "legacy-duplicate" }.sourceKind)

        val second = AppRepository.reconcileProbeRows(
            existing = state,
            preferredLegacyIds = setOf("legacy-preferred"),
            postId = "post",
            referer = referer,
            resolverOrdinal = 2,
            candidates = listOf("https://cdn.example/session-two/video.mp4?token=2"),
            now = 200L,
        )
        state = applyUpdates(state, second)

        val active = state.filter { it.sourceKind == "DIRECT" }
        assertEquals(1, active.size)
        assertEquals("legacy-preferred", active.single().id)
        assertEquals("https://cdn.example/session-two/video.mp4?token=2", active.single().url)
    }

    @Test
    fun multipleActualVideosGetStableDistinctSlotsOnFirstBatchAndReentry() {
        val referer = "https://embed.example/player"
        var state = emptyList<VideoEntity>()
        val first = AppRepository.reconcileProbeRows(
            existing = state,
            preferredLegacyIds = emptySet(),
            postId = "post",
            referer = referer,
            resolverOrdinal = 3,
            candidates = listOf(
                "https://cdn.example/first-session/a.mp4",
                "https://cdn.example/first-session/b.mp4",
            ),
            now = 100L,
        )
        state = applyUpdates(state, first)
        val firstIds = state.filter { it.sourceKind == "DIRECT" }.map { it.id }
        assertEquals(2, firstIds.size)
        assertEquals(2, firstIds.toSet().size)

        val second = AppRepository.reconcileProbeRows(
            existing = state,
            preferredLegacyIds = emptySet(),
            postId = "post",
            referer = referer,
            resolverOrdinal = 3,
            candidates = listOf(
                "https://cdn.example/second-session/a.mp4",
                "https://cdn.example/second-session/b.mp4",
            ),
            now = 200L,
        )
        state = applyUpdates(state, second)
        val secondActive = state.filter { it.sourceKind == "DIRECT" }

        assertEquals(2, secondActive.size)
        assertEquals(firstIds, secondActive.map { it.id })
        assertTrue(secondActive[0].url.contains("second-session"))
        assertTrue(secondActive[1].url.contains("second-session"))
    }

    @Test
    fun candidateNormalizationKeepsDistinctVideosButCollapsesQueryVariants() {
        val result = normalizeMediaCandidates(
            listOf(
                "https://cdn.example/a.mp4?token=1",
                "https://cdn.example/a.mp4?token=2",
                "https://cdn.example/b.mp4?token=3",
                "https://cdn.example/not-media.jpg",
            ),
        )

        assertEquals(2, result.size)
        assertTrue(result.first().contains("a.mp4"))
        assertTrue(result.last().contains("b.mp4"))
        assertFalse(result.any { it.contains("jpg") })
    }

    private fun applyUpdates(existing: List<VideoEntity>, updates: List<VideoEntity>): List<VideoEntity> {
        val rows = existing.associateByTo(linkedMapOf()) { it.id }
        updates.forEach { rows[it.id] = it }
        return rows.values.toList()
    }

    private fun video(
        id: String,
        url: String,
        kind: String,
        ordinal: Int,
        discoveredAt: Long,
        referer: String,
    ) = VideoEntity(
        id = id,
        postId = "post",
        url = url,
        referer = referer,
        userAgent = "test-agent",
        sourceKind = kind,
        ordinal = ordinal,
        discoveredAtEpochMillis = discoveredAt,
    )
}
