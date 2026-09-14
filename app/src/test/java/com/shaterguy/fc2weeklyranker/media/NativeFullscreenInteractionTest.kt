package com.shaterguy.fc2weeklyranker.media

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `seek preview includes direction delta target and total duration`() {
        assertEquals(
            "탐색 +30초  02:00 / 10:00",
            nativeFullscreenSeekPreviewText(30_000L, 120_000L, 600_000L),
        )
        assertEquals(
            "탐색 -10초  00:05 / --:--",
            nativeFullscreenSeekPreviewText(-10_000L, 5_000L, -1L),
        )
    }

    @Test
    fun `seek target clamps safely including overflow`() {
        assertEquals(0L, seekTarget(2_000L, -10_000L, 30_000L))
        assertEquals(30_000L, seekTarget(25_000L, 10_000L, 30_000L))
        assertEquals(35_000L, seekTarget(25_000L, 10_000L, -1L))
        assertEquals(30_000L, seekTarget(Long.MAX_VALUE - 5L, 50L, 30_000L))
    }

    @Test
    fun `gesture classifier waits for touch slop and locks the selected axis`() {
        val classifier = NativeFullscreenGestureClassifier(touchSlopPx = 12)
        assertEquals(NativeGestureMode.NONE, classifier.update(6f, 7f, 100f, 1_000))
        assertEquals(NativeGestureMode.NONE, classifier.update(12f, 12f, 100f, 1_000))
        assertEquals(NativeGestureMode.HORIZONTAL, classifier.update(30f, 4f, 100f, 1_000))
        assertEquals(
            "a classified gesture must not switch axis mid-drag",
            NativeGestureMode.HORIZONTAL,
            classifier.update(2f, 100f, 100f, 1_000),
        )

        classifier.reset()
        assertEquals(NativeGestureMode.BRIGHTNESS, classifier.update(1f, 30f, 100f, 1_000))
        classifier.reset()
        assertEquals(NativeGestureMode.VOLUME, classifier.update(1f, 30f, 900f, 1_000))
    }

    @Test
    fun `vertical gesture mapping clamps brightness and device min max volume`() {
        assertEquals(1f, verticalFraction(0.5f, -500f, 1_000, 1f), 0.0001f)
        assertEquals(0f, verticalFraction(0.5f, 800f, 1_000, 1f), 0.0001f)
        assertEquals(13, volumeLevel(5, 0, 15, -500f, 1_000, 1f))
        assertEquals(0, volumeLevel(5, 0, 15, 1_000f, 1_000, 1f))
        assertEquals(10, volumeLevel(7, 3, 10, -2_000f, 1_000, 1f))
        assertEquals(3, volumeLevel(7, 3, 10, 2_000f, 1_000, 1f))
    }

    @Test
    fun `sensitivity modes persist low normal high semantics and cycle`() {
        assertEquals(0.7f, NativeGestureSensitivity.LOW.factor, 0.0001f)
        assertEquals(1f, NativeGestureSensitivity.NORMAL.factor, 0.0001f)
        assertEquals(1.3f, NativeGestureSensitivity.HIGH.factor, 0.0001f)
        assertEquals(NativeGestureSensitivity.NORMAL, NativeGestureSensitivity.LOW.next())
        assertEquals(NativeGestureSensitivity.HIGH, NativeGestureSensitivity.NORMAL.next())
        assertEquals(NativeGestureSensitivity.LOW, NativeGestureSensitivity.HIGH.next())
        assertEquals(NativeGestureSensitivity.NORMAL, NativeGestureSensitivity.fromStored("UNKNOWN"))
    }

    @Test
    fun `double tap regions map to rewind toggle and forward`() {
        assertEquals("BACKWARD", doubleTapAction(100f, 900))
        assertEquals("TOGGLE_PLAYBACK", doubleTapAction(450f, 900))
        assertEquals("FORWARD", doubleTapAction(800f, 900))
    }

    @Test
    fun `aspect modes cover fit zoom fill and return to fit`() {
        assertEquals(NativeAspectMode.ZOOM, NativeAspectMode.FIT.next())
        assertEquals(NativeAspectMode.FILL, NativeAspectMode.ZOOM.next())
        assertEquals(NativeAspectMode.FIT, NativeAspectMode.FILL.next())
    }

    @Test
    fun `orientation lock state toggles deterministically`() {
        assertTrue(nativeFullscreenToggleOrientationLock(false))
        assertFalse(nativeFullscreenToggleOrientationLock(true))
    }

    @Test
    fun `screen lock and pip block gesture input`() {
        assertTrue(nativeFullscreenGestureInputAllowed(interactionLocked = false, enabled = true))
        assertFalse(nativeFullscreenGestureInputAllowed(interactionLocked = true, enabled = true))
        assertFalse(nativeFullscreenGestureInputAllowed(interactionLocked = false, enabled = false))
    }

    @Test
    fun `controller auto hide only applies while actively playing`() {
        assertTrue(
            nativeFullscreenShouldAutoHide(
                isPlaying = true,
                controlsVisible = true,
                interactionLocked = false,
                pictureInPicture = false,
            ),
        )
        assertFalse(nativeFullscreenShouldAutoHide(false, true, false, false))
        assertFalse(nativeFullscreenShouldAutoHide(true, false, false, false))
        assertFalse(nativeFullscreenShouldAutoHide(true, true, true, false))
        assertFalse(nativeFullscreenShouldAutoHide(true, true, false, true))
    }

    @Test
    fun `playback speed cycles through every supported value`() {
        val expected = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 0.5f)
        var current = 0.5f
        expected.forEach { next ->
            current = nextSpeed(current)
            assertEquals(next, current, 0.0001f)
        }
    }
}
