package com.shaterguy.fc2weeklyranker.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseUrlPolicyTest {
    @Test fun `normalizes public https origin`() { assertEquals("https://02.avsee.is", BaseUrlPolicy.normalize(" https://02.avsee.is/ ").getOrThrow()) }
    @Test fun `rejects unsafe schemes paths and local addresses`() { assertTrue(BaseUrlPolicy.normalize("http://02.avsee.is").isFailure); assertTrue(BaseUrlPolicy.normalize("https://02.avsee.is/bbs/board.php").isFailure); assertTrue(BaseUrlPolicy.normalize("https://localhost").isFailure); assertTrue(BaseUrlPolicy.normalize("https://127.0.0.1").isFailure) }
}
