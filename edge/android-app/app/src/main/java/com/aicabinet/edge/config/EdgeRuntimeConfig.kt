package com.aicabinet.edge.config

import android.content.Context
import android.util.Log
import com.aicabinet.edge.BuildConfig

/** BuildConfig 默认值 + SharedPreferences 运行时覆盖（工控机现场改 IP 免重编译）。 */
object EdgeRuntimeConfig {
    private const val TAG = "EdgeRuntimeConfig"
    private const val PREFS = "edge_runtime_config"

    fun deviceId(context: Context): String =
        getString(context, "device_id", BuildConfig.DEVICE_ID)

    fun mqttBroker(context: Context): String =
        getString(context, "mqtt_broker", BuildConfig.MQTT_BROKER)

    fun mqttUsername(context: Context): String =
        getString(context, "mqtt_username", BuildConfig.MQTT_USERNAME)

    fun mqttPassword(context: Context): String =
        getSecret(context, "mqtt_password", BuildConfig.MQTT_PASSWORD)

    fun mqttUseTls(context: Context): Boolean =
        getBoolean(context, "mqtt_use_tls", BuildConfig.MQTT_USE_TLS)

    /**
     * TLS 严格模式：为 true 时必须配置 truststore（自签/私有 CA）。
     * 公有 CA broker 可保持 false，使用系统默认信任库。
     */
    fun mqttTlsStrict(context: Context): Boolean =
        getBoolean(context, "mqtt_tls_strict", BuildConfig.MQTT_TLS_STRICT)

    fun mqttTrustStorePath(context: Context): String =
        getString(context, "mqtt_trust_store_path", BuildConfig.MQTT_TRUST_STORE_PATH)

    fun mqttTrustStorePassword(context: Context): String =
        getSecret(context, "mqtt_trust_store_password", BuildConfig.MQTT_TRUST_STORE_PASSWORD)

    fun mqttTrustStoreType(context: Context): String =
        getString(context, "mqtt_trust_store_type", BuildConfig.MQTT_TRUST_STORE_TYPE)

    fun mqttKeyStorePath(context: Context): String =
        getString(context, "mqtt_key_store_path", BuildConfig.MQTT_KEY_STORE_PATH)

    fun mqttKeyStorePassword(context: Context): String =
        getSecret(context, "mqtt_key_store_password", BuildConfig.MQTT_KEY_STORE_PASSWORD)

    fun mqttKeyStoreType(context: Context): String =
        getString(context, "mqtt_key_store_type", BuildConfig.MQTT_KEY_STORE_TYPE)

    fun tradeServiceUrl(context: Context): String =
        getString(context, "trade_service_url", BuildConfig.TRADE_SERVICE_URL)

    /**
     * 审计 A1/P0-2：设备 HTTP 凭据 secret（与 X-Device-Id 配对，OTA/素材端点一机一密）。
     * 由运营台签发（明文一次性返回）、现场装机注入；未注入时 OTA/进度上报静默跳过。
     */
    fun httpDeviceSecret(context: Context): String =
        getSecret(context, "http_device_secret", BuildConfig.HTTP_DEVICE_SECRET)

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

    /**
     * L06: 敏感字段（mqtt_password / http_device_secret / truststore/keystore 密码）统一经
     * [KeystoreCipher]（AndroidKeyStore AES/GCM）加密落盘，不再明文存储。
     * 读取兼容存量明文（原样返回），首次覆盖保存后升级为密文。
     */
    fun saveSecret(context: Context, key: String, value: String) = putSecret(context, key, value)

    private fun putSecret(context: Context, key: String, value: String) {
        // A6 整改：加密失败**不得**静默回落明文落盘（明文密钥 + allowBackup/adbd 可导出=裸奔）。
        // Keystore 不可用属设备级故障，宁可让装机注入失败重试，也不落明文。
        val stored = runCatching { KeystoreCipher.encrypt(value) }
            .getOrElse {
                throw IllegalStateException(
                    "AndroidKeyStore 不可用，拒绝以明文保存敏感字段 $key: ${it.message}",
                    it
                )
            }
        prefs(context).edit().putString(key, stored).apply()
    }

    /** 敏感字段读取：带 enc:v1: 前缀则解密，解密失败回退默认值；存量明文原样返回。 */
    private fun getSecret(context: Context, key: String, default: String): String {
        val stored = prefs(context).getString(key, null)?.trim()
        if (stored.isNullOrEmpty()) return default
        if (!KeystoreCipher.isEncrypted(stored)) return stored
        return runCatching { KeystoreCipher.decrypt(stored) }
            .onFailure { Log.w(TAG, "secret decrypt failed for $key: ${it.message}") }
            .getOrDefault(default)
    }
}
