package com.shaterguy.fc2weeklyranker.media

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativeFullscreenInteractionTest {
    private val ownerClassName = "com.shaterguy.fc2weeklyranker.media.NativeFullscreenInteractionKt"

    private fun method(name: String, vararg parameterTypes: Class<*>): Method =
        runCatching { Class.forName(ownerClassName).getDeclaredMethod(name, *parameterTypes) }
            .getOrElse {
                fail("missing fullscreen-assistant behavior: $name")
                throw AssertionError(it)
            }

    private fun seekSpan(durationMs: Long, sensitivityFactor: Float): Long =
        method(
            "nativeFullscreenSeekSpanMs",
            java.lang.Long.TYPE,
            java.lang.Float.TYPE,
        ).invoke(null, durationMs, sensitivityFactor) as Long

    private fun seekDelta(
        distancePx: Float,
        widthPx: Int,
        durationMs: Long,
        sensitivityFactor: Float,
    ): Long = method(
        "nativeFullscreenSeekDeltaMs",
        java.lang.Float.TYPE,
        java.lang.Integer.TYPE,
        java.lang.Long.TYPE,
        java.lang.Float.TYPE,
    ).invoke(null, distancePx, widthPx, durationMs, sensitivityFactor) as Long

    private fun seekTarget(startMs: Long, deltaMs: Long, durationMs: Long): Long =
        method(
            "nativeFullscreenSeekTargetMs",
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
        ).invoke(null, startMs, deltaMs, durationMs) as Long

    private fun verticalFraction(
        start: Float,
        distancePx: Float,
        heightPx: Int,
        sensitivityFactor: Float,
    ): Float = method(
        "nativeFullscreenVerticalFraction",
        java.lang.Float.TYPE,
        java.lang.Float.TYPE,
        java.lang.Integer.TYPE,
        java.lang.Float.TYPE,
    ).invoke(null, start, distancePx, heightPx, sensitivityFactor) as Float

    private fun volumeLevel(
        start: Int,
        min: Int,
        max: Int,
        distancePx: Float,
        heightPx: Int,
        sensitivityFactor: Float,
    ): Int = method(
        "nativeFullscreenVolumeLevel",
        java.lang.Integer.TYPE,
        java.lang.Integer.TYPE,
        java.lang.Integer.TYPE,
        java.lang.Float.TYPE,
        java.lang.Integer.TYPE,
        java.lang.Float.TYPE,
    ).invoke(null, start, min, max, distancePx, heightPx, sensitivityFactor) as Int

    private fun doubleTapAction(xPx: Float, widthPx: Int): String =
        method(
            "nativeFullscreenDoubleTapAction",
            java.lang.Float.TYPE,
            java.lang.Integer.TYPE,
        ).invoke(null, xPx, widthPx).toString()

    private fun nextSpeed(current: Float): Float =
        method("nativeFullscreenNextSpeed", java.lang.Float.TYPE).invoke(null, current) as Float

    @Test
    fun `seek window is duration aware and sensitivity scaled`() {
        assertEquals(60_000L, seekSpan(durationMs = -1L, sensitivityFactor = 1f))
        assertEquals(42_000L, seekSpan(durationMs = -1L, sensitivityFactor = 0.7f))
        assertEquals(78_000L, seekSpan(durationMs = -1L, sensitivityFactor = 1.3f))
        assertEquals(30_000L, seekSpan(durationMs = 120_000L, sensitivityFactor = 1f))
        assertEquals(60_000L, seekSpan(durationMs = 600_000L, sensitivityFactor = 1f))
        assertEquals(120_000L, seekSpan(durationMs = 1_800_000L, sensitivityFactor = 1f))
    }

    @Test
    fun `horizontal drag previews proportional seek without exceeding span`() {
        assertEquals(30_000L, seekDelta(500f, 1_000, 600_000L, 1f))
        assertEquals(-30_000L, seekDelta(-500f, 1_000, 600_000L, 1f))
        assertEquals(60_000L, seekDelta(2_000f, 1_000, 600_000L, 1f))
    }

    @Test
    fun `seek target clamps safely including overflow`() {
        assertEquals(0L, seekTarget(2_000L, -10_000L, 30_000L))
        assertEquals(30_000L, seekTarget(25_000L, 10_000L, 30_000L))
        assertEquals(35_000L, seekTarget(25_000L, 10_000L, -1L))
        assertEquals(30_000L, seekTarget(Long.MAX_VALUE - 5L, 50L, 30_000L))
    }

    @Test
    fun `vertical gesture mapping clamps brightness and volume`() {
        assertEquals(1f, verticalFraction(0.5f, -500f, 1_000, 1f), 0.0001f)
        assertEquals(0f, verticalFraction(0.5f, 800f, 1_000, 1f), 0.0001f)
        assertEquals(13, volumeLevel(5, 0, 15, -500f, 1_000, 1f))
        assertEquals(0, volumeLevel(5, 0, 15, 1_000f, 1_000, 1f))
    }

    @Test
    fun `double tap regions map to rewind toggle and forward`() {
        assertEquals("BACKWARD", doubleTapAction(100f, 900))
        assertEquals("TOGGLE_PLAYBACK", doubleTapAction(450f, 900))
        assertEquals("FORWARD", doubleTapAction(800f, 900))
    }

    @Test
    fun `playback speed cycles through supported values`() {
        assertEquals(0.75f, nextSpeed(0.5f), 0.0001f)
        assertEquals(1.25f, nextSpeed(1f), 0.0001f)
        assertEquals(0.5f, nextSpeed(2f), 0.0001f)
        assertTrue(nextSpeed(1.5f) > 1.5f)
    }
}
