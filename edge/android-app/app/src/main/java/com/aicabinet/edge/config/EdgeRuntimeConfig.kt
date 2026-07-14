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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getString(context: Context, key: String, default: String): String =
        prefs(context).getString(key, default)?.trim()?.takeIf { it.isNotEmpty() } ?: default

    private fun getBoolean(context: Context, key: String, default: Boolean): Boolean =
        if (prefs(context).contains(key)) prefs(context).getBoolean(key, default) else default

    private fun getLong(context: Context, key: String, default: Long): Long =
        if (prefs(context).contains(key)) prefs(context).getLong(key, default) else default

    private fun putString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }
}
