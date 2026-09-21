package com.aicabinet.edge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.aicabinet.edge.R
import com.aicabinet.edge.video.SessionVideoRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CabinetService : LifecycleService() {

    private lateinit var videoRecorder: SessionVideoRecorder
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * P0-7（同族）：停机用的**独立**作用域。
     *
     * 不能复用 [serviceScope] —— `onDestroy()` 里紧接着就 `serviceScope.cancel()`，
     * 会把刚提交、尚未被调度到的停机协程一并取消 ⇒ `stop()` **静默不执行**
     * （服务停了但串口没释放、MQTT 没断开）。停机跑完由 [ServiceShutdown.schedule] 自毁本作用域。
     */
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        videoRecorder = SessionVideoRecorder(applicationContext, this)
        CabinetForegroundService.init(applicationContext, serviceScope, videoRecorder)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // P0-7：`start()` 里**全是阻塞调用**，onCreate 跑在主线程 ⇒ 必须整段移出去（Dispatchers.IO）：
        //   1. OtaChecker.checkOnStartup()      —— OkHttp 同步 execute()（主线程网络 I/O）
        //   2. ChzhLockDriver.initialize()      —— 串口 open()
        //   3. MqttDeviceClient.connect()       —— Paho `MqttClient.connect()` 会阻塞等待，最长
        //                                          connectionTimeout=10s
        //   4. connect() 收尾的 publishHeartbeat()/flushOutbound() ⇒ OutboundMqttQueue.drain()
        //      ⇒ PrefsJsonQueue 的**同步 commit()**：这就是 P0-7 点名的「主线程 commit」，
        //      且 flushOutbound() 是无条件调用 drain() ⇒ **每次启动必然命中**。
        serviceScope.launch(Dispatchers.IO) {
            CabinetForegroundService.getController(applicationContext).start()
        }
    }

    override fun onDestroy() {
        // P0-7（同族）：`stop()` 是阻塞链，而 onDestroy 跑在主线程 —— 必须整段异步化：
        //   offlineQueue.stop()   → executor.shutdownNow()                       （非阻塞）
        //   mqtt.disconnect()     → Paho `disconnect(30000)` + 无超时 waitForCompletion()
        //   lockDriver.shutdown() → 串口 close()                                  （阻塞 I/O）
        // ⚠️ 且必须走**独立** shutdownScope：紧接着的 serviceScope.cancel() 会把挂在
        //    serviceScope 上的停机协程连同取消掉 ⇒ stop() 静默不执行（详见 ServiceShutdown）。
        val controller =
            runCatching { CabinetForegroundService.getController(applicationContext) }.getOrNull()
        if (controller == null) {
            // 服务从未成功启动（onCreate 里 init 之前就被销毁）：没有可停的东西，非错误。
            Log.w(TAG, "controller 未初始化，跳过 stop()")
        } else {
            ServiceShutdown.schedule(shutdownScope, onError = { Log.w(TAG, "stop() 失败", it) }) {
                controller.stop()
            }
        }
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
        private const val TAG = "CabinetService"
        private const val CHANNEL_ID = "cabinet_service"
        private const val NOTIFICATION_ID = 1
    }
}
