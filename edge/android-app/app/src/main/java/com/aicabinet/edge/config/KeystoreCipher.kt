package com.aicabinet.edge.config

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * L06: 基于 AndroidKeyStore 的 AES/GCM 加解密工具（不引第三方依赖）。
 * 用于 SharedPreferences 中敏感字段（mqtt_password / internal_api_key / keystore 密码等）的加密落盘。
 *
 * - 密钥别名 [KEY_ALIAS]，首次使用时生成 256bit AES 密钥并常驻 AndroidKeyStore（不可导出）。
 *   需要 minSdk >= 23（本项目 minSdk=24，满足）。
 * - 密文格式：[PREFIX] + Base64(随机 IV(12B) + ciphertext)，每次加密重新生成随机 IV。
 * - 读取兼容：带 [PREFIX] 前缀则解密；明文原样返回（存量明文字段在首次覆盖保存后升级为密文）。
 */
object KeystoreCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "aicabinet-edge"
    const val PREFIX = "enc:v1:"
    private const val IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    /** 加密并编码为 "enc:v1:" + Base64(IV + ciphertext)。 */
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + cipherText.size)
        iv.copyInto(out, 0)
        cipherText.copyInto(out, iv.size)
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** 解码并解密 [encrypt] 产出的密文；格式不合法或校验失败抛异常，由调用方兜底。 */
    fun decrypt(encoded: String): String {
        require(isEncrypted(encoded)) { "not an encrypted payload" }
        val data = Base64.decode(encoded.removePrefix(PREFIX), Base64.NO_WRAP)
        if (data.size <= IV_LENGTH_BYTES) {
            throw IllegalArgumentException("payload too short")
        }
        val iv = data.copyOfRange(0, IV_LENGTH_BYTES)
        val cipherText = data.copyOfRange(IV_LENGTH_BYTES, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
