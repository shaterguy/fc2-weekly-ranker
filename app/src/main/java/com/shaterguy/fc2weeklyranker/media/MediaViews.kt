package com.shaterguy.fc2weeklyranker.media

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.math.abs

private const val MEDIA_PROBE_SCRIPT = "(function(){var videos=Array.from(document.querySelectorAll('video')).map(function(v){var s=v.querySelector('source[src]');return v.currentSrc||v.src||(s?(s.currentSrc||s.src):'');}).filter(Boolean);if(videos.length){return JSON.stringify(videos);}return JSON.stringify(Array.from(document.querySelectorAll('source[src]')).map(function(s){return s.currentSrc||s.src||'';}).filter(Boolean));})()"
private val MEDIA_PROBE_DELAYS_MS = longArrayOf(0L, 250L, 750L, 1_500L, 3_000L, 6_000L, 10_000L)
internal const val FULLSCREEN_MAX_SEEK_MS = 10_000L

internal enum class VideoFrameOrientation {
    LANDSCAPE,
    PORTRAIT,
}

internal fun classifyVideoFrameOrientation(
    width: Int,
    height: Int,
    pixelWidthHeightRatio: Float,
): VideoFrameOrientation? {
    if (width <= 0 || height <= 0 || pixelWidthHeightRatio <= 0f || !pixelWidthHeightRatio.isFinite()) return null
    val displayWidth = width.toDouble() * pixelWidthHeightRatio.toDouble()
    return when {
        displayWidth > height.toDouble() -> VideoFrameOrientation.LANDSCAPE
        displayWidth < height.toDouble() -> VideoFrameOrientation.PORTRAIT
        else -> null
    }
}

internal fun fullscreenSeekDeltaMs(horizontalDistancePx: Float, containerWidthPx: Int): Long {
    if (containerWidthPx <= 0 || !horizontalDistancePx.isFinite()) return 0L
    val fraction = (horizontalDistancePx / containerWidthPx.toFloat()).coerceIn(-1f, 1f)
    return (fraction * FULLSCREEN_MAX_SEEK_MS.toFloat()).toLong()
}

internal fun clampFullscreenSeekPosition(startPositionMs: Long, deltaMs: Long, durationMs: Long?): Long {
    val target = (startPositionMs + deltaMs).coerceAtLeast(0L)
    return if (durationMs != null && durationMs >= 0L) target.coerceAtMost(durationMs) else target
}

@OptIn(UnstableApi::class)
@Composable
fun NativeVideoPlayer(video: VideoEntity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val player = remember(video.id) {
        val headers = linkedMapOf("Referer" to video.referer, "User-Agent" to video.userAgent)
        CookieManager.getInstance().getCookie(video.url)?.takeIf(String::isNotBlank)?.let { headers["Cookie"] = it }
        val dataSource = DefaultHttpDataSource.Factory().setUserAgent(video.userAgent).setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)).build().apply {
            setMediaItem(MediaItem.fromUri(video.url))
            prepare()
        }
    }
    val dialogHolder = remember(video.id) { arrayOfNulls<Dialog>(1) }
    val compactView = remember(video.id) {
        PlayerView(context).apply {
            this.player = player
            useController = true
        }
    }

    fun openFullscreen() {
        if (dialogHolder[0] != null) return
        val previousOrientation = activity?.requestedOrientation
        val fullscreenView = PlayerView(context).apply {
            useController = true
        }
        val fullscreenContainer = FullscreenSeekContainer(context, player).apply {
            addView(
                fullscreenView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        val orientationListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (dialogHolder[0] != null) activity?.applyVideoOrientation(videoSize)
            }
        }
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialogHolder[0] = dialog
        dialog.setContentView(
            fullscreenContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        fullscreenView.setFullscreenButtonClickListener { isFullscreen ->
            if (!isFullscreen) dialog.dismiss()
        }
        dialog.setOnShowListener {
            player.addListener(orientationListener)
            activity?.applyVideoOrientation(player.videoSize)
            PlayerView.switchTargetView(player, compactView, fullscreenView)
            compactView.setFullscreenButtonState(true)
            fullscreenView.setFullscreenButtonState(true)
            hideSystemBars(dialog)
        }
        dialog.setOnDismissListener {
            if (dialogHolder[0] === dialog) dialogHolder[0] = null
            player.removeListener(orientationListener)
            previousOrientation?.let { activity?.requestedOrientation = it }
            PlayerView.switchTargetView(player, fullscreenView, compactView)
            compactView.setFullscreenButtonState(false)
            fullscreenView.player = null
        }
        dialog.show()
    }

    compactView.setFullscreenButtonClickListener { isFullscreen ->
        if (isFullscreen) openFullscreen()
    }

    DisposableEffect(player, compactView) {
        onDispose {
            dialogHolder[0]?.dismiss()
            dialogHolder[0] = null
            compactView.player = null
            player.release()
        }
    }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(240.dp),
        factory = { compactView },
        update = { it.player = player },
    )
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Activity.applyVideoOrientation(videoSize: VideoSize) {
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
private class FullscreenSeekContainer(
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
        val deltaMs = fullscreenSeekDeltaMs(currentX - downX, width)
        val durationMs = player.duration.takeIf { it >= 0L }
        player.seekTo(clampFullscreenSeekPosition(startPositionMs, deltaMs, durationMs))
    }
}

