package com.aicabinet.edge.mqtt

import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * E-P1-1：MQTT TLS SocketFactory。
 * - 无私有 CA：可用系统默认信任库（公网/公有 CA broker）
 * - 自签/私有 CA：必须配置 truststore，否则 [strict]=true 时拒绝连接（避免「开了 TLS 却集体掉线」难排查）
 * - mTLS：可选 keystore（设备客户端证书）
 */
object MqttSslSocketFactories {

    fun create(
        trustStorePath: String,
        trustStorePassword: String,
        trustStoreType: String,
        keyStorePath: String,
        keyStorePassword: String,
        keyStoreType: String,
        strictCustomTrust: Boolean
    ): SSLSocketFactory {
        val trustPath = trustStorePath.trim()
        val keyPath = keyStorePath.trim()

        if (trustPath.isEmpty()) {
            if (strictCustomTrust) {
                throw IllegalStateException(
                    "MQTT TLS 已开启且 mqtt_tls_strict=true，但未配置 truststore。" +
                        "自签/私有 CA 请下发 PKCS12/JKS 到设备并配置 mqtt_trust_store_path；" +
                        "仅公有 CA 时可设 mqtt_tls_strict=false。"
                )
            }
            if (keyPath.isEmpty()) {
                return SSLSocketFactory.getDefault() as SSLSocketFactory
            }
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        if (trustPath.isNotEmpty()) {
            tmf.init(loadKeyStore(trustPath, trustStorePassword, trustStoreType.ifBlank { "PKCS12" }))
        } else {
            tmf.init(null as KeyStore?)
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        if (keyPath.isNotEmpty()) {
            kmf.init(
                loadKeyStore(keyPath, keyStorePassword, keyStoreType.ifBlank { "PKCS12" }),
                keyStorePassword.toCharArray()
            )
        } else {
            kmf.init(null, null)
        }

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(
            if (keyPath.isNotEmpty()) kmf.keyManagers else null,
            tmf.trustManagers,
            null
        )
        return ctx.socketFactory
    }

    private fun loadKeyStore(path: String, password: String, type: String): KeyStore {
        val file = File(path)
        if (!file.isFile) {
            throw IllegalStateException("MQTT TLS 证书库不存在或不可读: $path")
        }
        val store = KeyStore.getInstance(type)
        FileInputStream(file).use { input ->
            store.load(input, password.toCharArray())
        }
        return store
    }
}
