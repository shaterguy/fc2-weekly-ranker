package com.shaterguy.fc2weeklyranker.media

import android.content.pm.ActivityInfo
import android.os.Build
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
                val previousOrientation = activity.requestedOrientation
                val previousBrightness = activity.window.attributes.screenBrightness

                try {
                    controller.bindCompact(video, player, compact)
                    assertSame("compact view must own the production player before fullscreen", player, compact.player)

                    controller.openFullscreen(video, autoPlay = false)
                    val assistant = activity.window.decorView.findFirstView(NativeFullscreenAssistantView::class.java)
                    assertNotNull("fullscreen assistant overlay was not attached", assistant)
                    assistant!!
                    assertSame("fullscreen must reuse the compact ExoPlayer instance", player, assistant.playerView.player)
                    assertNull("compact target must detach while fullscreen owns the player", compact.player)
                    assertTrue(
                        "paused fullscreen must keep primary controls available",
                        assistant.requireButtonStartingWith("닫기").isShown,
                    )

                    val customButtons = assistant.collectLabeledButtons()
                    assertTrue("fullscreen assistant must expose custom controls", customButtons.size >= 10)
                    customButtons.forEach { button ->
                        assertTrue(
                            "custom fullscreen button must expose contentDescription: ${button.text}",
                            !button.contentDescription.isNullOrBlank(),
                        )
                    }

                    assistant.requireButtonStartingWith("속도 ").performClick()
                    assertEquals(1.25f, player.playbackParameters.speed, 0.0001f)
                    assistant.requireButtonStartingWith("속도 ").assertAccessibilityState("1.25x")

                    val aspect = assistant.requireButtonStartingWith("화면 맞춤")
                    aspect.performClick()
                    assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, assistant.playerView.resizeMode)
                    assistant.requireButtonStartingWith("화면 확대").assertAccessibilityState("확대")
                    assistant.requireButtonStartingWith("화면 확대").performClick()
                    assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, assistant.playerView.resizeMode)
                    assistant.requireButtonStartingWith("화면 채움").assertAccessibilityState("채움")
                    assistant.requireButtonStartingWith("화면 채움").performClick()
                    assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, assistant.playerView.resizeMode)
                    assistant.requireButtonStartingWith("화면 맞춤").assertAccessibilityState("맞춤")

                    val sensitivityBefore = assistant.requireButtonStartingWith("감도 ").text.toString()
                    assistant.requireButtonStartingWith("감도 ").performClick()
                    val sensitivityAfter = assistant.requireButtonStartingWith("감도 ").text.toString()
                    assertTrue("sensitivity control must advance to another persisted mode", sensitivityAfter != sensitivityBefore)

                    val orientation = assistant.requireButtonStartingWith("회전 자동")
                    orientation.assertAccessibilityState("자동")
                    orientation.performClick()
                    assertTrue("orientation lock state must be enabled", assistant.orientationLocked)
                    assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, activity.requestedOrientation)
                    assistant.requireButtonStartingWith("회전 잠금").assertAccessibilityState("잠김")

                    assistant.requireButtonStartingWith("화면 잠금").performClick()
                    assertTrue("screen lock must enter locked interaction state", assistant.interactionLocked)
                    assertFalse(
                        "general controls must be hidden while interaction is locked",
                        assistant.requireButtonStartingWith("속도 ").isShown,
                    )
                    val unlock = assistant.requireButtonStartingWith("화면 잠금 해제")
                    assertTrue("unlock control must remain visible while locked", unlock.isShown)
                    unlock.assertAccessibilityState("화면 잠김")
                    unlock.performClick()
                    assertFalse("unlock control must restore interaction", assistant.interactionLocked)

                    assistant.setPictureInPictureMode(true)
                    assertSame("PiP presentation state must keep the same ExoPlayer", player, assistant.playerView.player)
                    assistant.setPictureInPictureMode(false)
                    assertSame("returning from PiP presentation state must keep the same ExoPlayer", player, assistant.playerView.player)

                    activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.23f }
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertNull("fullscreen overlay must detach on back", assistant.parent)
                    assertSame("closing fullscreen must return the same player to compact view", player, compact.player)
                    assertEquals(previousOrientation, activity.requestedOrientation)
                    assertEquals(previousBrightness, activity.window.attributes.screenBrightness, 0.0001f)

                    controller.openFullscreen(video, autoPlay = false)
                    val repeated = activity.window.decorView.findFirstView(NativeFullscreenAssistantView::class.java)
                    assertNotNull("fullscreen must support repeated entry", repeated)
                    repeated!!
                    assertSame("repeated fullscreen must keep the original player", player, repeated.playerView.player)
                    repeated.requireButtonStartingWith("닫기").performClick()
                    assertNull("repeated fullscreen must detach cleanly", repeated.parent)
                    assertSame("repeated fullscreen close must restore compact target", player, compact.player)
                } finally {
                    controller.unbindCompact(video.id, player, compact)
                    controller.release()
                }
            }
        }
    }

    private fun <T : View> View.findFirstView(type: Class<T>): T? {
        if (type.isInstance(this)) return type.cast(this)
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findFirstView(type)?.let { return it }
        }
        return null
    }

    private fun View.findButtonStartingWith(prefix: String): Button? {
        if (this is Button && text.toString().startsWith(prefix)) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findButtonStartingWith(prefix)?.let { return it }
        }
        return null
    }

    private fun View.requireButtonStartingWith(prefix: String): Button =
        findButtonStartingWith(prefix) ?: error("button not found: $prefix")

    private fun View.collectLabeledButtons(destination: MutableList<Button> = mutableListOf()): List<Button> {
        if (this is Button && text.toString().isNotBlank()) destination += this
        if (this is ViewGroup) {
            for (index in 0 until childCount) getChildAt(index).collectLabeledButtons(destination)
        }
        return destination
    }

    private fun Button.assertAccessibilityState(expectedState: String) {
        val description = buildString {
            append(contentDescription ?: "")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                append(' ')
                append(stateDescription ?: "")
            }
        }
        assertTrue(
            "expected accessibility state '$expectedState' in '$description' for ${text}",
            description.contains(expectedState),
        )
    }

    companion object {
        private const val DECODER_MEDIA_URL =
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
    }
}
