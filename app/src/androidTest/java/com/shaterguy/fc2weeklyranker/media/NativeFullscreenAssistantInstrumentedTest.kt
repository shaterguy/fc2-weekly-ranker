package com.shaterguy.fc2weeklyranker.media

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shaterguy.fc2weeklyranker.MainActivity
import com.shaterguy.fc2weeklyranker.data.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeFullscreenAssistantInstrumentedTest {
    @Test
    fun compactFullscreenAssistantReusesPlayerAndRestoresCompactTarget() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = NativeVideoSessionController(activity, activity)
                val video = VideoEntity(
                    id = "native-fullscreen-runtime",
                    postId = "native-fullscreen-runtime-post",
                    url = DECODER_MEDIA_URL,
                    referer = "https://example.test/native-fullscreen",
                    userAgent = "FC2WeeklyRankerNativeFullscreenTest/1.0",
                    sourceKind = "DIRECT",
                    ordinal = 0,
                    discoveredAtEpochMillis = 1L,
                )
                val player: ExoPlayer = controller.obtainPlayer(video)
                val compact = PlayerView(activity).apply { useController = true }

                try {
                    controller.bindCompact(video, player, compact)
                    assertSame("compact view must own the production player before fullscreen", player, compact.player)

                    controller.openFullscreen(video, autoPlay = false)
                    val assistant = activity.window.decorView.findFirstView<NativeFullscreenAssistantView>()
                    assertNotNull("fullscreen assistant overlay was not attached", assistant)
                    assistant!!
                    assertSame("fullscreen must reuse the compact ExoPlayer instance", player, assistant.playerView.player)
                    assertNull("compact target must detach while fullscreen owns the player", compact.player)

                    assistant.findButtonStartingWith("속도 ").performClick()
                    assertEquals(1.25f, player.playbackParameters.speed, 0.0001f)

                    assistant.findButtonStartingWith("화면 맞춤").performClick()
                    assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, assistant.playerView.resizeMode)

                    val sensitivityBefore = assistant.findButtonStartingWith("감도 ").text.toString()
                    assistant.findButtonStartingWith("감도 ").performClick()
                    val sensitivityAfter = assistant.findButtonStartingWith("감도 ").text.toString()
                    assertTrue("sensitivity control must advance to another persisted mode", sensitivityAfter != sensitivityBefore)

                    assistant.findButtonStartingWith("화면 잠금").performClick()
                    assertTrue("screen lock must enter locked interaction state", assistant.interactionLocked)
                    assistant.findButtonStartingWith("화면 잠금 해제").performClick()
                    assertFalse("unlock control must restore interaction", assistant.interactionLocked)

                    assistant.findButtonStartingWith("닫기").performClick()
                    assertNull("fullscreen overlay must detach on close", assistant.parent)
                    assertSame("closing fullscreen must return the same player to compact view", player, compact.player)
                } finally {
                    controller.unbindCompact(video.id, player, compact)
                    controller.release()
                }
            }
        }
    }

    private inline fun <reified T : View> View.findFirstView(): T? {
        if (this is T) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findFirstView<T>()?.let { return it }
        }
        return null
    }

    private fun View.findButtonStartingWith(prefix: String): Button {
        val match = when (this) {
            is Button -> takeIf { it.text.toString().startsWith(prefix) }
            else -> null
        }
        if (match != null) return match
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                runCatching { return getChildAt(index).findButtonStartingWith(prefix) }
            }
        }
        error("button not found: $prefix")
    }

    companion object {
        private const val DECODER_MEDIA_URL =
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
    }
}
