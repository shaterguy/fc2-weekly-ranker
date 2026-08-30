package com.shaterguy.fc2weeklyranker.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class AppRepositoryUrlTest {
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
    fun `stable video id ignores host case default port and fragment`() {
        assertEquals(
            AppRepository.stableVideoId("123", "https://media.example.test/a.mp4?token=A"),
            AppRepository.stableVideoId("123", "HTTPS://MEDIA.EXAMPLE.TEST:443/a.mp4?token=A#player"),
        )
    }
}
