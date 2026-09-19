package com.aicabinet.edge.config

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 敏感字段落盘/读取的**失败路径**单测（L06）。
 *
 * 背景：Robolectric **不实现 AndroidKeyStore** —— 探针实测
 * `KeystoreCipher.encrypt` 抛 `KeyStoreException: AndroidKeyStore not found`。
 * 所以本类**测不了**加解密往返（那需要真机/仪器化），但恰好能测一件更重要的事：
 * **keystore 不可用时这一层的真实行为**。
 *
 * `EdgeRuntimeConfig.putSecret` 的写法是
 *   `runCatching { KeystoreCipher.encrypt(value) }.onFailure { Log.w(...) }.getOrDefault(value)`
 * 即「加密失败 ⇒ 原样明文落盘，只留一行 warn 日志」。这不是猜测，是代码读出来的；
 * 本类把它**钉成可执行断言**，免得将来有人以为「敏感字段一律密文落盘」。
 *
 * ⚠️ 如果哪天 Robolectric 支持了 AndroidKeyStore，`keystore_不可用时密文前缀不出现` 会**变红**。
 * 那是**有意的绊线**：环境变了，这段关于「失败路径」的结论必须重新取证，而不是继续绿着骗人。
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
    fun `keystore_不可用时密文前缀不出现`() {
        EdgeRuntimeConfig.saveSecret(ctx, "mqtt_password", "dev-mqtt-device-pass")
        val raw = prefs.getString("mqtt_password", null)
        assertEquals("dev-mqtt-device-pass", raw)
        // 字面量前缀而非 KeystoreCipher.PREFIX：用常量写断言会跟着实现漂，是恒真式。
        assertFalse("若这里变红 ⇒ 加密真的生效了，本文「明文回退」的结论作废", raw!!.startsWith("enc:v1:"))
    }

    @Test
    fun `明文回退后仍能读回原值`() {
        EdgeRuntimeConfig.saveSecret(ctx, "mqtt_password", "prod-pass-abc")
        assertEquals("prod-pass-abc", EdgeRuntimeConfig.mqttPassword(ctx))
    }

    @Test
    fun `不同密钥字段互不串值`() {
        EdgeRuntimeConfig.saveSecret(ctx, "mqtt_password", "pw-for-mqtt")
        EdgeRuntimeConfig.saveSecret(ctx, "internal_api_key", "key-for-internal")
        assertEquals("pw-for-mqtt", EdgeRuntimeConfig.mqttPassword(ctx))
        assertEquals("key-for-internal", EdgeRuntimeConfig.internalApiKey(ctx))
    }

    // ---------- 读取分流 ----------

    @Test
    fun `存量明文原样返回`() {
        prefs.edit().putString("internal_api_key", "legacy-plain-key").commit()
        assertEquals("legacy-plain-key", EdgeRuntimeConfig.internalApiKey(ctx))
    }

    @Test
    fun `明文读取会 trim`() {
        prefs.edit().putString("internal_api_key", "  legacy-plain-key  ").commit()
        assertEquals("legacy-plain-key", EdgeRuntimeConfig.internalApiKey(ctx))
    }

    @Test
    fun `密文前缀但无法解密时回退构建期默认值_fail_closed`() {
        // 造一个长度合法（> 12 字节 IV）的 enc:v1: 负载：长度校验会放行，
        // 真正卡住它的是 KeystoreCipher.getOrCreateKey() 的 KeyStoreException。
        val payload = ByteArray(40) { 7 }
        prefs.edit()
            .putString("internal_api_key", "enc:v1:" + Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()
        // fail-closed 口径：解密失败 ⇒ 回退默认（而不是把整串密文当口令用）。
        assertEquals("dev-internal-key-change-me", EdgeRuntimeConfig.internalApiKey(ctx))
    }

    @Test
    fun `密文前缀但负载过短时同样回退默认值且不崩溃`() {
        prefs.edit().putString("internal_api_key", "enc:v1:" + Base64.encodeToString(ByteArray(4), Base64.NO_WRAP)).commit()
        assertEquals("dev-internal-key-change-me", EdgeRuntimeConfig.internalApiKey(ctx))
    }

    @Test
    fun `空值等同未配置_回退默认`() {
        prefs.edit().putString("internal_api_key", "").commit()
        assertEquals("dev-internal-key-change-me", EdgeRuntimeConfig.internalApiKey(ctx))
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
