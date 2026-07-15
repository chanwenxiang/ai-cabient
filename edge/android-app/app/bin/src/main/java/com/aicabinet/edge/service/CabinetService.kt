package com.aicabinet.edge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.aicabinet.edge.R
import com.aicabinet.edge.video.SessionVideoRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class CabinetService : LifecycleService() {

    private lateinit var videoRecorder: SessionVideoRecorder
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        videoRecorder = SessionVideoRecorder(applicationContext, this)
        CabinetForegroundService.init(applicationContext, serviceScope, videoRecorder)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        CabinetForegroundService.getController(applicationContext).start()
    }

    override fun onDestroy() {
        runCatching { CabinetForegroundService.getController(applicationContext).stop() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "AI Cabinet", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("设备服务运行中")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .build()

    companion object {
        private const val CHANNEL_ID = "cabinet_service"
        private const val NOTIFICATION_ID = 1
    }
}
