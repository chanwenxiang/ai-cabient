package com.aicabinet.edge.config

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 敏感字段落盘/读取的**失败路径**单测（L06 + A6 整改）。
 *
 * 背景：Robolectric **不实现 AndroidKeyStore** —— 探针实测
 * `KeystoreCipher.encrypt` 抛 `KeyStoreException: AndroidKeyStore not found`。
 * 所以本类**测不了**加解密往返（那需要真机/仪器化），但恰好能测一件更重要的事：
 * **keystore 不可用时这一层的真实行为**。
 *
 * 行为沿革（绊线要求的「重新取证」，2026-10-16）：
 *  - 旧行为：`putSecret` 加密失败 ⇒ 原样明文落盘（曾以本类钉住防误判）；
 *    被 2026-10-05 审计列为 P2（明文 + allowBackup=adb 可导出）。
 *  - 新行为（A6 fail-closed）：加密失败 ⇒ 抛 IllegalStateException **拒绝保存**——
 *    宁可装机注入失败重试，也不落明文；allowBackup 同步改 false。
 *    本类用例已随之重写为钉住新口径；若 `KeystoreCipher` 环境再变（如 Robolectric
 *    支持了 AndroidKeyStore），`keystore_不可用时保存被拒绝` 会变红 ⇒ 重新取证。
 */
@RunWith(RobolectricTestRunner::class)
class SecretStorageFallbackTest {

    private lateinit var ctx: Context

    private val prefs by lazy { ctx.getSharedPreferences("edge_runtime_config", Context.MODE_PRIVATE) }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().commit()
    }

    // ---------- 环境事实（绊线） ----------

    @Test
    fun `keystore_不可用时加密抛异常且异常类型是KeyStoreException`() {
        val r = runCatching { KeystoreCipher.encrypt("probe") }
        assertTrue("本机 Robolectric 竟能加密 —— 环境已变，请重新取证", r.isFailure)
        assertEquals(
            "异常类型变了说明失败原因变了（不再是「keystore 缺失」），本文结论需重写",
            java.security.KeyStoreException::class.java,
            r.exceptionOrNull()?.javaClass
        )
    }

    @Test
    fun `keystore_不可用时保存被拒绝且不落任何明文`() {
        val r = runCatching { EdgeRuntimeConfig.saveSecret(ctx, "mqtt_password", "dev-mqtt-device-pass") }
        assertTrue("keystore 不可用时 saveSecret 应抛异常（A6 fail-closed），实际成功", r.isFailure)
        assertTrue(
            "异常类型应为 IllegalStateException（Keystore 不可用语义）",
            r.exceptionOrNull() is IllegalStateException
        )
        // 关键断言：storage 里**不存在**该键——明文与密文都不落
        assertNull("拒绝保存后不得有任何落盘残留", prefs.getString("mqtt_password", null))
    }

    @Test
    fun `保存被拒后读取回退构建期默认值`() {
        runCatching { EdgeRuntimeConfig.saveSecret(ctx, "mqtt_password", "prod-pass-abc") }
        // 字面量断言（仓纪律：不引 BuildConfig 防恒真式）——mock 构建默认见 build.gradle.kts
        assertEquals("dev-mqtt-device-pass", EdgeRuntimeConfig.mqttPassword(ctx))
    }

    @Test
    fun `不同密钥字段读取互不串值`() {
        // 不经 saveSecret（keystore 不可用会被拒），直接预置存量明文验证读取分流
        prefs.edit().putString("mqtt_password", "pw-for-mqtt").commit()
        prefs.edit().putString("http_device_secret", "key-for-internal").commit()
        assertEquals("pw-for-mqtt", EdgeRuntimeConfig.mqttPassword(ctx))
        assertEquals("key-for-internal", EdgeRuntimeConfig.httpDeviceSecret(ctx))
    }

    // ---------- 读取分流 ----------

    @Test
    fun `存量明文原样返回`() {
        prefs.edit().putString("http_device_secret", "legacy-plain-key").commit()
        assertEquals("legacy-plain-key", EdgeRuntimeConfig.httpDeviceSecret(ctx))
    }

    @Test
    fun `明文读取会 trim`() {
        prefs.edit().putString("http_device_secret", "  legacy-plain-key  ").commit()
        assertEquals("legacy-plain-key", EdgeRuntimeConfig.httpDeviceSecret(ctx))
    }

    @Test
    fun `密文前缀但无法解密时回退构建期默认值_fail_closed`() {
        // 造一个长度合法（> 12 字节 IV）的 enc:v1: 负载：长度校验会放行，
        // 真正卡住它的是 KeystoreCipher.getOrCreateKey() 的 KeyStoreException。
        val payload = ByteArray(40) { 7 }
        prefs.edit()
            .putString("http_device_secret", "enc:v1:" + Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()
        // fail-closed 口径：解密失败 ⇒ 回退默认（而不是把整串密文当口令用）。
        assertEquals("", EdgeRuntimeConfig.httpDeviceSecret(ctx))
    }

    @Test
    fun `密文前缀但负载过短时同样回退默认值且不崩溃`() {
        prefs.edit().putString("http_device_secret", "enc:v1:" + Base64.encodeToString(ByteArray(4), Base64.NO_WRAP)).commit()
        assertEquals("", EdgeRuntimeConfig.httpDeviceSecret(ctx))
    }

    @Test
    fun `空值等同未配置_回退默认`() {
        prefs.edit().putString("http_device_secret", "").commit()
        assertEquals("", EdgeRuntimeConfig.httpDeviceSecret(ctx))
    }

    // ---------- decrypt 的入参校验（先于任何密钥操作） ----------

    @Test
    fun `decrypt_拒绝非密文前缀的输入`() {
        val e = runCatching { KeystoreCipher.decrypt("plain-text") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertEquals("not an encrypted payload", e?.message)
    }

    @Test
    fun `decrypt_负载过短在触碰密钥前就拒绝`() {
        // 关键顺序契约：长度校验（size <= IV）在 Cipher.init 之前。
        // 若有人把 getOrCreateKey() 提到前面，这里会变成 KeyStoreException ⇒ 本断言变红，
        // 提示「校验顺序被改了」（在真机上就表现为白耗一次 keystore 调用）。
        val e = runCatching { KeystoreCipher.decrypt("enc:v1:" + Base64.encodeToString(ByteArray(12), Base64.NO_WRAP)) }
            .exceptionOrNull()
        assertTrue("期望 IllegalArgumentException，实际 ${e?.javaClass?.name}", e is IllegalArgumentException)
        assertEquals("payload too short", e?.message)
    }

    @Test
    fun `decrypt_负载非法Base64会被拒绝`() {
        val e = runCatching { KeystoreCipher.decrypt("enc:v1:!!!not-base64!!!") }.exceptionOrNull()
        assertTrue("期望抛异常，实际成功", e != null)
    }
}
