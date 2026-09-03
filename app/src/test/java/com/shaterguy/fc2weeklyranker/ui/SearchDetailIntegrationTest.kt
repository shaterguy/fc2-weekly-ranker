package com.shaterguy.fc2weeklyranker.ui

import com.shaterguy.fc2weeklyranker.data.PostEntity
import com.shaterguy.fc2weeklyranker.network.RemotePost
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchDetailIntegrationTest {
    @Test
    fun searchPostUsesDedicatedCacheWithoutInventingCommentMetrics() {
        val postedAt = Instant.parse("2026-09-03T01:02:03Z")
        val row = searchPostEntity(
            RemotePost(
                id = "123",
                url = "https://01.avsee.is/bbs/board.php?bo_table=javfc2&wr_id=123",
                title = "검색 게시물",
                postedAt = postedAt,
                recommendationCount = 77,
                media = emptyList(),
            ),
            fetchedAtEpochMillis = 456L,
        )

        assertEquals("123", row.id)
        assertEquals("검색 게시물", row.title)
        assertEquals(postedAt.toEpochMilli(), row.postedAtEpochMillis)
        assertEquals(0, row.recommendationCount)
        assertEquals(0.0, row.dailyRate, 0.0)
        assertEquals(SEARCH_SNAPSHOT_KEY, row.snapshotKey)
        assertEquals(456L, row.fetchedAtEpochMillis)
    }

    @Test
    fun searchCacheRowsDoNotAppearInRankingList() {
        val ranking = post("ranking", "ranking-v5-comments:1:0")
        val search = post("search", SEARCH_SNAPSHOT_KEY)

        assertEquals(listOf(ranking), rankingVisiblePosts(listOf(search, ranking)))
    }

    private fun post(id: String, snapshotKey: String) = PostEntity(
        id = id,
        url = "https://example.com/$id",
        title = id,
        postedAtEpochMillis = 1L,
        recommendationCount = 1,
        dailyRate = 1.0,
        snapshotKey = snapshotKey,
        fetchedAtEpochMillis = 1L,
    )
}
