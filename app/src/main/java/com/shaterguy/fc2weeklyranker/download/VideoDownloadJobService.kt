package com.shaterguy.fc2weeklyranker.download

import android.annotation.SuppressLint
import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class VideoDownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()

    @SuppressLint("NewApi")
    override fun onStartJob(params: JobParameters): Boolean {
        val videoId = params.extras.getString(KEY_VIDEO_ID) ?: return false
        setNotification(
            params,
            DownloadNotifications.notificationId(videoId),
            DownloadNotifications.notification(applicationContext, videoId),
            JobService.JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        val task = scope.launch(start = CoroutineStart.LAZY) {
            val result = DownloadTransferRunner.run(applicationContext, videoId) { downloaded, total ->
                withContext(Dispatchers.Main.immediate) {
                    setNotification(
                        params,
                        DownloadNotifications.notificationId(videoId),
                        DownloadNotifications.notification(applicationContext, videoId, downloaded, total),
                        JobService.JOB_END_NOTIFICATION_POLICY_REMOVE,
                    )
                }
            }
            withContext(Dispatchers.Main.immediate) {
                jobs.remove(params.jobId)
                jobFinished(params, result == DownloadRunResult.RETRY)
            }
        }
        jobs[params.jobId] = task
        task.start()
        return true
    }

    @SuppressLint("NewApi")
    override fun onStopJob(params: JobParameters): Boolean {
        val videoId = params.extras.getString(KEY_VIDEO_ID)
        jobs.remove(params.jobId)?.cancel()
        if (videoId != null) DownloadTransferRunner.cancel(videoId)
        return params.stopReason !in setOf(
            JobParameters.STOP_REASON_USER,
            JobParameters.STOP_REASON_CANCELLED_BY_APP,
        )
    }

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val KEY_VIDEO_ID = "video_id"
    }
}
