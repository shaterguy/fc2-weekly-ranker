package com.shaterguy.fc2weeklyranker.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullscreenVideoSupportTest {
    @Test
    fun `landscape video uses display aspect ratio`() {
        assertEquals(
            VideoFrameOrientation.LANDSCAPE,
            classifyVideoFrameOrientation(width = 720, height = 1080, pixelWidthHeightRatio = 2f),
        )
    }

    @Test
    fun `portrait video stays portrait`() {
        assertEquals(
            VideoFrameOrientation.PORTRAIT,
            classifyVideoFrameOrientation(width = 1080, height = 1920, pixelWidthHeightRatio = 1f),
        )
    }

    @Test
    fun `unknown and square video do not force orientation`() {
        assertNull(classifyVideoFrameOrientation(width = 0, height = 1080, pixelWidthHeightRatio = 1f))
        assertNull(classifyVideoFrameOrientation(width = 1080, height = 1080, pixelWidthHeightRatio = 1f))
    }

    @Test
    fun `horizontal seek scales with drag and caps at ten seconds`() {
        assertEquals(5_000L, fullscreenSeekDeltaMs(horizontalDistancePx = 500f, containerWidthPx = 1_000))
        assertEquals(-5_000L, fullscreenSeekDeltaMs(horizontalDistancePx = -500f, containerWidthPx = 1_000))
        assertEquals(FULLSCREEN_MAX_SEEK_MS, fullscreenSeekDeltaMs(horizontalDistancePx = 2_000f, containerWidthPx = 1_000))
        assertEquals(-FULLSCREEN_MAX_SEEK_MS, fullscreenSeekDeltaMs(horizontalDistancePx = -2_000f, containerWidthPx = 1_000))
    }

    @Test
    fun `seek position is clamped to media bounds`() {
        assertEquals(0L, clampFullscreenSeekPosition(startPositionMs = 2_000L, deltaMs = -10_000L, durationMs = 30_000L))
        assertEquals(30_000L, clampFullscreenSeekPosition(startPositionMs = 25_000L, deltaMs = 10_000L, durationMs = 30_000L))
        assertEquals(35_000L, clampFullscreenSeekPosition(startPositionMs = 25_000L, deltaMs = 10_000L, durationMs = null))
    }
}
