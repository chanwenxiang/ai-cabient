package com.aicabinet.edge.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `KeystoreCipher` 的密文识别单测（L06）。
 *
 * 为什么值得测：`EdgeRuntimeConfig.getSecret` 完全靠这一个判定分流 ——
 *   true  → 交给 `decrypt`（失败则回退默认值）
 *   false → 原样当明文使用
 * 判错两个方向都有后果：密文被当明文用 ⇒ 拿 `enc:v1:...` 去连 MQTT，broker 直接拒鉴权，
 * 现场表现为「柜子连不上、但没有报错线索」；明文被当密文 ⇒ 解密抛异常、回退成构建期默认口令，
 * 于是现场改过的生产口令被静默丢弃。
 *
 * 判据刻意用**字面量** `"enc:v1:"` 而不是 `KeystoreCipher.PREFIX`：用常量写断言等于
 * 「实现改成什么、断言就跟着变成什么」，是恒真式，起不到守护作用。
 *
 * 纯 JVM：`isEncrypted` 只做 `String.startsWith`，不触碰 AndroidKeyStore / Base64。
 */
class KeystoreCipherTest {

    /** 密文前缀的**契约值**。改它意味着老 APK 与新 APK 的读取口径分叉，必须是有意为之。 */
    private val prefix = "enc:v1:"

    @Test
    fun `带前缀的负载判为密文`() {
        assertTrue(KeystoreCipher.isEncrypted(prefix + "QUJDREVGR0g="))
    }

    @Test
    fun `裸明文与空串判为非密文`() {
        assertFalse(KeystoreCipher.isEncrypted("dev-mqtt-device-pass"))
        assertFalse(KeystoreCipher.isEncrypted(""))
    }

    @Test
    fun `版本前缀不匹配时按明文处理而非尝试解密`() {
        // 只认 v1。将来升到 enc:v2: 时，旧版本 APK 会把它当明文原样使用，
        // 而不是「解密失败 ⇒ 静默回退构建期默认口令」。两种失败模式的现场可诊断性完全不同。
        assertFalse(KeystoreCipher.isEncrypted("enc:v2:QUJDREVGR0g="))
        assertFalse(KeystoreCipher.isEncrypted("ENC:V1:QUJDREVGR0g="))
    }

    @Test
    fun `前缀字样出现在中间不算密文`() {
        assertFalse(KeystoreCipher.isEncrypted("x" + prefix + "QUJDREVGR0g="))
    }

    @Test
    fun `前缀完整但负载为空仍判为密文（长度校验不在这里）`() {
        // 分层契约：本函数只管「是不是密文」，负载长度由 decrypt 判（`size <= IV` 抛异常），
        // 再由调用方的 runCatching 兜底。把它并进来会让这一层承担不属于它的判据。
        assertTrue(KeystoreCipher.isEncrypted(prefix))
    }
}
