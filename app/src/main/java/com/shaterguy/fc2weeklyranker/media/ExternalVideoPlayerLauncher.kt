package com.shaterguy.fc2weeklyranker.media

import android.content.Context
import android.content.Intent
import com.shaterguy.fc2weeklyranker.data.VideoEntity

internal data class ExternalVideoPlayerRequest(
    val handle: ExternalVideoStreamHandle,
    val intent: Intent,
) : AutoCloseable {
    override fun close() {
        handle.close()
    }
}

internal fun createExternalVideoPlayerRequest(
    context: Context,
    video: VideoEntity,
): ExternalVideoPlayerRequest {
    val handle = ExternalVideoStreamSessions.create(context, video)
    return try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(handle.uri, handle.mimeType)
            addFlags(handle.intentFlags)
        }
        ExternalVideoStreamKeepAliveService.startFromUserVisibleContext(context)
        ExternalVideoPlayerRequest(handle, intent)
    } catch (throwable: Throwable) {
        handle.close()
        throw throwable
    }
}
