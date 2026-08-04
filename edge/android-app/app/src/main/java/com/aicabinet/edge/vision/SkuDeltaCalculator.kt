package com.aicabinet.edge.vision

import android.util.Log

/**
 * SKU Delta 差异计算器。
 * 对比开门首帧与关门末帧的检测结果，计算被取走的商品。
 * 策略：检测到减少 → 正向 delta；检测到增加 → 用户放回 (delta 为负)。
 */
object SkuDeltaCalculator {

    private const val TAG = "SkuDeltaCalculator"

    data class DeltaItem(
        val skuLabel: String,
        val delta: Int,              // 正=取走, 负=放回
        val avgConfidence: Float,    // 平均置信度
        val source: String = "EDGE_DELTA"
    )

    data class DeltaResult(
        val items: List<DeltaItem>,
        val totalRemoved: Int,
        val totalReturned: Int,
        val overallConfidence: Float,
        val needReview: Boolean
    )

    // 置信度阈值 — 低于此值进人工审核
    private const val MIN_CONFIDENCE = 0.50f
    private const val REVIEW_THRESHOLD = 0.35f

    /**
     * 计算两个时间点的检测差异。
     * @param beforeDetections 开门时（货柜满置）
     * @param afterDetections  关门后（用户已取走商品）
     * @return 差异结果
     */
    fun computeDelta(
        beforeDetections: List<NcnnYoloDetector.Detection>,
        afterDetections: List<NcnnYoloDetector.Detection>
    ): DeltaResult {
        if (beforeDetections.isEmpty() && afterDetections.isEmpty()) {
            return DeltaResult(emptyList(), 0, 0, 1.0f, needReview = false)
        }

        val beforeCounts = aggregateByLabel(beforeDetections)
        val afterCounts = aggregateByLabel(afterDetections)

        val items = mutableListOf<DeltaItem>()
        var totalRemoved = 0
        var totalReturned = 0

        // 对比所有商品变化
        val allLabels = (beforeCounts.keys + afterCounts.keys).toSet()
        for (label in allLabels) {
            val before = beforeCounts[label] ?: 0
            val after = afterCounts[label] ?: 0
            val delta = before - after

            if (delta == 0) continue

            val avgConf = calcAvgConfidence(label, beforeDetections, afterDetections)
            val item = DeltaItem(label, delta, avgConf)
            items.add(item)

            if (delta > 0) totalRemoved += delta
            else totalReturned += (-delta)

            Log.d(TAG, "delta $label: $before → $after = $delta (conf=$avgConf)")
        }

        // 未识别到任何变化 → 零结算
        if (items.isEmpty()) {
            return DeltaResult(emptyList(), 0, 0, 1.0f, needReview = false)
        }

        val overallConf = items.map { it.avgConfidence }.average().toFloat()
        val needReview = items.any { it.avgConfidence < MIN_CONFIDENCE }
                || overallConf < REVIEW_THRESHOLD

        return DeltaResult(items, totalRemoved, totalReturned, overallConf, needReview)
    }

    private fun aggregateByLabel(detections: List<NcnnYoloDetector.Detection>): Map<String, Int> {
        return detections.groupBy { it.label }.mapValues { it.value.size }
    }

    private fun calcAvgConfidence(
        label: String,
        before: List<NcnnYoloDetector.Detection>,
        after: List<NcnnYoloDetector.Detection>
    ): Float {
        val all = (before + after).filter { it.label == label }
        if (all.isEmpty()) return 0f
        return all.map { it.confidence }.average().toFloat()
    }
}
