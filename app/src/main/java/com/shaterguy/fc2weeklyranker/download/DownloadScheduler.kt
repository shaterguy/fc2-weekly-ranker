package com.shaterguy.fc2weeklyranker.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.shaterguy.fc2weeklyranker.data.DownloadEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class DownloadSchedulerKind { WORK_MANAGER, UIDT }

internal object DownloadRuntimePolicy {
    const val MAX_CONCURRENT_TRANSFERS = 3

    fun schedulerKind(sdkInt: Int): DownloadSchedulerKind =
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) DownloadSchedulerKind.UIDT else DownloadSchedulerKind.WORK_MANAGER

    fun jobId(state: DownloadEntity): Int {
        if (state.enqueueOrder in 1..Int.MAX_VALUE.toLong()) return state.enqueueOrder.toInt()
        return (state.videoId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
    }
}

internal class SchedulerOperationGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

internal object DownloadNotifications {
    private const val CHANNEL_ID = "video_downloads"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "동영상 다운로드", NotificationManager.IMPORTANCE_LOW).apply {
                description = "백그라운드 동영상 다운로드 진행 상태"
                setShowBadge(false)
            },
        )
    }

    fun notification(context: Context, videoId: String, downloaded: Long = 0L, total: Long? = null): Notification {
        ensureChannel(context)
        val percent = total?.takeIf { it > 0L }?.let { ((downloaded * 100L) / it).coerceIn(0L, 100L).toInt() }
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("동영상 다운로드")
            .setContentText(
                if (percent != null) "$percent% · ${formatBytes(downloaded)} / ${formatBytes(total)}"
                else "${formatBytes(downloaded)} 다운로드 중",
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent ?: 0, percent == null)
            .build()
    }

    fun foregroundInfo(context: Context, videoId: String, downloaded: Long = 0L, total: Long? = null): ForegroundInfo =
        ForegroundInfo(
            notificationId(videoId),
            notification(context, videoId, downloaded, total),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    fun notificationId(videoId: String): Int = (videoId.hashCode() and 0x3fffffff).coerceAtLeast(1)

    private fun formatBytes(value: Long?): String {
        if (value == null || value < 0L) return "?"
        return when {
            value >= 1_073_741_824L -> "%.1f GB".format(value / 1_073_741_824.0)
            value >= 1_048_576L -> "%.1f MB".format(value / 1_048_576.0)
            value >= 1_024L -> "%.1f KB".format(value / 1_024.0)
            else -> "$value B"
        }
    }
}

internal class DownloadScheduler(private val context: Context) {
    private val operationGate = SchedulerOperationGate()

    suspend fun schedule(state: DownloadEntity): Boolean = operationGate.run {
        when (DownloadRuntimePolicy.schedulerKind(Build.VERSION.SDK_INT)) {
            DownloadSchedulerKind.UIDT -> scheduleUidt(state, replaceExisting = true)
            DownloadSchedulerKind.WORK_MANAGER -> {
                enqueueWorker(state.videoId, ExistingWorkPolicy.REPLACE)
                true
            }
        }
    }

    suspend fun recover(state: DownloadEntity): Boolean = operationGate.run {
        when (DownloadRuntimePolicy.schedulerKind(Build.VERSION.SDK_INT)) {
            DownloadSchedulerKind.UIDT -> {
                val scheduler = uidtScheduler()
                val jobId = DownloadRuntimePolicy.jobId(state)
                scheduler.getPendingJob(jobId) != null || scheduleUidt(state, replaceExisting = false)
            }
            DownloadSchedulerKind.WORK_MANAGER -> {
                enqueueWorker(state.videoId, ExistingWorkPolicy.KEEP)
                true
            }
        }
    }

    suspend fun cancel(state: DownloadEntity) = operationGate.run {
        when (DownloadRuntimePolicy.schedulerKind(Build.VERSION.SDK_INT)) {
            DownloadSchedulerKind.UIDT -> uidtScheduler().cancel(DownloadRuntimePolicy.jobId(state))
            DownloadSchedulerKind.WORK_MANAGER ->
                WorkManager.getInstance(context).cancelUniqueWork(VideoDownloadWorker.workName(state.videoId))
        }
    }

    private fun enqueueWorker(videoId: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(VideoDownloadWorker.KEY_VIDEO_ID to videoId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(VideoDownloadWorker.workName(videoId), policy, request)
    }

    private fun scheduleUidt(state: DownloadEntity, replaceExisting: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return runCatching {
            val scheduler = uidtScheduler()
            val jobId = DownloadRuntimePolicy.jobId(state)
            if (!replaceExisting && scheduler.getPendingJob(jobId) != null) return@runCatching true
            val network = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val extras = PersistableBundle().apply { putString(VideoDownloadJobService.KEY_VIDEO_ID, state.videoId) }
            val info = JobInfo.Builder(jobId, ComponentName(context, VideoDownloadJobService::class.java))
                .setExtras(extras)
                .setRequiredNetwork(network)
                .setUserInitiated(true)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)
    }

    private fun uidtScheduler(): JobScheduler {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) scheduler.forNamespace(UIDT_NAMESPACE) else scheduler
    }

    companion object {
        private const val UIDT_NAMESPACE = "video-downloads"
    }
}
