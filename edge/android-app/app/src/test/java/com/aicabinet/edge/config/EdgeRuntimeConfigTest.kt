package com.aicabinet.edge.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EdgeRuntimeConfig.isPlaceholderDeviceId` 单测。
 *
 * 为什么值得测：E-P2-4 要解决的问题是**多机撞号** —— 真机首启若仍带着构建默认的
 * `CAB-001`，多台柜子会以同一个 deviceId 连 MQTT，服务端按 deviceId 路由开门指令，
 * 现场表现为「A 柜开门、B 柜门响」。`ensureDeviceId` 用这个判定决定「要不要生成新编号」，
 * 判宽了会保留撞号，判严了会在现场把运维手工编号也覆盖掉。
 *
 * 契约（含一处必须钉住的口径）：
 *  - 空白与 `CAB-001`（大小写不敏感）算占位；
 *  - 其它编号一律不算占位；
 *  - **本函数自身不 trim**：`" CAB-001 "` 返回 false。这是刻意的分层 ——
 *    `ensureDeviceId` 先对 `deviceId(context)` 做 trim 再调用它，trim 责任在调用方。
 *    钉住这条是为了防止有人「顺手加个 trim」，那会悄悄改变 `ensureDeviceId` 的入参语义。
 */
class EdgeRuntimeConfigTest {

    @Test
    fun `空白串算占位`() {
        assertTrue(EdgeRuntimeConfig.isPlaceholderDeviceId(""))
        assertTrue(EdgeRuntimeConfig.isPlaceholderDeviceId("   "))
    }

    @Test
    fun `构建默认 CAB-001 算占位且大小写不敏感`() {
        assertTrue(EdgeRuntimeConfig.isPlaceholderDeviceId("CAB-001"))
        assertTrue(EdgeRuntimeConfig.isPlaceholderDeviceId("cab-001"))
        assertTrue(EdgeRuntimeConfig.isPlaceholderDeviceId("Cab-001"))
    }

    @Test
    fun `现场编号与自动生成的 EDGE- 编号不算占位`() {
        assertFalse(EdgeRuntimeConfig.isPlaceholderDeviceId("CAB-002"))
        assertFalse(EdgeRuntimeConfig.isPlaceholderDeviceId("EDGE-1A2B3C4D5E6F"))
        assertFalse(EdgeRuntimeConfig.isPlaceholderDeviceId("0"))
    }

    @Test
    fun `带空白的 CAB-001 不算占位（trim 责任在调用方）`() {
        assertFalse(EdgeRuntimeConfig.isPlaceholderDeviceId(" CAB-001 "))
        assertFalse(EdgeRuntimeConfig.isPlaceholderDeviceId("CAB-001 "))
    }
}
