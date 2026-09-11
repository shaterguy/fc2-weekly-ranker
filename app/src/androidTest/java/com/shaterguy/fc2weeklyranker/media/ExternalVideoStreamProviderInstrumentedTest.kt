package com.shaterguy.fc2weeklyranker.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shaterguy.fc2weeklyranker.data.VideoEntity
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalVideoStreamProviderInstrumentedTest {
    @Test
    fun externalReceiverCanReadAndSeekProgressiveStream() {
        val base = fixtureBaseUrl()
        val url = "$base/progressive.mp4?signature=opaque-test"
        preflightProgressiveTransport(url)
        val handle = createHandle(url, "progressive")
        handle.use {
            val result = runReceiver(handle, "progressive")
            assertTrue(describeResult(result), result.getBoolean(EXTRA_OK))
        }
    }

    @Test
    fun externalReceiverCanFollowRewrittenHlsChildUri() {
        val base = fixtureBaseUrl()
        val url = "$base/master.m3u8?signature=opaque-test"
        preflightHlsTransport(url)
        val handle = createHandle(url, "hls")
        handle.use {
            val result = runReceiver(handle, "hls")
            assertTrue(describeResult(result), result.getBoolean(EXTRA_OK))
        }
    }

    private fun preflightProgressiveTransport(url: String) {
        val transport = ExternalHttpTransport()
        val context = requestContext("progressive")
        val metadata = transport.metadata(url, context)
        assertTrue("transport progressive metadata length=${metadata.length}", metadata.length >= 120L)
        val first = transport.readRange(url, context, 0L, 24).bytes
        val later = transport.readRange(url, context, 96L, 24).bytes
        assertTrue("transport progressive head bytes=${first.size}", first.size == 24)
        assertTrue("transport progressive seek bytes=${later.size}", later.size == 24)
        assertFalse("transport progressive seek returned the head bytes", first.contentEquals(later))
    }

    private fun preflightHlsTransport(url: String) {
        val transport = ExternalHttpTransport()
        val context = requestContext("hls")
        val (playlist, finalUrl) = transport.fetchSmallText(url, context)
        assertTrue("transport HLS playlist missing EXTM3U", playlist.startsWith("#EXTM3U"))
        val segmentUrl = URI(finalUrl).resolve("segment.ts").toString()
        val metadata = transport.metadata(segmentUrl, context)
        assertTrue("transport HLS child metadata length=${metadata.length}", metadata.length >= 16L)
        val child = transport.readRange(segmentUrl, context, 0L, 16).bytes
        assertTrue("transport HLS child bytes=${child.size}", child.isNotEmpty())
    }

    private fun createHandle(url: String, suffix: String): ExternalVideoStreamHandle {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ExternalVideoStreamSessions.create(context, fixtureVideo(url, suffix))
    }

    private fun requestContext(suffix: String): ExternalVideoRequestContext = ExternalVideoRequestContext(
        referer = "https://example.test/post/$suffix",
        userAgent = "FC2WeeklyRankerExternalStreamTest/1.0",
    )

    private fun fixtureVideo(url: String, suffix: String): VideoEntity = VideoEntity(
        id = "external-stream-$suffix",
        postId = "external-stream-post-$suffix",
        url = url,
        referer = "https://example.test/post/$suffix",
        userAgent = "FC2WeeklyRankerExternalStreamTest/1.0",
        sourceKind = "DIRECT",
        ordinal = 0,
        discoveredAtEpochMillis = 1L,
    )

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
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
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

    private fun describeResult(result: Bundle): String = buildString {
        append("code=").append(result.getString(EXTRA_ERROR_CODE).orEmpty())
        append(" stage=").append(result.getString(EXTRA_ERROR_STAGE).orEmpty())
        append(" errno=").append(result.getInt(EXTRA_ERRNO, -1))
        append(" function=").append(result.getString(EXTRA_ERROR_FUNCTION).orEmpty())
        append(" message=").append(result.getString(EXTRA_ERROR_MESSAGE).orEmpty())
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
        private const val EXTRA_ERROR_STAGE = "errorStage"
        private const val EXTRA_ERRNO = "errno"
        private const val EXTRA_ERROR_FUNCTION = "errorFunction"
        private const val EXTRA_ERROR_MESSAGE = "errorMessage"
    }
}