@Suppress("DEPRECATION")
private fun hideSystemBars(dialog: Dialog) {
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

@Composable
fun RestrictedIframePlayer(video: VideoEntity, onMediaDiscovered: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val handler = remember(video.id) { Handler(Looper.getMainLooper()) }
    val iframeHost = remember(video.url) { runCatching { URI(video.url).host.lowercase() }.getOrNull() }
    val networkCandidates = remember(video.id) { linkedMapOf<String, String>() }
    val publishedCandidateKeys = remember(video.id) { linkedSetOf<String>() }
    val probeWindowStarted = remember(video.id) { booleanArrayOf(false) }
    val released = remember(video.id) { booleanArrayOf(false) }

    fun publish(candidates: List<String>) {
        if (released[0]) return
        collectNewMediaCandidates(publishedCandidateKeys, candidates).forEach(onMediaDiscovered)
    }

    fun probeDom(view: WebView) {
        if (released[0]) return
        view.evaluateJavascript(MEDIA_PROBE_SCRIPT) { raw ->
            if (released[0]) return@evaluateJavascript
            val domCandidates = decodeMediaCandidates(raw)
            publish(domCandidates + networkCandidates.values)
        }
    }

    fun scheduleProbeWindow(view: WebView) {
        if (released[0] || probeWindowStarted[0]) return
        probeWindowStarted[0] = true
        MEDIA_PROBE_DELAYS_MS.forEach { delayMillis ->
            handler.postDelayed(
                {
                    if (!released[0]) probeDom(view)
                },
                delayMillis,
            )
        }
    }

    val webView = remember(video.id) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = video.userAgent
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val uri = request.url
                    return uri.scheme != "https" || uri.host?.lowercase() != iframeHost
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val candidate = request.url.toString()
                    if (request.url.scheme == "https" && looksLikeMedia(candidate)) {
                        handler.post {
                            if (released[0]) return@post
                            networkCandidates[canonicalMediaKey(candidate)] = candidate
                            publish(networkCandidates.values.toList())
                            scheduleProbeWindow(view)
                        }
                    }
                    return null
                }

                override fun onPageFinished(view: WebView, url: String) {
                    scheduleProbeWindow(view)
                }
            }
            loadUrl(video.url, mapOf("Referer" to video.referer))
        }
    }
    DisposableEffect(webView) {
        onDispose {
            released[0] = true
            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.destroy()
        }
    }
    AndroidView(modifier = modifier, factory = { webView })
}

internal fun decodeMediaCandidates(raw: String?): List<String> = runCatching {
    if (raw.isNullOrBlank() || raw == "null") return@runCatching emptyList()
    val decoded = JSONObject("{\"value\":$raw}").getString("value")
    val array = JSONArray(decoded)
    buildList {
        for (i in 0 until array.length()) {
            array.optString(i).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}.getOrDefault(emptyList())

internal fun collectNewMediaCandidates(publishedKeys: MutableSet<String>, urls: List<String>): List<String> =
    normalizeMediaCandidates(urls).filter { publishedKeys.add(canonicalMediaKey(it)) }

internal fun normalizeMediaCandidates(urls: List<String>): List<String> {
    val seen = linkedSetOf<String>()
    return urls.filter { it.startsWith("https://") && looksLikeMedia(it) }
        .filter { seen.add(canonicalMediaKey(it)) }
}

private fun canonicalMediaKey(url: String): String = runCatching {
    val uri = URI(url)
    val scheme = uri.scheme?.lowercase() ?: "https"
    val host = uri.host?.lowercase().orEmpty()
    val path = uri.rawPath.orEmpty().trimEnd('/')
    "$scheme://$host$path"
}.getOrElse { url.substringBefore('?') }

internal fun looksLikeMedia(url: String): Boolean {
    val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
    return path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".webm")
}
