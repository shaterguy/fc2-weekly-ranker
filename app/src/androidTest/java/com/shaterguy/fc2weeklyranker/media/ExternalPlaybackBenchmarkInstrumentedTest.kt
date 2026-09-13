package com.shaterguy.fc2weeklyranker.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shaterguy.fc2weeklyranker.data.VideoEntity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalPlaybackBenchmarkInstrumentedTest {
    @Test
    fun directHttpsAndOpaqueProxyUseSameDecoderBenchmark() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val video = VideoEntity(
            id = "playback-benchmark",
            postId = "playback-benchmark-post",
            url = PUBLIC_FIXTURE_URL,
            referer = "https://example.test/benchmark",
            userAgent = "FC2WeeklyRankerPlaybackBenchmark/1.0",
            sourceKind = "DIRECT",
            ordinal = 0,
            discoveredAtEpochMillis = 1L,
        )
        val request = createExternalVideoPlayerRequest(context, video)
        request.use {
            val proxyUri = requireNotNull(request.intent.data) { "opaque proxy request missing content URI" }
            require(proxyUri.scheme == "content") { "benchmark proxy must stay opaque content URI" }
            val direct = mutableListOf<Metric>()
            val proxy = mutableListOf<Metric>()
            repeat(REPETITIONS) { index ->
                direct += runBenchmark(
                    context = context,
                    uri = Uri.parse(PUBLIC_FIXTURE_URL),
                    grantFlags = 0,
                    requestId = "direct-${index + 1}",
                    mode = "benchmark",
                    timeoutSeconds = 75L,
                )
                proxy += runBenchmark(
                    context = context,
                    uri = proxyUri,
                    grantFlags = request.intent.flags,
                    requestId = "proxy-${index + 1}",
                    mode = "benchmark",
                    timeoutSeconds = 75L,
                )
            }
            val longProxy = runBenchmark(
                context = context,
                uri = proxyUri,
                grantFlags = request.intent.flags,
                requestId = "proxy-long",
                mode = "long",
                timeoutSeconds = 190L,
            )

            writeReport(context, direct, proxy, longProxy)

            (direct + proxy).forEach { metric ->
                assertTrue(metric.describe(), metric.ok)
                assertEquals(metric.describe(), 0, metric.mediaErrorWhat)
                assertTrue(metric.describe(), metric.firstFrameMs >= 0L)
                assertTrue(metric.describe(), metric.seekResumeMs >= 0L)
                assertEquals(metric.describe(), 3, metric.completedSeeks)
            }
            assertTrue(longProxy.describe(), longProxy.ok)
            assertEquals(longProxy.describe(), 0, longProxy.mediaErrorWhat)
            assertTrue(longProxy.describe(), longProxy.firstFrameMs >= 0L)
            assertTrue(longProxy.describe(), longProxy.playedPositionMs >= 120_000)
        }
    }

    private fun runBenchmark(
        context: Context,
        uri: Uri,
        grantFlags: Int,
        requestId: String,
        mode: String,
        timeoutSeconds: Long,
    ): Metric {
        val resultThread = HandlerThread("playback-benchmark-result").apply { start() }
        val latch = CountDownLatch(1)
        val result = AtomicReference<Bundle?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != RESULT_ACTION || intent.getStringExtra(EXTRA_REQUEST_ID) != requestId) return
                result.set(intent.extras ?: Bundle.EMPTY)
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(RESULT_ACTION),
            null,
            Handler(resultThread.looper),
            ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(RECEIVER_PACKAGE, BENCHMARK_ACTIVITY)
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(grantFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_RESULT_PACKAGE, context.packageName)
            }
            context.startActivity(intent)
            assertTrue("playback benchmark timed out request=$requestId", latch.await(timeoutSeconds, TimeUnit.SECONDS))
            val bundle = requireNotNull(result.get()) { "playback benchmark returned no result request=$requestId" }
            return Metric(
                id = requestId,
                ok = bundle.getBoolean(EXTRA_OK),
                firstFrameMs = bundle.getLong(EXTRA_FIRST_FRAME_MS, -1L),
                seekResumeMs = bundle.getLong(EXTRA_SEEK_RESUME_MS, -1L),
                completedSeeks = bundle.getInt(EXTRA_COMPLETED_SEEKS, 0),
                mediaErrorWhat = bundle.getInt(EXTRA_MEDIA_ERROR_WHAT, 0),
                mediaErrorExtra = bundle.getInt(EXTRA_MEDIA_ERROR_EXTRA, 0),
                bufferingCount = bundle.getInt(EXTRA_BUFFERING_COUNT, 0),
                bufferingDurationMs = bundle.getLong(EXTRA_BUFFERING_DURATION_MS, 0L),
                maxNoProgressMs = bundle.getLong(EXTRA_MAX_NO_PROGRESS_MS, 0L),
                stallCount = bundle.getInt(EXTRA_STALL_COUNT, 0),
                playedPositionMs = bundle.getInt(EXTRA_PLAYED_POSITION_MS, 0),
                failureStage = bundle.getString(EXTRA_FAILURE_STAGE).orEmpty(),
            )
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            resultThread.quitSafely()
        }
    }

    private fun writeReport(context: Context, direct: List<Metric>, proxy: List<Metric>, longProxy: Metric) {
        val directFirst = direct.map { it.firstFrameMs }
        val proxyFirst = proxy.map { it.firstFrameMs }
        val directSeek = direct.map { it.seekResumeMs }
        val proxySeek = proxy.map { it.seekResumeMs }
        val root = JSONObject()
            .put("schema", "fc2-external-playback-benchmark-v1")
            .put("fixture", "BigBuckBunny_320x180.mp4")
            .put("repetitions", REPETITIONS)
            .put("direct", metricGroup(direct, directFirst, directSeek))
            .put("proxy", metricGroup(proxy, proxyFirst, proxySeek))
            .put("longProxy", metricJson(longProxy))
        File(context.filesDir, REPORT_FILE).writeText(root.toString(2), Charsets.UTF_8)
    }

    private fun metricGroup(metrics: List<Metric>, first: List<Long>, seek: List<Long>): JSONObject = JSONObject()
        .put("runs", JSONArray(metrics.map(::metricJson)))
        .put("firstFrameMs", JSONArray(first))
        .put("seekResumeMs", JSONArray(seek))
        .put("firstFrameP50Ms", p50(first))
        .put("firstFrameP95Ms", p95(first))
        .put("seekResumeP50Ms", p50(seek))
        .put("seekResumeP95Ms", p95(seek))

    private fun metricJson(metric: Metric): JSONObject = JSONObject()
        .put("id", metric.id)
        .put("ok", metric.ok)
        .put("firstFrameMs", metric.firstFrameMs)
        .put("seekResumeMs", metric.seekResumeMs)
        .put("completedSeeks", metric.completedSeeks)
        .put("mediaErrorWhat", metric.mediaErrorWhat)
        .put("mediaErrorExtra", metric.mediaErrorExtra)
        .put("bufferingCount", metric.bufferingCount)
        .put("bufferingDurationMs", metric.bufferingDurationMs)
        .put("maxNoProgressMs", metric.maxNoProgressMs)
        .put("stallCount", metric.stallCount)
        .put("playedPositionMs", metric.playedPositionMs)
        .put("failureStage", metric.failureStage)

    private fun p50(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun p95(values: List<Long>): Long = values.maxOrNull() ?: -1L

    private data class Metric(
        val id: String,
        val ok: Boolean,
        val firstFrameMs: Long,
        val seekResumeMs: Long,
        val completedSeeks: Int,
        val mediaErrorWhat: Int,
        val mediaErrorExtra: Int,
        val bufferingCount: Int,
        val bufferingDurationMs: Long,
        val maxNoProgressMs: Long,
        val stallCount: Int,
        val playedPositionMs: Int,
        val failureStage: String,
    ) {
        fun describe(): String =
            "id=$id ok=$ok firstFrameMs=$firstFrameMs seekResumeMs=$seekResumeMs seeks=$completedSeeks " +
                "mediaError=$mediaErrorWhat/$mediaErrorExtra buffering=$bufferingCount/$bufferingDurationMs " +
                "maxNoProgressMs=$maxNoProgressMs stalls=$stallCount playedPositionMs=$playedPositionMs stage=$failureStage"
    }

    companion object {
        private const val PUBLIC_FIXTURE_URL =
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
        private const val REPETITIONS = 3
        private const val REPORT_FILE = "external-playback-benchmark.json"
        private const val RECEIVER_PACKAGE = "com.shaterguy.fc2weeklyranker.externalreceiver"
        private const val BENCHMARK_ACTIVITY = "$RECEIVER_PACKAGE.PlaybackBenchmarkActivity"
        private const val RESULT_ACTION = "com.shaterguy.fc2weeklyranker.EXTERNAL_PLAYBACK_BENCHMARK_RESULT"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_REQUEST_ID = "requestId"
        private const val EXTRA_RESULT_PACKAGE = "resultPackage"
        private const val EXTRA_OK = "ok"
        private const val EXTRA_FIRST_FRAME_MS = "firstFrameMs"
        private const val EXTRA_SEEK_RESUME_MS = "seekResumeMs"
        private const val EXTRA_COMPLETED_SEEKS = "completedSeeks"
        private const val EXTRA_MEDIA_ERROR_WHAT = "mediaErrorWhat"
        private const val EXTRA_MEDIA_ERROR_EXTRA = "mediaErrorExtra"
        private const val EXTRA_BUFFERING_COUNT = "bufferingCount"
        private const val EXTRA_BUFFERING_DURATION_MS = "bufferingDurationMs"
        private const val EXTRA_MAX_NO_PROGRESS_MS = "maxNoProgressMs"
        private const val EXTRA_STALL_COUNT = "stallCount"
        private const val EXTRA_PLAYED_POSITION_MS = "playedPositionMs"
        private const val EXTRA_FAILURE_STAGE = "failureStage"
    }
}
