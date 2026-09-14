package com.aicabinet.edge.config

import android.content.Context
import com.aicabinet.edge.BuildConfig

/** BuildConfig 默认值 + SharedPreferences 运行时覆盖（工控机现场改 IP 免重编译）。 */
object EdgeRuntimeConfig {
    private const val PREFS = "edge_runtime_config"

    fun deviceId(context: Context): String =
        getString(context, "device_id", BuildConfig.DEVICE_ID)

    fun mqttBroker(context: Context): String =
        getString(context, "mqtt_broker", BuildConfig.MQTT_BROKER)

    fun mqttUsername(context: Context): String =
        getString(context, "mqtt_username", BuildConfig.MQTT_USERNAME)

    fun mqttPassword(context: Context): String =
        getString(context, "mqtt_password", BuildConfig.MQTT_PASSWORD)

    fun mqttUseTls(context: Context): Boolean =
        getBoolean(context, "mqtt_use_tls", BuildConfig.MQTT_USE_TLS)

    fun tradeServiceUrl(context: Context): String =
        getString(context, "trade_service_url", BuildConfig.TRADE_SERVICE_URL)

    fun internalApiKey(context: Context): String =
        getString(context, "internal_api_key", BuildConfig.INTERNAL_API_KEY)

    fun useMockDriver(context: Context): Boolean =
        getBoolean(context, "use_mock_driver", BuildConfig.USE_MOCK_DRIVER)

    /** Mock 模式下模拟用户购物时长，之后自动关门 */
    fun mockShoppingMs(context: Context): Long =
        getLong(context, "mock_shopping_ms", 5_000L)

    /** 消费者购物最长等待关门时间 */
    fun shoppingCloseTimeoutMs(context: Context): Long =
        getLong(context, "shopping_close_timeout_ms", 120_000L)

    /** 运营补货最长等待关门时间 */
    fun operatorCloseTimeoutMs(context: Context): Long =
        getLong(context, "operator_close_timeout_ms", 600_000L)

    fun serialPortPath(context: Context): String =
        getString(context, "serial_port_path", BuildConfig.SERIAL_PORT_PATH)

    fun multiCameraEnabled(context: Context): Boolean =
        getBoolean(context, "multi_camera_enabled", BuildConfig.MULTI_CAMERA_ENABLED)

    fun saveMultiCamera(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("multi_camera_enabled", enabled).apply()

    fun saveBroker(context: Context, broker: String) =
        putString(context, "mqtt_broker", broker.trim())

    fun saveDeviceId(context: Context, deviceId: String) =
        putString(context, "device_id", deviceId.trim())

    /**
     * E-P2-4：真机首次启动若仍是构建默认 `CAB-001`/空，生成并持久化本机 ID，避免多机撞号。
     * mock 驱动保留 `CAB-001` 便于联调；现场正式编号仍可用 [saveDeviceId] 覆盖。
     */
    fun ensureDeviceId(context: Context): String {
        val current = deviceId(context).trim()
        if (current.isNotEmpty() && !isPlaceholderDeviceId(current)) {
            return current
        }
        if (BuildConfig.USE_MOCK_DRIVER) {
            return current.ifBlank { "CAB-001" }
        }
        val generated = "EDGE-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        saveDeviceId(context, generated)
        return generated
    }

    fun isPlaceholderDeviceId(deviceId: String): Boolean =
        deviceId.isBlank() || deviceId.equals("CAB-001", ignoreCase = true)

    /** E-P2-5：MQTT 出站队列容量（满后优先丢非关键 topic） */
    fun mqttOutboundMaxItems(context: Context): Int =
        getInt(context, "mqtt_outbound_max_items", 500).coerceIn(50, 5_000)

    fun mqttOutboundMaxAttempts(context: Context): Int =
        getInt(context, "mqtt_outbound_max_attempts", 200).coerceIn(3, 1_000)

    /** 离线视频上传重试上限 */
    fun offlineUploadMaxAttempts(context: Context): Int =
        getInt(context, "offline_upload_max_attempts", 20).coerceIn(3, 200)

    /**
     * E-P2-6：MinIO/预签名上传断路器。
     * 连续失败达到阈值后冷却 [minioCircuitOpenMs]，避免打满离线队列与线程。
     */
    fun minioCircuitFailureThreshold(context: Context): Int =
        getInt(context, "minio_circuit_failure_threshold", 5).coerceIn(2, 50)

    fun minioCircuitOpenMs(context: Context): Long =
        getLong(context, "minio_circuit_open_ms", 60_000L).coerceIn(5_000L, 600_000L)

    /** 单次会话录像软上限（超时仅打日志，不停录；便于现场调参） */
    fun videoMaxRecordMs(context: Context): Long =
        getLong(context, "video_max_record_ms", 600_000L)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getString(context: Context, key: String, default: String): String =
        prefs(context).getString(key, default)?.trim()?.takeIf { it.isNotEmpty() } ?: default

    private fun getBoolean(context: Context, key: String, default: Boolean): Boolean =
        if (prefs(context).contains(key)) prefs(context).getBoolean(key, default) else default

    private fun getLong(context: Context, key: String, default: Long): Long =
        if (prefs(context).contains(key)) prefs(context).getLong(key, default) else default

    private fun getInt(context: Context, key: String, default: Int): Int =
        if (prefs(context).contains(key)) prefs(context).getInt(key, default) else default

    private fun putString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }
}
