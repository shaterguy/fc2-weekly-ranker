package com.shaterguy.fc2weeklyranker.media

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.shaterguy.fc2weeklyranker.data.VideoEntity
import kotlin.math.abs

internal data class NativeFullscreenIdentity(
    val sessionId: Long,
    val videoId: String,
)

internal class NativeFullscreenSessionGate {
    private var nextSessionId = 0L
    var active: NativeFullscreenIdentity? = null
        private set

    fun begin(videoId: String): NativeFullscreenIdentity? {
        if (active != null) return null
        return NativeFullscreenIdentity(++nextSessionId, videoId).also { active = it }
    }

    fun isCurrent(identity: NativeFullscreenIdentity): Boolean = active == identity

    fun finish(identity: NativeFullscreenIdentity): Boolean {
        if (!isCurrent(identity)) return false
        active = null
        return true
    }

    fun reset() {
        active = null
    }
}

private data class NativePlaybackResume(
    val positionMs: Long,
    val playWhenReady: Boolean,
)

@OptIn(UnstableApi::class)
internal class NativeVideoSessionController(
    private val context: Context,
    private val activity: Activity?,
) {
    private data class CompactHandle(
        val video: VideoEntity,
        val player: ExoPlayer,
        val view: PlayerView,
    )

    private data class ActiveSession(
        val identity: NativeFullscreenIdentity,
        val player: ExoPlayer,
        val dialog: Dialog,
        val fullscreenView: PlayerView,
        val orientationListener: Player.Listener,
        val previousOrientation: Int?,
    )

    private val gate = NativeFullscreenSessionGate()
    private val compactHandles = linkedMapOf<String, CompactHandle>()
    private val resumeStates = linkedMapOf<String, NativePlaybackResume>()
    private var activeSession: ActiveSession? = null
    private var released = false

    internal fun obtainPlayer(video: VideoEntity): ExoPlayer {
        check(!released) { "NativeVideoSessionController is already released" }
        activeSession?.takeIf { it.identity.videoId == video.id }?.let { return it.player }
        compactHandles[video.id]?.let { return it.player }
        val resume = resumeStates.remove(video.id)
        return createNativeVideoPlayer(context, video, resume)
    }

    internal fun bindCompact(video: VideoEntity, player: ExoPlayer, view: PlayerView) {
        if (released) return
        compactHandles[video.id] = CompactHandle(video = video, player = player, view = view)
        view.setFullscreenButtonClickListener { isFullscreen ->
            if (isFullscreen) openFullscreen(video = video, autoPlay = false)
        }
        if (isActiveFullscreenPlayer(video.id, player)) {
            if (view.player === player) view.player = null
            view.setFullscreenButtonState(true)
        } else {
            if (view.player !== player) view.player = player
            view.setFullscreenButtonState(false)
        }
    }

    internal fun unbindCompact(videoId: String, player: ExoPlayer, view: PlayerView) {
        val current = compactHandles[videoId]
        if (current?.player === player && current.view === view) {
            compactHandles.remove(videoId)
        } else if (current?.player === player) {
            view.player = null
            return
        }
        view.player = null
        if (!isActiveFullscreenPlayer(videoId, player)) {
            if (resumeStates.containsKey(videoId)) resumeStates[videoId] = player.resumeSnapshot()
            player.release()
        }
    }

    internal fun openFullscreen(video: VideoEntity, autoPlay: Boolean) {
        if (released) return
        val current = activeSession
        if (current != null) {
            if (current.identity.videoId == video.id && autoPlay) current.player.play()
            return
        }

        val identity = gate.begin(video.id) ?: return
        val compactHandle = compactHandles[video.id]
        val player = compactHandle?.player ?: obtainPlayer(video)
        val playerCreatedForSession = compactHandle == null
        val previousOrientation = activity?.requestedOrientation
        val fullscreenView = PlayerView(context).apply {
            useController = true
            contentDescription = "내장플레이어 전체화면 ${video.ordinal + 1}"
        }
        val fullscreenContainer = NativeFullscreenSeekContainer(context, player).apply {
            addView(
                fullscreenView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        val orientationListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (gate.isCurrent(identity)) activity?.applyNativeVideoOrientation(videoSize)
            }
        }
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val session = ActiveSession(
            identity = identity,
            player = player,
            dialog = dialog,
            fullscreenView = fullscreenView,
            orientationListener = orientationListener,
            previousOrientation = previousOrientation,
        )
        activeSession = session

        dialog.setContentView(
            fullscreenContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        fullscreenView.setFullscreenButtonClickListener { isFullscreen ->
            if (!isFullscreen) dialog.dismiss()
        }
        dialog.setOnShowListener {
            if (!gate.isCurrent(identity)) return@setOnShowListener
            player.addListener(orientationListener)
            activity?.applyNativeVideoOrientation(player.videoSize)
            val latestCompact = compactHandles[video.id]?.takeIf { it.player === player }
            if (latestCompact != null) {
                PlayerView.switchTargetView(player, latestCompact.view, fullscreenView)
                latestCompact.view.setFullscreenButtonState(true)
            } else {
                fullscreenView.player = player
            }
            fullscreenView.setFullscreenButtonState(true)
            if (autoPlay) player.play()
            hideNativeVideoSystemBars(dialog)
        }
        dialog.setOnDismissListener { finishFullscreen(identity) }

        runCatching { dialog.show() }.onFailure {
            dialog.setOnDismissListener(null)
            activeSession = null
            gate.finish(identity)
            if (playerCreatedForSession) player.release()
            throw it
        }
    }

    private fun finishFullscreen(identity: NativeFullscreenIdentity) {
        val session = activeSession ?: return
        if (!gate.isCurrent(identity) || session.identity != identity) return

        session.player.removeListener(session.orientationListener)
        session.previousOrientation?.let { previous -> activity?.requestedOrientation = previous }
        resumeStates[identity.videoId] = session.player.resumeSnapshot()

        val compactHandle = compactHandles[identity.videoId]?.takeIf { it.player === session.player }
        if (compactHandle != null) {
            PlayerView.switchTargetView(session.player, session.fullscreenView, compactHandle.view)
            compactHandle.view.setFullscreenButtonState(false)
            session.fullscreenView.player = null
        } else {
            session.player.pause()
            session.fullscreenView.player = null
            session.player.release()
        }

        activeSession = null
        gate.finish(identity)
    }

    internal fun release() {
        if (released) return
        released = true
        val releasedPlayers = mutableSetOf<ExoPlayer>()
        activeSession?.let { session ->
            session.dialog.setOnDismissListener(null)
            session.player.removeListener(session.orientationListener)
            session.previousOrientation?.let { previous -> activity?.requestedOrientation = previous }
            session.fullscreenView.player = null
            runCatching { session.dialog.dismiss() }
            if (releasedPlayers.add(session.player)) session.player.release()
        }
        activeSession = null
        gate.reset()
        compactHandles.values.forEach { handle ->
            handle.view.player = null
            if (releasedPlayers.add(handle.player)) handle.player.release()
        }
        compactHandles.clear()
        resumeStates.clear()
    }

    private fun isActiveFullscreenPlayer(videoId: String, player: ExoPlayer): Boolean =
        activeSession?.let { it.identity.videoId == videoId && it.player === player } == true
}

@OptIn(UnstableApi::class)
@Composable
internal fun rememberNativeVideoSessionController(scopeKey: Any): NativeVideoSessionController {
    val context = LocalContext.current
    val activity = remember(context) { context.findNativeVideoActivity() }
    val controller = remember(scopeKey, context, activity) { NativeVideoSessionController(context, activity) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

@OptIn(UnstableApi::class)
@Composable
internal fun CoordinatedNativeVideoPlayer(
    video: VideoEntity,
    controller: NativeVideoSessionController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(video.id, controller) { controller.obtainPlayer(video) }
    val compactView = remember(video.id, controller) {
        PlayerView(context).apply {
            useController = true
        }
    }

    DisposableEffect(video.id, player, compactView, controller) {
        controller.bindCompact(video, player, compactView)
        onDispose { controller.unbindCompact(video.id, player, compactView) }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(240.dp),
        factory = { compactView },
        update = { controller.bindCompact(video, player, it) },
    )
}

@OptIn(UnstableApi::class)
private fun createNativeVideoPlayer(
    context: Context,
    video: VideoEntity,
    resume: NativePlaybackResume?,
): ExoPlayer {
    val headers = linkedMapOf("Referer" to video.referer, "User-Agent" to video.userAgent)
    CookieManager.getInstance().getCookie(video.url)?.takeIf(String::isNotBlank)?.let { headers["Cookie"] = it }
    val dataSource = DefaultHttpDataSource.Factory()
        .setUserAgent(video.userAgent)
        .setDefaultRequestProperties(headers)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        .build()
        .apply {
            setMediaItem(MediaItem.fromUri(video.url))
            resume?.let {
                seekTo(it.positionMs.coerceAtLeast(0L))
                playWhenReady = it.playWhenReady
            }
            prepare()
        }
}

private fun ExoPlayer.resumeSnapshot(): NativePlaybackResume = NativePlaybackResume(
    positionMs = currentPosition.coerceAtLeast(0L),
    playWhenReady = playWhenReady,
)

private fun Context.findNativeVideoActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findNativeVideoActivity()
    else -> null
}

private fun Activity.applyNativeVideoOrientation(videoSize: VideoSize) {
    val requested = when (
        classifyVideoFrameOrientation(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
    ) {
        VideoFrameOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        VideoFrameOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        null -> return
    }
    if (requestedOrientation != requested) requestedOrientation = requested
}

@SuppressLint("ClickableViewAccessibility")
private class NativeFullscreenSeekContainer(
    context: Context,
    private val player: Player,
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val controllerExclusionHeightPx = (96f * resources.displayMetrics.density).toInt()
    private var downX = 0f
    private var downY = 0f
    private var startPositionMs = 0L
    private var seeking = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                startPositionMs = player.currentPosition
                seeking = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return false
                if (startsInControllerZone()) return false
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    seeking = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> seeking = false
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!seeking) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> seekForHorizontalDrag(event.x)
            MotionEvent.ACTION_UP -> {
                seekForHorizontalDrag(event.x)
                seeking = false
            }
            MotionEvent.ACTION_CANCEL -> seeking = false
        }
        return true
    }

    private fun startsInControllerZone(): Boolean =
        height > 0 && downY >= (height - controllerExclusionHeightPx).coerceAtLeast(0)

    private fun seekForHorizontalDrag(currentX: Float) {
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        val deltaMs = fullscreenSeekDeltaMs(currentX - downX, width)
        val durationMs = player.duration.takeIf { it >= 0L }
        player.seekTo(clampFullscreenSeekPosition(startPositionMs, deltaMs, durationMs))
    }
}

@Suppress("DEPRECATION")
private fun hideNativeVideoSystemBars(dialog: Dialog) {
    val window = dialog.window ?: return
    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
    } else {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
