package com.aicabinet.edge.ota

import android.content.Context
import android.util.Log
import com.aicabinet.edge.config.EdgeRuntimeConfig
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OTA 升级状态。取值必须与服务端 `OtaService.ALLOWED_PROGRESS_STATUS`
 * （IDLE / DOWNLOADING / INSTALLING / SUCCESS / FAILED）**逐字一致** —— 服务端对未知取值直接 400。
 *
 * 用 enum 而不是裸字符串：越界在**编译期**就不可能发生，因此端侧不需要再写一个
 * 「只允许这五个值」的运行时校验 —— 那种校验写出来永远为真，属失效形态③（判据恒真）。
 */
enum class OtaUpgradeStatus(val wire: String) {
    IDLE("IDLE"),
    DOWNLOADING("DOWNLOADING"),
    INSTALLING("INSTALLING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
}

/**
 * 上报通道抽象。
 *
 * 抽成函数式接口是**为了可测**：本模块没有 MockWebServer 依赖，真实 OkHttp 在 JVM 单测里
 * 无法直接验证「发了什么报文」；注入假实现即可把「报文契约」与「网络栈」分开验证。
 */
fun interface OtaProgressTransport {
    /** @return HTTP 状态码；网络故障可抛异常（调用方负责吞掉）。 */
    fun post(url: String, apiKey: String, json: String): Int
}

/**
 * OTA 升级进度上报（O2）。
 *
 * 背景：服务端 `POST /internal/v1/devices/{id}/ota/progress` 与 `ota_device_report` 的
 * target_version / upgrade_status / progress_percent / error_message 四列（V280）**早已就绪**，
 * 但设备侧从来没有人调用它 —— 于是「卡在下载 60%」和「压根没开始升级」在运营台看起来
 * 完全一样，且没有任何线索可查。本类就是那个缺失的调用方。
 *
 * 三条设计约束：
 * 1. **best-effort**：上报失败绝不能打断升级流程（否则观测手段本身成了故障源）。
 * 2. **报文自洽**：不依赖服务端的宽容收敛（服务端 `reportProgress` 会把非 FAILED 状态的
 *    原因清空），端侧同样只在该带原因时才带 —— 两处各写对，而不是两处一起写错。
 * 3. 与 [OtaChecker] 的状态机同源，避免出现「服务端允许但端侧发不出」的取值。
 */
object OtaProgressReporter {
    private const val TAG = "OtaProgress"

    /** 用 Jackson 而非 `org.json`：`isReturnDefaultValues=true` 会让 android.jar 里的
     *  `JSONObject` 在 JVM 单测中退化成默认值（`put` 不生效），报文就**测不了**。
     *  同仓 `VideoClipJson` 也正因此改用 Jackson。 */
    private val mapper = jacksonObjectMapper()

    /** 上报用**独立** client（不复用 OtaChecker 那个 readTimeout=120s 的下载 client）：
     *  上报必须快失败。它跑在 `CabinetService` 的 IO 线程上、且在 MQTT 连接之前，
     *  若超时过长会把整个启动链拖住。3s 足够覆盖同内网的正常往返。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val httpTransport = OtaProgressTransport { url, apiKey, json ->
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("X-Internal-Api-Key", apiKey)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { it.code }
    }

    /**
     * 测试接缝：非 null 时覆盖默认 HTTP 传输。
     *
     * 生产代码**从不**给它赋值（保持 null）。存在的理由：真实 OkHttp 在 JVM 单测里会
     * 真去连 `tradeServiceUrl`，既慢又不确定；有了它，[OtaInstallResultReceiver] 这类
     * 由系统实例化、无法注入构造参数的对象也能被确定性地测。
     */
    internal var transportForTest: OtaProgressTransport? = null

    /** 收敛到 0-100。服务端 `clampProgressPercent` 也做同样收敛 —— 两侧都做是刻意的：
     *  端侧不该把无意义的值发上网络（也让「报文本身」可被断言）。 */
    fun clampPercent(percent: Int?): Int = (percent ?: 0).coerceIn(0, 100)

    /**
     * 构造上报体。字段名对齐服务端 `DeviceInternalController.OtaProgressRequest`。
     *
     * 空值一律**不发该字段**（而非发 `null`）：服务端 record 对应位置即为 null，
     * 少发一个键不会改变语义，但让报文更小、日志更干净。
     */
    fun buildPayload(
        status: OtaUpgradeStatus,
        targetVersion: String? = null,
        progressPercent: Int? = null,
        errorMessage: String? = null,
    ): String {
        val fields = linkedMapOf<String, Any>(
            "status" to status.wire,
            "progressPercent" to clampPercent(progressPercent),
        )
        trimToNull(targetVersion)?.let { fields["targetVersion"] = it }
        // 只有 FAILED 带原因：与 OtaService.reportProgress 的收敛一致，
        // 免得库里出现「SUCCESS 却带着错误原因」这种自相矛盾的历史行。
        if (status == OtaUpgradeStatus.FAILED) {
            trimToNull(errorMessage)?.let { fields["errorMessage"] = it }
        }
        return mapper.writeValueAsString(fields)
    }

    fun progressUrl(baseUrl: String, deviceId: String): String =
        "${baseUrl.trimEnd('/')}/internal/v1/devices/$deviceId/ota/progress"

    /**
     * 上报一次进度。**永不抛异常**，返回是否被服务端接受（2xx）。
     *
     * 返回值只用于日志与测试断言 —— 生产调用点**不应**根据它改变控制流：
     * 上报失败不是升级失败。
     */
    fun report(
        context: Context,
        status: OtaUpgradeStatus,
        targetVersion: String? = null,
        progressPercent: Int? = null,
        errorMessage: String? = null,
        transport: OtaProgressTransport = transportForTest ?: httpTransport,
    ): Boolean {
        return try {
            val base = EdgeRuntimeConfig.tradeServiceUrl(context)
            val deviceId = EdgeRuntimeConfig.ensureDeviceId(context)
            val url = progressUrl(base, deviceId)
            val payload = buildPayload(status, targetVersion, progressPercent, errorMessage)
            val code = transport.post(url, EdgeRuntimeConfig.internalApiKey(context), payload)
            if (code !in 200..299) {
                Log.w(TAG, "OTA progress not accepted HTTP $code status=${status.wire} body=$payload")
            }
            code in 200..299
        } catch (e: Exception) {
            // 观测手段本身不能成为故障源：这里吞掉一切并降级为一条日志。
            // 代价是这一次状态点丢失 —— 下一个状态点会带来更新的一行，可接受。
            Log.w(TAG, "OTA progress skipped: ${e.message}", e)
            false
        }
    }

    private fun trimToNull(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }
}
