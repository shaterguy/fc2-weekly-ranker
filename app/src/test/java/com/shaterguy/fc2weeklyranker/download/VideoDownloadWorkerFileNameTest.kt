package com.shaterguy.fc2weeklyranker.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VideoDownloadWorkerFileNameTest {
    @Test
    fun `preserves original FC2PPV filename and suffix letters`() {
        assertEquals(
            "FC2PPV-0000000.mp4",
            VideoDownloadWorker.outputFileName("123", 0, "https://cdn.example.test/FC2PPV-0000000.mp4"),
        )
        assertEquals(
            "FC2PPV-0000000A.mp4",
            VideoDownloadWorker.outputFileName("123", 1, "https://cdn.example.test/FC2PPV-0000000A.mp4?token=abc"),
        )
    }

    @Test
    fun `decodes safe path basename but rejects encoded path separators`() {
        assertEquals(
            "FC2PPV-0000000 A.mp4",
            VideoDownloadWorker.outputFileName("123", 0, "https://cdn.example.test/FC2PPV-0000000%20A.mp4"),
        )
        val unsafe = VideoDownloadWorker.outputFileName("123", 2, "https://cdn.example.test/FC2PPV%2Fbad.mp4")
        assertFalse(unsafe.contains('/'))
        assertEquals("weekly_ranker_123_3.mp4", unsafe)
    }

    @Test
    fun `uses deterministic fallback when URL has no basename`() {
        assertEquals(
            "weekly_ranker_post_1.mp4",
            VideoDownloadWorker.outputFileName("post", 0, "https://cdn.example.test/"),
        )
    }
}
