package com.shaterguy.fc2weeklyranker.media

import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal enum class NativeGestureSensitivity(val factor: Float, val label: String) {
    LOW(0.7f, "낮음"),
    NORMAL(1f, "보통"),
    HIGH(1.3f, "높음");

    fun next(): NativeGestureSensitivity = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStored(value: String?): NativeGestureSensitivity =
            entries.firstOrNull { it.name == value } ?: NORMAL
    }
}

internal enum class NativeDoubleTapAction {
    BACKWARD,
    TOGGLE_PLAYBACK,
    FORWARD,
}

internal enum class NativeAspectMode(val label: String) {
    FIT("맞춤"),
    ZOOM("확대"),
    FILL("채움");

    fun next(): NativeAspectMode = entries[(ordinal + 1) % entries.size]
}

internal fun nativeFullscreenSeekSpanMs(durationMs: Long, sensitivityFactor: Float): Long {
    val base = if (durationMs > 0L) {
        (durationMs / 10L).coerceIn(30_000L, 120_000L)
    } else {
        60_000L
    }
    return (base.toDouble() * sensitivityFactor.coerceIn(0.25f, 2f).toDouble())
        .roundToLong()
        .coerceIn(1_000L, 120_000L)
}

internal fun nativeFullscreenSeekDeltaMs(
    distancePx: Float,
    widthPx: Int,
    durationMs: Long,
    sensitivityFactor: Float,
): Long {
    if (widthPx <= 0) return 0L
    val fraction = (distancePx / widthPx.toFloat()).coerceIn(-1f, 1f)
    return (nativeFullscreenSeekSpanMs(durationMs, sensitivityFactor) * fraction).roundToLong()
}

internal fun nativeFullscreenSeekTargetMs(
    startPositionMs: Long,
    deltaMs: Long,
    durationMs: Long,
): Long {
    val target = when {
        deltaMs > 0L && startPositionMs > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE
        deltaMs < 0L && startPositionMs < Long.MIN_VALUE - deltaMs -> Long.MIN_VALUE
        else -> startPositionMs + deltaMs
    }.coerceAtLeast(0L)
    return if (durationMs > 0L) target.coerceAtMost(durationMs) else target
}

internal fun nativeFullscreenVerticalFraction(
    startFraction: Float,
    distancePx: Float,
    heightPx: Int,
    sensitivityFactor: Float,
): Float {
    if (heightPx <= 0) return startFraction.coerceIn(0f, 1f)
    return (
        startFraction -
            (distancePx / heightPx.toFloat()) * sensitivityFactor.coerceIn(0.25f, 2f)
        ).coerceIn(0f, 1f)
}

internal fun nativeFullscreenVolumeLevel(
    startLevel: Int,
    minLevel: Int,
    maxLevel: Int,
    distancePx: Float,
    heightPx: Int,
    sensitivityFactor: Float,
): Int {
    if (maxLevel <= minLevel || heightPx <= 0) return startLevel.coerceIn(minLevel, maxLevel)
    val range = maxLevel - minLevel
    val startFraction = (startLevel - minLevel).toFloat() / range.toFloat()
    val targetFraction = nativeFullscreenVerticalFraction(
        startFraction = startFraction,
        distancePx = distancePx,
        heightPx = heightPx,
        sensitivityFactor = sensitivityFactor,
    )
    return (minLevel + targetFraction * range).roundToInt().coerceIn(minLevel, maxLevel)
}

internal fun nativeFullscreenDoubleTapAction(xPx: Float, widthPx: Int): NativeDoubleTapAction {
    if (widthPx <= 0) return NativeDoubleTapAction.TOGGLE_PLAYBACK
    val third = widthPx / 3f
    return when {
        xPx < third -> NativeDoubleTapAction.BACKWARD
        xPx > third * 2f -> NativeDoubleTapAction.FORWARD
        else -> NativeDoubleTapAction.TOGGLE_PLAYBACK
    }
}

private val NATIVE_FULLSCREEN_SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

internal fun nativeFullscreenNextSpeed(currentSpeed: Float): Float {
    val next = NATIVE_FULLSCREEN_SPEEDS.firstOrNull { it > currentSpeed + 0.001f }
    return next ?: NATIVE_FULLSCREEN_SPEEDS.first()
}
