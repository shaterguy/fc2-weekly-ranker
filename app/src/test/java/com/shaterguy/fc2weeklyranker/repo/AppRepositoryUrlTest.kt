package com.shaterguy.fc2weeklyranker.repo

import com.shaterguy.fc2weeklyranker.download.VideoDownloadWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppRepositoryUrlTest {
    @Test
    fun `snapshot key invalidates live board parser revision`() {
        assertEquals("ranking-v4:1234:2", AppRepository.snapshotKey(1234L, 2))
    }

    @Test
    fun `ranking dataset key is scoped to the configured origin`() {
        assertEquals(
            "https://01.avsee.is|javfc2",
            AppRepository.rankDatasetKey("https://01.avsee.is"),
        )
        assertNotEquals(
            AppRepository.rankDatasetKey("https://01.avsee.is"),
            AppRepository.rankDatasetKey("https://02.avsee.is"),
        )
    }

    @Test
    fun `rebases stored detail path onto current site origin`() {
        assertEquals(
            "https://99.avsee.is/bbs/board.php?bo_table=javfc2&wr_id=123",
            AppRepository.rebaseDetailUrl(
                "https://01.avsee.is/bbs/board.php?bo_table=javfc2&wr_id=123",
                "https://99.avsee.is",
            ),
        )
    }

    @Test
    fun `same media asset with a renewed token has one stable video id`() {
        assertEquals(
            AppRepository.stableVideoId("123", "https://cdn.example.test/video/a.mp4?token=old"),
            AppRepository.stableVideoId("123", "https://cdn.example.test/video/a.mp4?token=new"),
        )
    }

    @Test
    fun `HLS manifest is never treated as a downloadable video file`() {
        assertEquals(false, VideoDownloadWorker.supportsFileDownload("https://cdn.example.test/playlist.m3u8?token=abc"))
    }
}
