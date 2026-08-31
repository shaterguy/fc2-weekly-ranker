package com.shaterguy.fc2weeklyranker.repo

import com.shaterguy.fc2weeklyranker.data.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoReconcileTest {
    @Test
    fun keepsPreviouslyProbedDirectVideoWhenRefreshOmitsIt() {
        val first = video("first", "https://cdn.example/first.mp4", "DIRECT", 0, 10L)
        val second = video("second", "https://cdn.example/second.mp4", "DIRECT", 1, 20L)
        val oldResolver = video("old-frame", "https://embed.example/old", "IFRAME", 1, 15L)
        val newResolver = video("new-frame", "https://embed.example/new", "IFRAME", 1, 30L)

        val result = AppRepository.reconcileVideoRows(
            existing = listOf(first, second, oldResolver),
            fresh = listOf(first.copy(discoveredAtEpochMillis = 40L), newResolver),
        )

        assertEquals(listOf("first", "second", "new-frame"), result.map { it.id })
        assertTrue(result.any { it.id == "second" && it.url.endsWith("second.mp4") })
        assertFalse(result.any { it.id == "old-frame" })
    }

    @Test
    fun freshDirectRowReplacesSameStableIdWithoutDuplication() {
        val existing = video("same", "https://cdn.example/video.mp4?old=1", "DIRECT", 0, 10L)
        val fresh = existing.copy(url = "https://cdn.example/video.mp4?new=1", discoveredAtEpochMillis = 20L)

        val result = AppRepository.reconcileVideoRows(listOf(existing), listOf(fresh))

        assertEquals(1, result.size)
        assertEquals(fresh, result.single())
    }

    private fun video(id: String, url: String, kind: String, ordinal: Int, discoveredAt: Long) =
        VideoEntity(
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
