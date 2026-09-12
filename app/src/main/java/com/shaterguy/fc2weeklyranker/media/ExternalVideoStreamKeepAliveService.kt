package com.shaterguy.fc2weeklyranker.media

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

internal const val EXTERNAL_STREAM_KEEP_ALIVE_IDLE_TIMEOUT_MS = 90_000L
private const val EXTERNAL_STREAM_KEEP_ALIVE_CHECK_INTERVAL_MS = 10_000L

internal fun shouldStopExternalStreamKeepAlive(
    activeDescriptors: Int,
    lastActivityMs: Long,
    nowMs: Long,
    idleTimeoutMs: Long = EXTERNAL_STREAM_KEEP_ALIVE_IDLE_TIMEOUT_MS,
): Boolean = activeDescriptors <= 0 &&
    lastActivityMs > 0L &&
    nowMs - lastActivityMs >= idleTimeoutMs

internal object ExternalVideoStreamKeepAliveState {
    private val activeDescriptors = AtomicInteger(0)

    @Volatile
    private var lastActivityMs: Long = 0L

    fun serviceStarted() {
        touch()
    }

    fun descriptorOpened() {
        activeDescriptors.incrementAndGet()
        touch()
    }

    fun descriptorClosed() {
        while (true) {
            val current = activeDescriptors.get()
            if (current <= 0) break
            if (activeDescriptors.compareAndSet(current, current - 1)) break
        }
        touch()
    }

    fun streamActivity() {
        touch()
    }

    fun shouldStop(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        shouldStopExternalStreamKeepAlive(
            activeDescriptors = activeDescriptors.get(),
            lastActivityMs = lastActivityMs,
            nowMs = nowMs,
        )

    private fun touch() {
        lastActivityMs = SystemClock.elapsedRealtime()
    }
}

class ExternalVideoStreamKeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            if (ExternalVideoStreamKeepAliveState.shouldStop()) {
                stopKeepAlive()
            } else {
                handler.postDelayed(this, EXTERNAL_STREAM_KEEP_ALIVE_CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        ExternalVideoStreamKeepAliveState.serviceStarted()
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, EXTERNAL_STREAM_KEEP_ALIVE_CHECK_INTERVAL_MS)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopKeepAlive()
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleCheck)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "외부 플레이어 스트림",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "외부 플레이어로 재생할 영상 데이터를 전송합니다."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("FC2 Weekly Ranker")
        .setContentText("외부 플레이어로 영상 데이터를 전송하는 중입니다.")
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .build()

    private fun stopKeepAlive() {
        handler.removeCallbacks(idleCheck)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "external-video-stream"
        private const val NOTIFICATION_ID = 0x465332

        internal fun startFromUserVisibleContext(context: Context): Boolean {
            val activity = context.findActivity() ?: return false
            if (activity.isFinishing || activity.isDestroyed) return false
            activity.startForegroundService(Intent(activity, ExternalVideoStreamKeepAliveService::class.java))
            return true
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        if (current !is ContextWrapper) return null
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return null
}
