package com.shaterguy.fc2weeklyranker.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFullscreenSessionGateTest {
    @Test
    fun `a later video can own the first fullscreen session`() {
        val gate = NativeFullscreenSessionGate()

        val session = gate.begin("video-2")

        requireNotNull(session)
        assertEquals("video-2", session.videoId)
        assertTrue(gate.isCurrent(session))
    }

    @Test
    fun `duplicate and competing opens are rejected while fullscreen is active`() {
        val gate = NativeFullscreenSessionGate()
        val session = gate.begin("video-2")

        requireNotNull(session)
        assertNull(gate.begin("video-2"))
        assertNull(gate.begin("video-3"))
        assertTrue(gate.isCurrent(session))
    }

    @Test
    fun `stale dismiss cannot finish a newer video session`() {
        val gate = NativeFullscreenSessionGate()
        val first = requireNotNull(gate.begin("video-2"))
        assertTrue(gate.finish(first))
        val second = requireNotNull(gate.begin("video-3"))

        assertFalse(gate.finish(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `reentry receives a new session identity`() {
        val gate = NativeFullscreenSessionGate()
        val first = requireNotNull(gate.begin("video-2"))
        assertTrue(gate.finish(first))

        val second = requireNotNull(gate.begin("video-2"))

        assertNotEquals(first.sessionId, second.sessionId)
        assertTrue(gate.isCurrent(second))
    }
}
