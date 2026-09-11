package com.shaterguy.fc2weeklyranker.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shaterguy.fc2weeklyranker.data.VideoEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalVideoStreamProviderInstrumentedTest {
    @Test
    fun externalReceiverCanReadAndSeekProgressiveStream() {
        val base = fixtureBaseUrl()
        val handle = createHandle("$base/progressive.mp4?signature=opaque-test", "progressive")
        handle.use {
            val result = runReceiver(handle, "progressive")
            assertTrue(result.getString(EXTRA_ERROR_CODE).orEmpty(), result.getBoolean(EXTRA_OK))
        }
    }

    @Test
    fun externalReceiverCanFollowRewrittenHlsChildUri() {
        val base = fixtureBaseUrl()
        val handle = createHandle("$base/master.m3u8?signature=opaque-test", "hls")
        handle.use {
            val result = runReceiver(handle, "hls")
            assertTrue(result.getString(EXTRA_ERROR_CODE).orEmpty(), result.getBoolean(EXTRA_OK))
        }
    }

    private fun createHandle(url: String, suffix: String): ExternalVideoStreamHandle {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val video = VideoEntity(
            id = "external-stream-$suffix",
            postId = "external-stream-post-$suffix",
            url = url,
            referer = "https://example.test/post/$suffix",
            userAgent = "FC2WeeklyRankerExternalStreamTest/1.0",
            sourceKind = "DIRECT",
            ordinal = 0,
            discoveredAtEpochMillis = 1L,
        )
        return ExternalVideoStreamSessions.create(context, video)
    }

    private fun runReceiver(handle: ExternalVideoStreamHandle, scenario: String): Bundle {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        val result = AtomicReference<Bundle?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != RESULT_ACTION || intent.getStringExtra(EXTRA_SCENARIO) != scenario) return
                result.set(intent.extras ?: Bundle.EMPTY)
                latch.countDown()
            }
        }
        val filter = IntentFilter(RESULT_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(RECEIVER_PACKAGE, RECEIVER_ACTIVITY)
                setDataAndType(handle.uri, handle.mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or handle.intentFlags)
                putExtra(EXTRA_SCENARIO, scenario)
                putExtra(EXTRA_RESULT_PACKAGE, context.packageName)
            }
            context.startActivity(intent)
            assertTrue("external receiver result timed out", latch.await(30, TimeUnit.SECONDS))
            return result.get().also { assertNotNull("external receiver returned no result", it) }!!
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun fixtureBaseUrl(): String {
        val raw = InstrumentationRegistry.getArguments().getString("fixtureBaseUrl")
        require(!raw.isNullOrBlank()) { "fixtureBaseUrl instrumentation argument is required" }
        return raw.trimEnd('/')
    }

    companion object {
        private const val RECEIVER_PACKAGE = "com.shaterguy.fc2weeklyranker.externalreceiver"
        private const val RECEIVER_ACTIVITY = "$RECEIVER_PACKAGE.ExternalStreamReceiverActivity"
        private const val RESULT_ACTION = "com.shaterguy.fc2weeklyranker.EXTERNAL_STREAM_TEST_RESULT"
        private const val EXTRA_SCENARIO = "scenario"
        private const val EXTRA_RESULT_PACKAGE = "resultPackage"
        private const val EXTRA_OK = "ok"
        private const val EXTRA_ERROR_CODE = "errorCode"
    }
}
