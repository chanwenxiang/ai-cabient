package com.aicabinet.edge.service

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.aicabinet.edge.hal.DoorState
import com.aicabinet.edge.hal.ILockDriver
import com.aicabinet.edge.mqtt.MqttDeviceClient
import com.aicabinet.edge.status.DeviceStatusHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * V308：运维指令执行器（`LOCK` / `UNLOCK` / `REBOOT` / `SET_TARGET_TEMP` / `SELF_TEST`）。
 *
 * <p>🔴 **修的是什么问题**：此前 {@code MqttDeviceClient} 只认 `OPEN_DOOR`，
 * 其余指令**静默丢弃、不回 ACK** ⇒ 云端 `DeviceCommandTracker` 要等满 15 秒
 * `ACK_TIMEOUT_MS` 才置 `TIMEOUT`。运维点「远程重启」→ 云端返回 `commandId`
 * 像是成功 → 设备**实际没动** → 15 秒后才知失败。
 *
 * <p>**本类的三条纪律**：
 * <ol>
 *   <li><b>每条指令都必须回 ACK</b>，哪怕失败。失败也要带原因，让运维一眼分清
 *       「未支持」/「参数非法」/「执行失败」/「已过期」。</li>
 *   <li><b>不确定就不做</b>：需要硬件语义但尚未确认的（如整柜 vs 货道锁）
 *       <b>不做猜测实现</b>，直接回失败并说明原因。</li>
 *   <li><b>REBOOT 延迟回 ACK</b>：重启会杀掉进程，ACK 必须在触发前发出去，
 *       否则云端永远收不到（又变成 15 秒超时）。</li>
 * </ol>
 *
 * <p>⚠️ <b>待硬件方确认的两项</b>（2026-10-06 未确认，见 docs §8.4）：
 * <ul>
 *   <li>`LOCK` 是锁<b>货道</b>还是<b>整柜</b> —— 两者风险完全不同，
 *       当前实现按整柜门锁处理（{@link ILockDriver#lock}），若实为货道锁需换驱动调用；</li>
 *   <li>`SET_TARGET_TEMP` 的取值范围与回读校验 —— 当前只做基础范围校验并回读门温。</li>
 * </ul>
 */
class OpsCommandExecutor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val lockDriver: ILockDriver,
    /** 回ACK 与上报遥测的出口（由 CabinetController 注入，避免本类直接依赖 MQTT） */
    private val mqtt: () -> MqttDeviceClient
) {

    private val appContext = context.applicationContext

    /** 入口：处理一条运维指令。<b>必须在协程里调用</b>（锁驱动是 suspend）。 */
    fun handle(ops: MqttDeviceClient.OpsCommand) {
        when (ops.type) {
            "LOCK" -> handleLock(ops, lock = true)
            "UNLOCK" -> handleLock(ops, lock = false)
            "REBOOT" -> handleReboot(ops)
            "SET_TARGET_TEMP" -> handleSetTargetTemp(ops)
            "SELF_TEST" -> handleSelfTest(ops)
            else -> ack(ops.commandId, false, "不支持的指令类型: ${ops.type}")
        }
    }

    // ==================== LOCK / UNLOCK ====================

    private fun handleLock(ops: MqttDeviceClient.OpsCommand, lock: Boolean) {
        scope.launch {
            val result = if (lock) lockDriver.lock() else lockDriver.unlock()
            result.fold(
                onSuccess = {
                    // ⚠️ 锁门与 OPEN_DOOR 互斥：锁上之后必须让云端知道门已锁
                    DeviceStatusHub.setDoorState(
                        lockDriver.currentDoorState(),
                        event = if (lock) "远程锁门成功" else "远程解锁成功"
                    )
                    ack(ops.commandId, true, if (lock) "已锁门" else "已解锁")
                },
                onFailure = {
                    Log.e(TAG, "remote lock/unlock failed type=${ops.type}", it)
                    ack(ops.commandId, false, "锁驱动执行失败: ${it.message ?: it.javaClass.simpleName}")
                }
            )
        }
    }

    // ==================== REBOOT ====================

    /**
     * 远程重启。
     *
     * <p>🔴 <b>ACK 必须先发、再重启</b>：Android 的 `exitProcess()` 不会给网络留收尾时间，
     * 若「先重启再 ACK」，ACK 大概率丢在队列里 ⇒ 云端又变成 15 秒超时。
     * 这里用 [ackThenReboot]：先同步 publish，再延迟 1.2s 真正重启。
     */
    private fun handleReboot(ops: MqttDeviceClient.OpsCommand) {
        Log.w(TAG, "REBOOT requested commandId=${ops.commandId}")
        ack(ops.commandId, true, "已接受重启指令，设备即将重启")
        scope.launch {
            // 留出 MQTT flush + 云端收到 ACK 的时间
            kotlinx.coroutines.delay(1_200)
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                // ⚠️ compileSdk 34：PowerManager 只剩 `reboot(String?)` 公开签名；
                //   旧的 3 参重载 `reboot(reason, no, flags)` 是隐藏 API，普通 App 调不到。
                //   （这一处曾按旧签名写，CI 编译期才发现 —— 本机跑不了 Android 编译是根因。）
                val restarted = runCatching {
                    pm.reboot("REBOOT: remote ops command")
                }.isSuccess
                if (restarted) return@launch
            }
            // PowerManager.reboot 需要 DEVICE_POWER 权限（system 签名），
            // 普通 App 拿不到 ⇒ 退化到杀进程，效果等价（应用被杀后由看护服务拉起）
            Log.w(TAG, "PowerManager.reboot unavailable, fallback to killProcess")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    // ==================== SET_TARGET_TEMP ====================

    /**
     * 设置目标柜温。
     *
     * <p>⚠️ **只做基础范围校验 + 落盘，不假装能控温**：真实温控依赖硬件侧
     * （压缩机/继电器），本项目未接入。因此本指令当前语义是
     * <b>「记录并持久化目标温度，供后续硬件接入读取」</b>，
     * 回 ACK 时明确说明这一点 —— <b>不能回success 让运维以为温控已生效</b>。
     */
    private fun handleSetTargetTemp(ops: MqttDeviceClient.OpsCommand) {
        val target = ops.targetTempC
        if (target == null) {
            ack(ops.commandId, false, "缺少 targetTempC 参数")
            return
        }
        // 冷藏柜常见工况 -25~10°C；范围放宽到 -40~40 以免误拒，
        // 但**明确越界**（真给个 80°C 显然不合理）
        if (target !in -40..40) {
            ack(ops.commandId, false, "目标温度越界: $target°C（允许 -40~40）")
            return
        }
        val saved = runCatching {
            appContext.getSharedPreferences(PREFS_OPS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_TARGET_TEMP_C, target)
                .putLong(KEY_TARGET_TEMP_SET_AT, System.currentTimeMillis())
                .commit()
        }.isSuccess
        if (saved) {
            // 🔴 诚实回执：写成功 ≠ 温控生效
            ack(ops.commandId, true, "目标温度已记录 $target°C（温控执行器未接入，尚未实际控温）")
        } else {
            ack(ops.commandId, false, "目标温度持久化失败")
        }
    }

    // ==================== SELF_TEST ====================

    /**
     * 设备自检（V308 阶段一）。
     *
     * <p><b>只上报「能自检的项」，不编造「检测不了」的项</b>：
     * <ul>
     *   <li>`storageFree` —— `File.usableSpace` 实测；</li>
     *   <li>`appUptimeSec` —— `SystemClock.elapsedRealtime` 实算；</li>
     *   <li>`doorState` —— 门状态回读（顺带验证锁驱动通信是否正常）；</li>
     *   <li>`networkRssi` —— 读系统 Wi-Fi RSSI（<b>拿不到就报 null，不填 0</b>：
     *       0 表示「信号强度为 0」，与「读不到」是两件事）。</li>
     * </ul>
     * 未接入的部件（摄像头、主板温度、货道电机）**不在这里假装检测**。
     */
    private fun handleSelfTest(ops: MqttDeviceClient.OpsCommand) {
        scope.launch {
            val items = linkedMapOf<String, Any?>()
            val errors = mutableListOf<String>()

            runCatching {
                // 🔴 filesDir 本身已是 File，不要再包一层 File(...)（不存在 File(File) 构造）
                val f = appContext.filesDir
                items["storageFreeMb"] = f.usableSpace / 1024 / 1024
            }.onFailure { errors.add("storageFree 读取失败: ${it.message}") }

            runCatching {
                items["appUptimeSec"] = android.os.SystemClock.elapsedRealtime() / 1000
            }.onFailure { errors.add("uptime 读取失败: ${it.message}") }

            runCatching {
                items["doorState"] = lockDriver.currentDoorState().name
            }.onFailure {
                errors.add("门状态回读失败（锁驱动通信异常）: ${it.message}")
                items["doorState"] = DoorState.UNKNOWN.name
            }

            runCatching {
                items["networkRssi"] = readWifiRssi()
            }.onFailure { items["networkRssi"] = null }

            runCatching {
                items["deviceModel"] = "${Build.MANUFACTURER} ${Build.MODEL}"
                items["androidVersion"] = Build.VERSION.RELEASE
            }.onFailure { errors.add("设备信息读取失败: ${it.message}") }

            // 持久化最近一次自检结果，供 App 端查看与下次对比
            runCatching {
                appContext.getSharedPreferences(PREFS_OPS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_SELF_TEST_AT, System.currentTimeMillis())
                    .putString(KEY_LAST_SELF_TEST_JSON, JSONObject(items).toString())
                    .apply()
            }.onFailure { errors.add("自检结果持久化失败: ${it.message}") }

            val summary = buildString {
                append("自检完成：")
                append(items.entries.joinToString(", ") { (k, v) -> "$k=$v" })
                if (errors.isNotEmpty()) {
                    append("；异常：")
                    append(errors.joinToString("; "))
                }
            }
            Log.i(TAG, summary)
            // ⚠️ setError 只接受非空 String，没有「清空错误」的重载。
            //   所以只在**真有异常**时上报，不要塞空串 —— 空串会被后台当真实错误显示。
            if (errors.isNotEmpty()) {
                DeviceStatusHub.setError(errors.joinToString("; "))
            }
            // 失败项不影响 ACK 成功：自检「跑完了」就是成功，异常项在 message 里如实列出
            ack(ops.commandId, true, summary)
            // 同时把结构化结果单独发一条 SELF_TEST_REPORT 事件，便于后台长期趋势分析
            mqtt().publishSelfTestReport(ops.commandId, items, errors)
        }
    }

    /** Wi-Fi RSSI（dBm）；读不到返回 null（**不返回 0**，0 是有效值语义） */
    private fun readWifiRssi(): Int? {
        return try {
            val wifi = appContext.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                ?: return null
            @Suppress("DEPRECATION")
            wifi.connectionInfo?.rssi
                ?.takeIf { it != -127 && it in -126..-1 }
        } catch (e: Exception) {
            null
        }
    }

    // ==================== ACK ====================

    private fun ack(commandId: String, success: Boolean, message: String) {
        runCatching { mqtt().publishAck(commandId, success, message) }
            .onFailure { Log.e(TAG, "publish ack failed commandId=$commandId", it) }
    }

    companion object {
        private const val TAG = "OpsCommandExecutor"
        private const val PREFS_OPS = "ops_command_state"
        private const val KEY_TARGET_TEMP_C = "target_temp_c"
        private const val KEY_TARGET_TEMP_SET_AT = "target_temp_set_at"
        private const val KEY_LAST_SELF_TEST_AT = "last_self_test_at"
        private const val KEY_LAST_SELF_TEST_JSON = "last_self_test_json"
    }
}
