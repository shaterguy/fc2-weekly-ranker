package com.shaterguy.fc2weeklyranker.domain

import com.shaterguy.fc2weeklyranker.ui.contentModeForLocalPostId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LtxContentModeTest {
    @Test fun ltxResolvesToJavmAndSharesTheConfiguredOriginContract() {
        assertEquals("javm", ContentMode.LTX.boardTable)
        assertEquals("LTX", ContentMode.LTX.sourceKey)
        assertEquals(ContentMode.LTX, ContentMode.fromSourceKey("LTX"))
    }

    @Test fun allThreeBoardsCanStoreTheSameRemotePostNumber() {
        val remoteId = "42"
        val localIds = ContentMode.entries.map { it.localPostId(remoteId) }
        assertEquals(3, localIds.distinct().size)
        for (mode in ContentMode.entries) {
            val local = mode.localPostId(remoteId)
            assertEquals(remoteId, mode.remotePostId(local))
            assertEquals(mode, contentModeForLocalPostId(local))
        }
        assertEquals("jav:42", ContentMode.JAV.localPostId(remoteId))
        assertEquals("ltx:42", ContentMode.LTX.localPostId(remoteId))
        assertNotEquals(ContentMode.JAV.localPostId(remoteId), ContentMode.LTX.localPostId(remoteId))
    }
}
