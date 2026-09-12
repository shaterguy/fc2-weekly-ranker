package com.shaterguy.fc2weeklyranker.media

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.shaterguy.fc2weeklyranker.data.VideoEntity

/**
 * Debug-only entry point used by remote CI to reproduce the real external-player handoff
 * without keeping instrumentation attached to the provider process.
 */
class ExternalStreamDiagnosticActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startedAt = SystemClock.elapsedRealtime()
        runCatching {
            val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL)
                ?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: DECODER_MEDIA_URL
            val request = createExternalVideoPlayerRequest(this, diagnosticVideo(sourceUrl))
            val receiverIntent = Intent(request.intent).apply {
                component = ComponentName(RECEIVER_PACKAGE, RECEIVER_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_SCENARIO, LONG_DECODER_SCENARIO)
            }
            Log.i(TAG, "launch scenario=$LONG_DECODER_SCENARIO elapsedRealtimeMs=$startedAt")
            startActivity(receiverIntent)
            // Deliberately do not close the handle. The production handoff also leaves the
            // in-process registry responsible for the active content URI session.
        }.onFailure { error ->
            Log.e(TAG, "launch-failed type=${error.javaClass.simpleName} elapsedRealtimeMs=$startedAt")
        }
        finishAndRemoveTask()
    }

    private fun diagnosticVideo(sourceUrl: String): VideoEntity = VideoEntity(
        id = "external-stream-long-diagnostic",
        postId = "external-stream-long-diagnostic-post",
        url = sourceUrl,
        referer = "https://example.test/external-stream-diagnostic",
        userAgent = "FC2WeeklyRankerExternalStreamDiagnostic/1.0",
        sourceKind = "DIRECT",
        ordinal = 0,
        discoveredAtEpochMillis = 1L,
    )

    private companion object {
        const val TAG = "FC2ExternalLaunch"
        const val DECODER_MEDIA_URL =
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
        const val RECEIVER_PACKAGE = "com.shaterguy.fc2weeklyranker.externalreceiver"
        const val RECEIVER_ACTIVITY = "$RECEIVER_PACKAGE.ExternalStreamReceiverActivity"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_SOURCE_URL = "sourceUrl"
        const val LONG_DECODER_SCENARIO = "long-decoder"
    }
}
