package com.aicabinet.edge.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `EdgeRuntimeConfig` 的 **SharedPreferences 真读写** 单测。
 *
 * 为什么不放在既有的 `EdgeRuntimeConfigTest`（纯 JVM）里：那个类只测
 * `isPlaceholderDeviceId` 这种无状态纯函数。本类要测的是「现场改配置能不能生效」——
 * 涉及真实 `SharedPreferences`，必须让 Robolectric 提供 Application Context。
 *
 * 为什么值得测：工控机现场改 IP / 改口令**不重编译**，全靠这一层覆盖。三种失效都很难查：
 *   - 覆盖读不到 ⇒ 现场改了不生效，表现为「明明改了还连旧 broker」；
 *   - 空白值没回退默认 ⇒ 手抖多敲一个空格，`mqtt_broker=""` 直接把 MQTT 打死；
 *   - 数值越界没夹紧 ⇒ `mqtt_outbound_max_items` 填 1，出站队列退化成「只留一条」，
 *     关门事件被自己挤掉，现场表现为「偶发丢门事件、无任何报错」。
 *
 * 断言刻意用**字面量**（`"CAB-001"` / `50` / `5000`）而不是 `BuildConfig.DEVICE_ID` 之类的**生产常量**：
 * 用生产常量写断言等于「实现改成什么、断言跟着变成什么」，是恒真式，守护不了任何东西
 * （`PROJECT-REFERENCE.md` §11.6 失效形态③）。
 */
@RunWith(RobolectricTestRunner::class)
class EdgeRuntimeConfigPrefsTest {

    private lateinit var ctx: Context

    /** 与 `EdgeRuntimeConfig.PREFS` 同值。用字面量钉住：改 prefs 文件名会让**已上线设备**丢配置。 */
    private val prefsName = "edge_runtime_config"

