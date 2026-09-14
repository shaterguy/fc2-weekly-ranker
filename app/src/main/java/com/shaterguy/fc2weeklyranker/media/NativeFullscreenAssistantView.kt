package com.shaterguy.fc2weeklyranker.media

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@SuppressLint("ViewConstructor")
internal class NativeFullscreenAssistantView(
    context: Context,
    private val activity: Activity,
    private val player: ExoPlayer,
    internal val playerView: PlayerView,
    initialSensitivity: NativeGestureSensitivity,
    private val onClose: () -> Unit,
    private val onPictureInPicture: () -> Unit,
    private val onSensitivityChanged: (NativeGestureSensitivity) -> Unit,
    private val onOrientationLockChanged: (Boolean) -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val topControls = LinearLayout(context)
    private val assistantScroll = HorizontalScrollView(context)
    private val assistantControls = LinearLayout(context)
    private val osd = TextView(context)
    private val unlockButton = makeButton("화면 잠금 해제", "화면 잠금 해제", "화면 잠김")
    private val orientationButton = makeButton("회전 자동", "회전 잠금 설정", "자동")
    private val sensitivityButton = makeButton("", "제스처 감도 변경")
    private val speedButton = makeButton("", "재생 속도 변경")
    private val aspectButton = makeButton("", "화면 비율 변경")
    private val subtitleButton = makeButton("자막 OFF", "자막 변경", "OFF")
    private val gestureView: NativeFullscreenGestureView
    private var controlsVisible = true
    private var pictureInPicture = false
    private var subtitleSelection = -1
    private var aspectMode = NativeAspectMode.FIT
    private var sensitivity = initialSensitivity

    internal var interactionLocked: Boolean = false
        private set
    internal var orientationLocked: Boolean = false
        private set

    private val autoHide = Runnable {
        if (
            nativeFullscreenShouldAutoHide(
                isPlaying = player.isPlaying,
                controlsVisible = controlsVisible,
                interactionLocked = interactionLocked,
                pictureInPicture = pictureInPicture,
            )
        ) {
            hideControls()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) scheduleAutoHide() else if (!pictureInPicture && !interactionLocked) showControls()
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true

        playerView.apply {
            useController = true
            setControllerShowTimeoutMs(CONTROL_TIMEOUT_MS.toInt())
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        gestureView = NativeFullscreenGestureView(
            context = context,
            activity = activity,
            player = player,
            audioManager = audioManager,
            sensitivityProvider = { sensitivity.factor },
            onSingleTap = { toggleControls() },
            onOsd = { message, persistent -> showOsd(message, persistent) },
        )
        addView(
            gestureView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                bottomMargin = dp(112)
            },
        )

        configureTopControls()
        configureAssistantControls()
        configureOsdAndUnlock()

        player.addListener(playerListener)
        updateSensitivity(initialSensitivity)
        refreshSpeedLabel()
        refreshAspectLabel()
        refreshOrientationLabel()
        showControls()
    }

    private fun configureTopControls() {
        topControls.orientation = LinearLayout.HORIZONTAL
        topControls.gravity = Gravity.CENTER_VERTICAL
        topControls.setPadding(dp(8), dp(8), dp(8), dp(8))

        topControls.addView(makeButton("화면 잠금", "화면 잠금", "해제됨").apply {
            setOnClickListener { setInteractionLocked(true) }
        })
        topControls.addView(orientationButton.apply {
            setOnClickListener {
                orientationLocked = nativeFullscreenToggleOrientationLock(orientationLocked)
                refreshOrientationLabel()
                onOrientationLockChanged(orientationLocked)
                showOsd(if (orientationLocked) "현재 방향 잠금" else "동영상 방향 자동", false)
                scheduleAutoHide()
            }
        })
        topControls.addView(makeButton("닫기", "전체화면 닫기").apply {
            setOnClickListener { if (!interactionLocked) onClose() }
        })

        addView(
            topControls,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END),
        )
    }

    private fun configureAssistantControls() {
        assistantControls.orientation = LinearLayout.HORIZONTAL
        assistantControls.gravity = Gravity.CENTER_VERTICAL
        assistantControls.setPadding(dp(8), dp(4), dp(8), dp(4))

        assistantControls.addView(makeButton("-10초", "10초 뒤로 이동").apply {
            setOnClickListener { seekBy(-10_000L, "10초 뒤로") }
        })
        assistantControls.addView(makeButton("+10초", "10초 앞으로 이동").apply {
            setOnClickListener { seekBy(10_000L, "10초 앞으로") }
        })
        assistantControls.addView(speedButton.apply {
            setOnClickListener {
                player.setPlaybackSpeed(nativeFullscreenNextSpeed(player.playbackParameters.speed))
                refreshSpeedLabel()
                showOsd("재생 속도 ${formatSpeed(player.playbackParameters.speed)}", false)
                scheduleAutoHide()
            }
        })
        assistantControls.addView(aspectButton.apply {
            setOnClickListener {
                aspectMode = aspectMode.next()
                playerView.resizeMode = when (aspectMode) {
                    NativeAspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    NativeAspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    NativeAspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
                refreshAspectLabel()
                showOsd("화면 ${aspectMode.label}", false)
                scheduleAutoHide()
            }
        })
        assistantControls.addView(subtitleButton.apply {
            setOnClickListener {
                cycleSubtitle()
                scheduleAutoHide()
            }
        })
        assistantControls.addView(sensitivityButton.apply {
            setOnClickListener {
                updateSensitivity(sensitivity.next())
                onSensitivityChanged(sensitivity)
                showOsd("제스처 감도 ${sensitivity.label}", false)
                scheduleAutoHide()
            }
        })
        assistantControls.addView(makeButton("PiP", "Picture in Picture 전환").apply {
            setOnClickListener {
                if (!interactionLocked) onPictureInPicture()
            }
        })

        assistantScroll.isHorizontalScrollBarEnabled = false
        assistantScroll.addView(
            assistantControls,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        addView(
            assistantScroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                bottomMargin = dp(84)
            },
        )
    }

    private fun configureOsdAndUnlock() {
        osd.apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB0000000.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            contentDescription = "전체화면 동영상 상태"
        }
        addView(
            osd,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        unlockButton.visibility = View.GONE
        unlockButton.setOnClickListener { setInteractionLocked(false) }
        addView(
            unlockButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8)
                marginEnd = dp(8)
            },
        )
    }

    internal fun updateSensitivity(value: NativeGestureSensitivity) {
        sensitivity = value
        sensitivityButton.text = "감도 ${value.label}"
        sensitivityButton.updateAccessibility("제스처 감도 변경", value.label)
    }

    internal fun setPictureInPictureMode(inPip: Boolean) {
        pictureInPicture = inPip
        gestureView.isEnabled = !inPip
        gestureView.visibility = if (inPip) View.GONE else View.VISIBLE
        if (inPip) {
            removeCallbacks(autoHide)
            topControls.visibility = View.GONE
            assistantScroll.visibility = View.GONE
            unlockButton.visibility = View.GONE
            osd.visibility = View.GONE
            playerView.hideController()
            controlsVisible = false
        } else if (interactionLocked) {
            setInteractionLocked(true)
        } else {
            showControls()
        }
    }

    internal fun showOsd(message: String, persistent: Boolean) {
        if (pictureInPicture) return
        osd.text = message
        osd.contentDescription = "전체화면 동영상 상태, $message"
        osd.visibility = View.VISIBLE
        osd.removeCallbacks(hideOsd)
        if (!persistent) osd.postDelayed(hideOsd, OSD_TIMEOUT_MS)
    }

    internal fun dispose() {
        removeCallbacks(autoHide)
        osd.removeCallbacks(hideOsd)
        player.removeListener(playerListener)
        gestureView.cancelInteraction()
        keepScreenOn = false
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && pictureInPicture && !activity.isInPictureInPictureMode) {
            setPictureInPictureMode(false)
        }
    }

    private val hideOsd = Runnable { osd.visibility = View.GONE }

    private fun setInteractionLocked(locked: Boolean) {
        interactionLocked = locked
        gestureView.interactionLocked = locked
        removeCallbacks(autoHide)
        osd.removeCallbacks(hideOsd)
        osd.visibility = View.GONE
        if (locked) {
            controlsVisible = false
            topControls.visibility = View.GONE
            assistantScroll.visibility = View.GONE
            playerView.hideController()
            unlockButton.updateAccessibility("화면 잠금 해제", "화면 잠김")
            unlockButton.visibility = View.VISIBLE
        } else {
            unlockButton.visibility = View.GONE
            showControls()
            showOsd("화면 잠금 해제", false)
        }
    }

    private fun showControls() {
        if (pictureInPicture || interactionLocked) return
        controlsVisible = true
        topControls.visibility = View.VISIBLE
        assistantScroll.visibility = View.VISIBLE
        unlockButton.visibility = View.GONE
        playerView.showController()
        scheduleAutoHide()
    }

    private fun hideControls() {
        if (pictureInPicture || interactionLocked) return
        controlsVisible = false
        topControls.visibility = View.GONE
        assistantScroll.visibility = View.GONE
        playerView.hideController()
    }

    private fun toggleControls() {
        if (pictureInPicture || interactionLocked) return
        if (controlsVisible) hideControls() else showControls()
    }

    private fun scheduleAutoHide() {
        removeCallbacks(autoHide)
        if (
            nativeFullscreenShouldAutoHide(
                isPlaying = player.isPlaying,
                controlsVisible = controlsVisible,
                interactionLocked = interactionLocked,
                pictureInPicture = pictureInPicture,
            )
        ) {
            postDelayed(autoHide, CONTROL_TIMEOUT_MS)
        }
    }

    private fun seekBy(deltaMs: Long, label: String) {
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            showOsd("탐색을 지원하지 않는 영상입니다", false)
            return
        }
        val target = nativeFullscreenSeekTargetMs(player.currentPosition, deltaMs, player.duration)
        player.seekTo(target)
        showOsd(label, false)
        scheduleAutoHide()
    }

    private fun cycleSubtitle() {
        val tracks = buildList {
            player.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_TEXT }
                .forEach { group ->
                    for (index in 0 until group.length) add(group to index)
                }
        }
        if (tracks.isEmpty()) {
            subtitleSelection = -1
            subtitleButton.text = "자막 없음"
            subtitleButton.updateAccessibility("자막 변경", "사용 가능한 자막 없음")
            showOsd("내장 자막 트랙이 없습니다", false)
            return
        }

        subtitleSelection += 1
        if (subtitleSelection >= tracks.size) subtitleSelection = -1
        val builder = player.trackSelectionParameters.buildUpon()
        if (subtitleSelection < 0) {
            player.trackSelectionParameters = builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            subtitleButton.text = "자막 OFF"
            subtitleButton.updateAccessibility("자막 변경", "OFF")
            showOsd("자막 OFF", false)
        } else {
            val (group, trackIndex) = tracks[subtitleSelection]
            player.trackSelectionParameters = builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                .build()
            val state = "${subtitleSelection + 1}/${tracks.size}"
            subtitleButton.text = "자막 $state"
            subtitleButton.updateAccessibility("자막 변경", state)
            showOsd("자막 $state", false)
        }
    }

    private fun refreshSpeedLabel() {
        val speed = formatSpeed(player.playbackParameters.speed)
        speedButton.text = "속도 $speed"
        speedButton.updateAccessibility("재생 속도 변경", speed)
    }

    private fun refreshAspectLabel() {
        aspectButton.text = "화면 ${aspectMode.label}"
        aspectButton.updateAccessibility("화면 비율 변경", aspectMode.label)
    }

    private fun refreshOrientationLabel() {
        orientationButton.text = if (orientationLocked) "회전 잠금" else "회전 자동"
        orientationButton.updateAccessibility(
            action = "회전 잠금 설정",
            state = if (orientationLocked) "잠김" else "자동",
        )
    }

    private fun makeButton(
        label: String,
        accessibilityAction: String = label,
        accessibilityState: String? = null,
    ): Button = Button(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 12f
        setAllCaps(false)
        setBackgroundColor(0x99000000.toInt())
        minHeight = dp(40)
        minWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        updateAccessibility(accessibilityAction, accessibilityState)
    }

    private fun Button.updateAccessibility(action: String, state: String?) {
        contentDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || state.isNullOrBlank()) {
            action
        } else {
            "$action, $state"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            stateDescription = state
        }
    }

    private fun formatSpeed(speed: Float): String =
        if (speed == speed.roundToInt().toFloat()) "${speed.roundToInt()}x" else "${speed}x"

    private fun dp(value: Int): Int = (value * density).roundToInt()

    companion object {
        private const val CONTROL_TIMEOUT_MS = 3_000L
        private const val OSD_TIMEOUT_MS = 1_000L
    }
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class NativeFullscreenGestureView(
    context: Context,
    private val activity: Activity,
    private val player: Player,
    private val audioManager: AudioManager,
    private val sensitivityProvider: () -> Float,
    private val onSingleTap: () -> Unit,
    private val onOsd: (String, Boolean) -> Unit,
) : View(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val classifier = NativeFullscreenGestureClassifier(touchSlop)
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!interactionLocked) onSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (interactionLocked) return true
                when (nativeFullscreenDoubleTapAction(e.x, width)) {
                    NativeDoubleTapAction.BACKWARD -> seekBy(-10_000L, "10초 뒤로")
                    NativeDoubleTapAction.FORWARD -> seekBy(10_000L, "10초 앞으로")
                    NativeDoubleTapAction.TOGGLE_PLAYBACK -> togglePlayback()
                }
                return true
            }
        },
    )

    var interactionLocked: Boolean = false
        set(value) {
            field = value
            if (value) cancelInteraction()
        }

    private var downX = 0f
    private var downY = 0f
    private var startPositionMs = 0L
    private var previewTargetMs = 0L
    private var startBrightness = 0.5f
    private var startVolume = 0

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!nativeFullscreenGestureInputAllowed(interactionLocked, isEnabled)) return true
        if (event.pointerCount > 1) {
            cancelInteraction()
            onOsd("핀치 확대는 SurfaceView 안전성을 위해 사용하지 않습니다", false)
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                classifier.reset()
                downX = event.x
                downY = event.y
                startPositionMs = player.currentPosition.coerceAtLeast(0L)
                previewTargetMs = startPositionMs
                startBrightness = currentBrightnessFraction()
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                gestureDetector.onTouchEvent(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (classifier.mode == NativeGestureMode.NONE) {
                    gestureDetector.onTouchEvent(event)
                }
                val mode = classifier.update(
                    deltaX = event.x - downX,
                    deltaY = event.y - downY,
                    downX = downX,
                    widthPx = width,
                )
                when (mode) {
                    NativeGestureMode.HORIZONTAL -> updateSeekPreview(event.x)
                    NativeGestureMode.BRIGHTNESS -> updateBrightness(event.y)
                    NativeGestureMode.VOLUME -> updateVolume(event.y)
                    NativeGestureMode.NONE -> Unit
                }
            }

            MotionEvent.ACTION_UP -> {
                val mode = classifier.mode
                if (mode == NativeGestureMode.NONE) {
                    gestureDetector.onTouchEvent(event)
                } else if (mode == NativeGestureMode.HORIZONTAL &&
                    player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                ) {
                    updateSeekPreview(event.x)
                    player.seekTo(previewTargetMs)
                    val duration = if (player.duration > 0L) nativeFullscreenFormatTime(player.duration) else "--:--"
                    onOsd("이동 ${nativeFullscreenFormatTime(previewTargetMs)} / $duration", false)
                }
                classifier.reset()
            }

            MotionEvent.ACTION_CANCEL -> {
                classifier.reset()
            }
        }
        return true
    }

    fun cancelInteraction() {
        classifier.reset()
    }

    private fun updateSeekPreview(currentX: Float) {
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            onOsd("탐색을 지원하지 않는 영상입니다", false)
            return
        }
        val delta = nativeFullscreenSeekDeltaMs(
            distancePx = currentX - downX,
            widthPx = width,
            durationMs = player.duration,
            sensitivityFactor = sensitivityProvider(),
        )
        previewTargetMs = nativeFullscreenSeekTargetMs(startPositionMs, delta, player.duration)
        onOsd(
            nativeFullscreenSeekPreviewText(
                deltaMs = delta,
                targetMs = previewTargetMs,
                durationMs = player.duration,
            ),
            true,
        )
    }

    private fun updateBrightness(currentY: Float) {
        val target = nativeFullscreenVerticalFraction(
            startFraction = startBrightness,
            distancePx = currentY - downY,
            heightPx = height,
            sensitivityFactor = sensitivityProvider(),
        )
        val attrs = activity.window.attributes
        attrs.screenBrightness = target
        activity.window.attributes = attrs
        onOsd("밝기 ${(target * 100).roundToInt()}%", true)
    }

    private fun updateVolume(currentY: Float) {
        val minVolume = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = nativeFullscreenVolumeLevel(
            startLevel = startVolume,
            minLevel = minVolume,
            maxLevel = maxVolume,
            distancePx = currentY - downY,
            heightPx = height,
            sensitivityFactor = sensitivityProvider(),
        )
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        val percent = if (maxVolume > minVolume) {
            ((target - minVolume) * 100f / (maxVolume - minVolume)).roundToInt()
        } else {
            0
        }
        onOsd("볼륨 $percent%", true)
    }

    private fun seekBy(deltaMs: Long, label: String) {
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            onOsd("탐색을 지원하지 않는 영상입니다", false)
            return
        }
        player.seekTo(nativeFullscreenSeekTargetMs(player.currentPosition, deltaMs, player.duration))
        onOsd(label, false)
    }

    private fun togglePlayback() {
        if (!player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) return
        if (player.playWhenReady) {
            player.pause()
            onOsd("일시정지", false)
        } else {
            player.play()
            onOsd("재생", false)
        }
    }

    private fun currentBrightnessFraction(): Float {
        val override = activity.window.attributes.screenBrightness
        if (override in 0f..1f) return override
        val system = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (system / 255f).coerceIn(0.01f, 1f)
    }
}
