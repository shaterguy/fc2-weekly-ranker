package com.shaterguy.fc2weeklyranker

import com.shaterguy.fc2weeklyranker.data.VideoEntity
import com.shaterguy.fc2weeklyranker.repo.AppRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDetailVisibilityTest {
    @Test
    fun cachedRowsStayHiddenUntilRefreshAndCanonicalDuplicatesCollapse() {
        val rows = listOf(
            video("old-direct", "https://cdn.example/a.mp4?token=old", AppRepository.SOURCE_DIRECT, 1, 50L),
            video("old-resolver", "https://embed.example/player?video=old", AppRepository.SOURCE_IFRAME, 2, 50L),
            video("fresh-a", "https://cdn.example/a.mp4?token=one", AppRepository.SOURCE_DIRECT, 1_001_000, 110L),
            video("fresh-a-duplicate", "https://cdn.example/a.mp4?token=two", AppRepository.SOURCE_DIRECT, 1_002_000, 120L),
            video("fresh-b", "https://cdn.example/b.mp4?token=one", AppRepository.SOURCE_DIRECT, 1_003_000, 130L),
            video("fresh-resolver", "https://embed.example/player?video=new", AppRepository.SOURCE_IFRAME, 3, 110L),
        )

        assertTrue(visibleDirectVideos(rows, null).isEmpty())
        assertTrue(visibleDetailResolvers(rows, null).isEmpty())

        val direct = visibleDirectVideos(rows, 100L)
        val resolvers = visibleDetailResolvers(rows, 100L)

        assertEquals(listOf("fresh-a", "fresh-b"), direct.map { it.id })
        assertEquals(listOf("fresh-resolver"), resolvers.map { it.id })
    }

    @Test
    fun newerEntryCutoffRejectsPreviousEntryRows() {
        val rows = listOf(
            video("entry-one-direct", "https://cdn.example/one.mp4", AppRepository.SOURCE_DIRECT, 1_001_000, 150L),
            video("entry-one-resolver", "https://embed.example/player?video=one", AppRepository.SOURCE_IFRAME, 1, 150L),
            video("entry-two-direct", "https://cdn.example/two.mp4", AppRepository.SOURCE_DIRECT, 1_001_000, 250L),
            video("entry-two-resolver", "https://embed.example/player?video=two", AppRepository.SOURCE_IFRAME, 1, 250L),
        )

        assertEquals(listOf("entry-two-direct"), visibleDirectVideos(rows, 200L).map { it.id })
        assertEquals(listOf("entry-two-resolver"), visibleDetailResolvers(rows, 200L).map { it.id })
    }

    private fun video(id: String, url: String, kind: String, ordinal: Int, discoveredAt: Long) = VideoEntity(
        id = id,
        postId = "post",
        url = url,
        referer = "https://example.test/post",
        userAgent = "test-agent",
        sourceKind = kind,
        ordinal = ordinal,
        discoveredAtEpochMillis = discoveredAt,
    )
}