    private val prefs by lazy { ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE) }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // 每个用例从干净 prefs 起步：Robolectric 每个 @Test 会重建 Application。
        prefs.edit().clear().commit()
    }

    // ---------- 默认值 ----------

    @Test
    fun `未覆盖时读构建期默认值`() {
        assertEquals("CAB-001", EdgeRuntimeConfig.deviceId(ctx))
        assertEquals(500, EdgeRuntimeConfig.mqttOutboundMaxItems(ctx))
        assertEquals(200, EdgeRuntimeConfig.mqttOutboundMaxAttempts(ctx))
        assertEquals(120_000L, EdgeRuntimeConfig.shoppingCloseTimeoutMs(ctx))
        assertEquals(600_000L, EdgeRuntimeConfig.operatorCloseTimeoutMs(ctx))
        assertTrue(EdgeRuntimeConfig.useMockDriver(ctx))
    }

    @Test
    fun `覆盖后读到现场值而非默认值`() {
        prefs.edit().putString("mqtt_broker", "tcp://192.168.1.50:1883").commit()
        assertEquals("tcp://192.168.1.50:1883", EdgeRuntimeConfig.mqttBroker(ctx))
        assertNotEquals("tcp://10.0.2.2:11883", EdgeRuntimeConfig.mqttBroker(ctx))
    }

    // ---------- trim / 空白回退 ----------

    @Test
    fun `字符串读取会 trim`() {
        prefs.edit().putString("mqtt_broker", "  tcp://10.0.0.9:1883  ").commit()
        assertEquals("tcp://10.0.0.9:1883", EdgeRuntimeConfig.mqttBroker(ctx))
    }

    @Test
    fun `空白值回退默认而不是当成空字符串`() {
        prefs.edit().putString("mqtt_broker", "   ").commit()
        // 关键口径：空白 ⇒ 默认。若回退成 ""，现场「手抖多敲空格」会把 broker 打成空串。
        assertEquals("tcp://10.0.2.2:11883", EdgeRuntimeConfig.mqttBroker(ctx))
    }

    @Test
    fun `空白 deviceId 回退默认`() {
        prefs.edit().putString("device_id", " ").commit()
        assertEquals("CAB-001", EdgeRuntimeConfig.deviceId(ctx))
    }

    // ---------- contains 守卫：显式 false / 0 必须被尊重 ----------

    @Test
    fun `显式写入 false 会被尊重而不是回退默认`() {
        // getBoolean 用 prefs.contains 分流，正是为了让「显式 false」不被误判成「没配过」。
        // 若改成 getBoolean(key, default)，这里读到的仍是默认 true ⇒ 现场关不掉 mock 驱动。
        prefs.edit().putBoolean("use_mock_driver", false).commit()
        assertFalse(EdgeRuntimeConfig.useMockDriver(ctx))
    }

    @Test
    fun `显式写入 0 会被尊重而不是回退默认`() {
        prefs.edit().putLong("mock_shopping_ms", 0L).commit()
        assertEquals(0L, EdgeRuntimeConfig.mockShoppingMs(ctx))
    }

    // ---------- 数值夹紧（含守卫用例） ----------

    @Test
    fun `出站队列容量按下界夹紧`() {
        prefs.edit().putInt("mqtt_outbound_max_items", 10).commit()
        assertEquals(50, EdgeRuntimeConfig.mqttOutboundMaxItems(ctx))
    }

    @Test
    fun `出站队列容量按上界夹紧`() {
        prefs.edit().putInt("mqtt_outbound_max_items", 999_999).commit()
        assertEquals(5_000, EdgeRuntimeConfig.mqttOutboundMaxItems(ctx))
    }

    @Test
    fun `出站队列容量在区间内原样返回`() {
        // 守卫用例：证明上两条测的是"夹紧"而不是"恒定返回某个边界值"。
        // 没有这一条，把实现写成 `return 5000` 也能让前两条全绿。
        prefs.edit().putInt("mqtt_outbound_max_items", 777).commit()
        assertEquals(777, EdgeRuntimeConfig.mqttOutboundMaxItems(ctx))
    }

    @Test
    fun `重试上限按上下界夹紧且区间内原样`() {
        prefs.edit().putInt("mqtt_outbound_max_attempts", 1).commit()
        assertEquals(3, EdgeRuntimeConfig.mqttOutboundMaxAttempts(ctx))

        prefs.edit().putInt("mqtt_outbound_max_attempts", 500_000).commit()
        assertEquals(1_000, EdgeRuntimeConfig.mqttOutboundMaxAttempts(ctx))

        prefs.edit().putInt("mqtt_outbound_max_attempts", 42).commit()
        assertEquals(42, EdgeRuntimeConfig.mqttOutboundMaxAttempts(ctx))
    }

    // ---------- 写入器 ----------

    @Test
    fun `saveDeviceId 落盘并 trim`() {
        EdgeRuntimeConfig.saveDeviceId(ctx, "  CAB-007  ")
        assertEquals("CAB-007", EdgeRuntimeConfig.deviceId(ctx))
        // 直接看落盘原值：确认写进去的就是 trim 后的（而不是靠读取时 trim 掩盖）。
        assertEquals("CAB-007", prefs.getString("device_id", null))
    }

    @Test
    fun `saveBroker 落盘并 trim`() {
        EdgeRuntimeConfig.saveBroker(ctx, "\ttcp://172.16.0.7:1883\n")
        assertEquals("tcp://172.16.0.7:1883", prefs.getString("mqtt_broker", null))
    }

    @Test
    fun `saveMultiCamera 落盘`() {
        assertFalse(EdgeRuntimeConfig.multiCameraEnabled(ctx))
        EdgeRuntimeConfig.saveMultiCamera(ctx, true)
        assertTrue(EdgeRuntimeConfig.multiCameraEnabled(ctx))
    }

    // ---------- ensureDeviceId ----------

    @Test
    fun `ensureDeviceId 在 mock 档下保留占位号且不落盘`() {
        // mock 档（本任务 testMockDebugUnitTest，USE_MOCK_DRIVER=true）刻意保留 CAB-001 便于联调；
        // 且**不应**写盘 —— 若写盘，切到 device 档后现场会带着一个 mock 期生成的假编号。
        assertEquals("CAB-001", EdgeRuntimeConfig.ensureDeviceId(ctx))
        assertFalse(prefs.contains("device_id"))
    }

    @Test
    fun `ensureDeviceId 对现场已编号原样返回`() {
        EdgeRuntimeConfig.saveDeviceId(ctx, "CAB-042")
        assertEquals("CAB-042", EdgeRuntimeConfig.ensureDeviceId(ctx))
        assertEquals("CAB-042", prefs.getString("device_id", null))
    }
}
