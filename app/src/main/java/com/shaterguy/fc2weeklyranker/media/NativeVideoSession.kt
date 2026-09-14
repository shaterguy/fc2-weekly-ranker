package com.shaterguy.fc2weeklyranker.media

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import com.shaterguy.fc2weeklyranker.data.SettingsStore
import com.shaterguy.fc2weeklyranker.data.VideoEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
        val assistant: NativeFullscreenAssistantView,
        val fullscreenView: PlayerView,
        val orientationListener: Player.Listener,
        val previousOrientation: Int?,
        val previousBrightness: Float?,
        val backCallback: OnBackPressedCallback?,
    )

    private val gate = NativeFullscreenSessionGate()
    private val compactHandles = linkedMapOf<String, CompactHandle>()
    private val resumeStates = linkedMapOf<String, NativePlaybackResume>()
    private val settingsStore = SettingsStore(context.applicationContext)
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var gestureSensitivity = NativeGestureSensitivity.NORMAL
    private var activeSession: ActiveSession? = null
    private var released = false

    init {
        controllerScope.launch {
            settingsStore.fullscreenGestureSensitivity.collectLatest { stored ->
                gestureSensitivity = NativeGestureSensitivity.fromStored(stored)
                activeSession?.assistant?.updateSensitivity(gestureSensitivity)
            }
        }
    }

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
        val hostActivity = activity ?: return
        val current = activeSession
        if (current != null) {
            if (current.identity.videoId == video.id && autoPlay) current.player.play()
            return
        }

        val identity = gate.begin(video.id) ?: return
        val compactHandle = compactHandles[video.id]
        val player = compactHandle?.player ?: obtainPlayer(video)
        val playerCreatedForSession = compactHandle == null
        val previousOrientation = hostActivity.requestedOrientation
        val previousBrightness = hostActivity.window.attributes.screenBrightness

        val fullscreenView = PlayerView(context).apply {
            useController = true
            contentDescription = "내장플레이어 전체화면 ${video.ordinal + 1}"
        }
        lateinit var assistant: NativeFullscreenAssistantView
        val orientationListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (gate.isCurrent(identity) && !assistant.orientationLocked) {
                    hostActivity.applyNativeVideoOrientation(videoSize)
                }
            }
        }
        val backCallback = (hostActivity as? ComponentActivity)?.let { componentActivity ->
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val session = activeSession
                    if (session?.identity != identity) return
                    if (session.assistant.interactionLocked) {
                        session.assistant.showOsd("화면 잠금을 먼저 해제하세요", false)
                    } else {
                        finishFullscreen(identity)
                    }
                }
            }.also { componentActivity.onBackPressedDispatcher.addCallback(it) }
        }

        assistant = NativeFullscreenAssistantView(
            context = context,
            activity = hostActivity,
            player = player,
            playerView = fullscreenView,
            initialSensitivity = gestureSensitivity,
            onClose = { finishFullscreen(identity) },
            onPictureInPicture = { enterPictureInPicture(identity) },
            onSensitivityChanged = { value ->
                gestureSensitivity = value
                controllerScope.launch { settingsStore.setFullscreenGestureSensitivity(value.name) }
            },
            onOrientationLockChanged = { locked ->
                if (gate.isCurrent(identity)) {
                    if (locked) {
                        hostActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    } else {
                        hostActivity.applyNativeVideoOrientation(player.videoSize)
                    }
                }
            },
        )

        val session = ActiveSession(
            identity = identity,
            player = player,
            assistant = assistant,
            fullscreenView = fullscreenView,
            orientationListener = orientationListener,
            previousOrientation = previousOrientation,
            previousBrightness = previousBrightness,
            backCallback = backCallback,
        )
        activeSession = session

        runCatching {
            hostActivity.addContentView(
                assistant,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            player.addListener(orientationListener)
            hostActivity.applyNativeVideoOrientation(player.videoSize)
            val latestCompact = compactHandles[video.id]?.takeIf { it.player === player }
            if (latestCompact != null) {
                PlayerView.switchTargetView(player, latestCompact.view, fullscreenView)
                latestCompact.view.setFullscreenButtonState(true)
            } else {
                fullscreenView.player = player
            }
            fullscreenView.setFullscreenButtonClickListener { isFullscreen ->
                if (!isFullscreen && !assistant.interactionLocked) finishFullscreen(identity)
            }
            fullscreenView.setFullscreenButtonState(true)
            if (autoPlay) player.play()
            hideNativeVideoSystemBars(hostActivity)
        }.onFailure {
            player.removeListener(orientationListener)
            backCallback?.remove()
            (assistant.parent as? ViewGroup)?.removeView(assistant)
            activeSession = null
            gate.finish(identity)
            restoreFullscreenWindowState(hostActivity, previousOrientation, previousBrightness)
            if (playerCreatedForSession) player.release()
            throw it
        }
    }

    private fun enterPictureInPicture(identity: NativeFullscreenIdentity) {
        val session = activeSession ?: return
        val hostActivity = activity ?: return
        if (!gate.isCurrent(identity) || session.identity != identity) return
        if (!hostActivity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            session.assistant.showOsd("PiP를 지원하지 않는 기기입니다", false)
            return
        }

        val builder = PictureInPictureParams.Builder()
        val size = session.player.videoSize
        if (size.width > 0 && size.height > 0) {
            val ratio = size.width.toDouble() / size.height.toDouble()
            if (ratio in (1.0 / 2.39)..2.39) {
                builder.setAspectRatio(Rational(size.width, size.height))
            }
        }
        val entered = runCatching { hostActivity.enterPictureInPictureMode(builder.build()) }.getOrDefault(false)
        if (entered) {
            session.assistant.setPictureInPictureMode(true)
        } else {
            session.assistant.showOsd("PiP 전환에 실패했습니다", false)
        }
    }

    private fun finishFullscreen(identity: NativeFullscreenIdentity) {
        val session = activeSession ?: return
        if (!gate.isCurrent(identity) || session.identity != identity) return
        val hostActivity = activity

        session.player.removeListener(session.orientationListener)
        session.backCallback?.remove()
        session.assistant.dispose()
        if (hostActivity != null) {
            restoreFullscreenWindowState(hostActivity, session.previousOrientation, session.previousBrightness)
            showNativeVideoSystemBars(hostActivity)
        }
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

        (session.assistant.parent as? ViewGroup)?.removeView(session.assistant)
        activeSession = null
        gate.finish(identity)
    }

    internal fun release() {
        if (released) return
        released = true
        controllerScope.cancel()
        val releasedPlayers = mutableSetOf<ExoPlayer>()
        activeSession?.let { session ->
            session.player.removeListener(session.orientationListener)
            session.backCallback?.remove()
            session.assistant.dispose()
            activity?.let {
                restoreFullscreenWindowState(it, session.previousOrientation, session.previousBrightness)
                showNativeVideoSystemBars(it)
            }
            session.fullscreenView.player = null
            (session.assistant.parent as? ViewGroup)?.removeView(session.assistant)
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

private fun restoreFullscreenWindowState(
    activity: Activity,
    previousOrientation: Int?,
    previousBrightness: Float?,
) {
    previousOrientation?.let { activity.requestedOrientation = it }
    previousBrightness?.let { brightness ->
        val attrs = activity.window.attributes
        attrs.screenBrightness = brightness
        activity.window.attributes = attrs
    }
}

@Suppress("DEPRECATION")
private fun hideNativeVideoSystemBars(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
    } else {
        activity.window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}

@Suppress("DEPRECATION")
private fun showNativeVideoSystemBars(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.window.insetsController?.show(WindowInsets.Type.systemBars())
    } else {
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }
}
