package com.aicabinet.edge.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SkuDeltaCalculator`：开门帧 / 关门帧差分算增减。
 *
 * <p>本类现在是 `vision/` 下**唯一**的实现类（2026-10-07 删掉了零调用方的
 * NcnnYoloDetector / EdgeVisionEngine / EdgeRecognitionCache / EdgeVisionConfig /
 * FrameCaptureManager 共 231 行死代码后），并被 `scripts/check-edge-vision-wired.mjs`
 * 标为 `pending`（待将邑对接的纯逻辑）。该门禁要求 pending 类**必须有测试引用**，
 * 否则「待对接」会退化成「永远不接」的合法借口 —— 这就是本文件存在的理由。
 *
 * <p>⚠️ 本项目用 **JUnit 4**（`testImplementation("junit:junit:4.13.2")`），
 * 不是 JUnit 5 —— 写错会让整个测试源码编译失败，而失败信息是一片
 * `Unresolved reference: jupiter`，看不出是「版本选错」而非「代码写错」。
 * （照trade-service 的 JUnit5 习惯写就会踩这个坑。）
 *
 * <p>🔴 被钉住的三个行为（都是重构时容易悄悄改坏的）：
 *   1. `delta = 关门 − 开门`，负数（拿走的）也要出现 —— 只出正数会让「退货」消失；
 *   2. **置信度缺失（null）⇒ 强制 needReview** —— 0 是合法置信度，
 *      用 0 冒充「缺失」会把「没测到」误判成「很不确定」；
 *   3. 空结果也要 needReview ——「全都没识别出来」不等于「没买东西」。
 */
class SkuDeltaCalculatorTest {

    private fun det(sku: String, conf: Float?) = SkuDeltaCalculator.Detection(sku, conf)

    @Test
    fun `开门1件关门3件则 delta 为正2`() {
        val r = SkuDeltaCalculator.diff(
            listOf(det("SKU-A", 0.9f)),
            listOf(det("SKU-A", 0.9f), det("SKU-A", 0.9f), det("SKU-A", 0.9f))
        )
        // 3 - 1 = 2
        assertEquals(2, r.items.single().delta)
    }

    @Test
    fun `开门3件关门1件则 delta 为负2且不被过滤`() {
        val r = SkuDeltaCalculator.diff(
            listOf(det("SKU-A", 0.9f), det("SKU-A", 0.9f), det("SKU-A", 0.9f)),
            listOf(det("SKU-A", 0.9f))
        )
        assertEquals(1, r.items.size)
        assertEquals(-2, r.items.single().delta)
    }

    @Test
    fun `两帧相同则 items 为空但仍需人工复核`() {
        val r = SkuDeltaCalculator.diff(
            listOf(det("SKU-A", 0.9f)),
            listOf(det("SKU-A", 0.9f))
        )
        assertTrue(r.items.isEmpty())
        // 空结果也必须复核：全都没识别出来 ≠ 没买东西
        assertTrue(r.needReview)
    }

    @Test
    fun `A增加B减少各自独立成行`() {
        val r = SkuDeltaCalculator.diff(
            listOf(det("SKU-A", 0.9f), det("SKU-B", 0.9f), det("SKU-B", 0.9f)),
            listOf(det("SKU-A", 0.9f), det("SKU-A", 0.9f), det("SKU-B", 0.9f))
        )
        val bySku = r.items.associateBy { it.skuLabel }
        assertEquals(2, r.items.size)
        assertEquals(1, bySku["SKU-A"]!!.delta)
        assertEquals(-1, bySku["SKU-B"]!!.delta)
    }

    @Test
    fun `只出现在关门帧的新SKU 计为正增量`() {
        val r = SkuDeltaCalculator.diff(
            emptyList(),
            listOf(det("SKU-NEW", 0.95f))
        )
        assertEquals("SKU-NEW", r.items.single().skuLabel)
        assertEquals(1, r.items.single().delta)
    }

    @Test
    fun `高置信度不需复核`() {
        val r = SkuDeltaCalculator.diff(emptyList(), listOf(det("SKU-A", 0.92f)))
        assertFalse(r.needReview)
    }

    @Test
    fun `低置信度需复核`() {
        val r = SkuDeltaCalculator.diff(emptyList(), listOf(det("SKU-A", 0.3f)))
        assertTrue(r.needReview)
        assertTrue(r.items.single().needReview)
    }

    @Test
    fun `置信度为null时强制复核即使其他项都很确定`() {
        // 🔴 本类最容易被重构改坏的语义：
        //    null =「识别侧没给」，不是 0。若按 0 参与判断，
        //    置信度缺失会被当成「很不确定」而静默走人工；
        //    更糟的是有人可能用 0 冒充 null，把「没测到」藏起来。
        val r = SkuDeltaCalculator.diff(
            listOf(det("SKU-A", null)),
            listOf(det("SKU-A", 0.99f), det("SKU-A", 0.99f))
        )
        assertTrue(r.needReview)
    }

    @Test
    fun `零置信度是合法值而非缺失两者行为须可区分`() {
        val zero = SkuDeltaCalculator.diff(emptyList(), listOf(det("SKU-Z", 0f)))
        val missing = SkuDeltaCalculator.diff(emptyList(), listOf(det("SKU-Z", null)))
        // 两者 needReview 都是 true，但语义不同：
        // 0f 是「测了，非常不确定」；null 是「没测到」
        assertEquals(0f, zero.items.single().avgConfidence)
        assertEquals(0f, missing.items.single().avgConfidence)
        assertTrue(zero.needReview && missing.needReview)
    }

    @Test
    fun `阈值恰为05时不算低置信度`() {
        assertEquals(0.5f, SkuDeltaCalculator.LOW_CONFIDENCE_THRESHOLD)
        val r = SkuDeltaCalculator.diff(
            emptyList(),
            listOf(det("SKU-A", SkuDeltaCalculator.LOW_CONFIDENCE_THRESHOLD))
        )
        assertFalse(r.needReview)
    }
}