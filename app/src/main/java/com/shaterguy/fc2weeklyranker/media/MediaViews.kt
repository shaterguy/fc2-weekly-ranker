package com.shaterguy.fc2weeklyranker.media

import android.app.Dialog
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.shaterguy.fc2weeklyranker.data.VideoEntity
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

@OptIn(UnstableApi::class)
@Composable
fun NativeVideoPlayer(video: VideoEntity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
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
        val fullscreenView = PlayerView(context).apply {
            useController = true
        }
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialogHolder[0] = dialog
        dialog.setContentView(
            fullscreenView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        fullscreenView.setFullscreenButtonClickListener { isFullscreen ->
            if (!isFullscreen) dialog.dismiss()
        }
        dialog.setOnShowListener {
            PlayerView.switchTargetView(player, compactView, fullscreenView)
            compactView.setFullscreenButtonState(true)
            fullscreenView.setFullscreenButtonState(true)
            hideSystemBars(dialog)
        }
        dialog.setOnDismissListener {
            if (dialogHolder[0] === dialog) dialogHolder[0] = null
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
    val handler = remember { Handler(Looper.getMainLooper()) }
    val iframeHost = remember(video.url) { runCatching { URI(video.url).host.lowercase() }.getOrNull() }
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
                        handler.post { onMediaDiscovered(candidate) }
                    }
                    return null
                }

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(
                        "(function(){return JSON.stringify(Array.from(document.querySelectorAll('video,source')).map(function(e){return e.currentSrc||e.src||'';}).filter(Boolean));})()",
                    ) { raw ->
                        runCatching {
                            val decoded = JSONObject("{\"value\":$raw}").getString("value")
                            val array = JSONArray(decoded)
                            for (i in 0 until array.length()) {
                                val candidate = array.optString(i)
                                if (candidate.startsWith("https://") && looksLikeMedia(candidate)) onMediaDiscovered(candidate)
                            }
                        }
                    }
                }
            }
            loadUrl(video.url, mapOf("Referer" to video.referer))
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.destroy()
        }
    }
    AndroidView(modifier = modifier, factory = { webView })
}

private fun looksLikeMedia(url: String): Boolean {
    val path = runCatching { Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
    return path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".webm")
}
