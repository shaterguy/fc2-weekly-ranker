package com.shaterguy.fc2weeklyranker.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.webkit.CookieManager
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
        preflightSeekableOnProxyThread(url, "progressive", 96L)
        val request = createRequest(url, "progressive")
        request.use {
            val result = runReceiver(request, "progressive")
            assertTrue(describeResult(result), result.getBoolean(EXTRA_OK))
        }

        val decoderRequest = createRequest(DECODER_MEDIA_URL, "decoder")
        decoderRequest.use {
            val result = runReceiver(decoderRequest, "decoder")
            assertTrue(describeResult(result), result.getBoolean(EXTRA_OK))
        }
    }

    @Test
    fun externalReceiverCanFollowRewrittenHlsChildUri() {
        val base = fixtureBaseUrl()
        val url = "$base/master.m3u8?signature=opaque-test"
        val segmentUrl = preflightHlsTransport(url)
        preflightSeekableOnProxyThread(segmentUrl, "hls", 0L)
        val request = createRequest(url, "hls")
        request.use {
            val result = runReceiver(request, "hls")
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

    private fun preflightHlsTransport(url: String): String {
        val transport = ExternalHttpTransport()
        val context = requestContext("hls")
        val (playlist, finalUrl) = transport.fetchSmallText(url, context)
        assertTrue("transport HLS playlist missing EXTM3U", playlist.startsWith("#EXTM3U"))
        val segmentUrl = URI(finalUrl).resolve("segment.ts").toString()
        val metadata = transport.metadata(segmentUrl, context)
        assertTrue("transport HLS child metadata length=${metadata.length}", metadata.length >= 16L)
        val child = transport.readRange(segmentUrl, context, 0L, 16).bytes
        assertTrue("transport HLS child bytes=${child.size}", child.isNotEmpty())
        return segmentUrl
    }

    private fun preflightSeekableOnProxyThread(url: String, suffix: String, seekOffset: Long) {
        val thread = HandlerThread("external-stream-seekable-preflight").apply { start() }
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        Handler(thread.looper).post {
            try {
                val transport = ExternalHttpTransport(
                    cookieProvider = { targetUrl ->
                        CookieManager.getInstance().getCookie(targetUrl)?.takeIf(String::isNotBlank)
                    },
                )
                val resource = SeekableExternalHttpResource(
                    url = url,
                    context = requestContext(suffix),
                    transport = transport,
                    chunkSize = 32,
                    maxCachedChunks = 2,
                )
                val length = resource.size()
                check(length > 0L) { "seekable metadata returned empty resource" }
                var cursor = 0L
                while (cursor < length) {
                    val requested = minOf(37L, length - cursor).toInt()
                    val bytes = resource.read(cursor, requested)
                    check(bytes.size == requested) {
                        "seekable sustained read returned ${bytes.size}/$requested bytes at offset $cursor before EOF $length"
                    }
                    cursor += bytes.size.toLong()
                }
                check(resource.read(length, 16).isEmpty()) { "seekable EOF read returned bytes" }
                if (seekOffset > 0L && seekOffset < length) {
                    check(resource.read(seekOffset, minOf(24L, length - seekOffset).toInt()).isNotEmpty()) {
                        "seekable backward seek read returned no bytes"
                    }
                }
                resource.close()
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                latch.countDown()
            }
        }
        try {
            assertTrue("seekable proxy-thread preflight timed out", latch.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throwable ->
                throw AssertionError(
                    "seekable proxy-thread preflight failed: ${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}",
                    throwable,
                )
            }
        } finally {
            thread.quitSafely()
        }
    }

    private fun createRequest(url: String, suffix: String): ExternalVideoPlayerRequest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val video = fixtureVideo(url, suffix)
        return createExternalVideoPlayerRequest(context, video).also { request ->
            assertOpaqueExternalPlayerRequest(context, request, video)
        }
    }

    private fun assertOpaqueExternalPlayerRequest(
        context: Context,
        request: ExternalVideoPlayerRequest,
        video: VideoEntity,
    ) {
        val intent = request.intent
        val data = intent.data
        assertNotNull("external player intent has no data URI", data)
        assertTrue("external player intent must use content URI, got $data", data?.scheme == "content")
        assertTrue(
            "external player intent authority must stay app-scoped, got ${data?.authority}",
            data?.authority == context.packageName + ".externalstream",
        )
        assertTrue("external player intent MIME is missing", !intent.type.isNullOrBlank())
        assertTrue(
            "external player intent missing read grant",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertTrue(
            "external player intent missing prefix grant",
            intent.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION != 0,
        )
        val serialized = intent.toUri(Intent.URI_INTENT_SCHEME)
        listOf(video.url, video.referer, video.userAgent, "signature=opaque-test").forEach { secret ->
            assertFalse("external player intent leaked protected value: $secret", serialized.contains(secret))
        }
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

    private fun runReceiver(request: ExternalVideoPlayerRequest, scenario: String): Bundle {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resultThread = HandlerThread("external-stream-result-receiver").apply { start() }
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
            null,
            Handler(resultThread.looper),
            ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            val intent = Intent(request.intent).apply {
                component = ComponentName(RECEIVER_PACKAGE, RECEIVER_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_SCENARIO, scenario)
                putExtra(EXTRA_RESULT_PACKAGE, context.packageName)
            }
            context.startActivity(intent)
            val timeoutSeconds = if (scenario == "decoder") 75L else 30L
            assertTrue("external receiver result timed out", latch.await(timeoutSeconds, TimeUnit.SECONDS))
            return result.get().also { assertNotNull("external receiver returned no result", it) }!!
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            resultThread.quitSafely()
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
        private const val DECODER_MEDIA_URL =
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
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
