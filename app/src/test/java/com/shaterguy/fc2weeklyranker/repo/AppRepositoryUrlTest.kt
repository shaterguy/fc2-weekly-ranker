package com.shaterguy.fc2weeklyranker.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class AppRepositoryUrlTest {
    @Test
    fun `snapshot key invalidates recommendation parser revision`() {
        assertEquals("ranking-v2:1234:2", AppRepository.snapshotKey(1234L, 2))
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
}
