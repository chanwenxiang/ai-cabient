package com.aicabinet.edge.vision

/**
 * open/close 帧检测差：关门相对开门增加的件数视为购入。
 */
object SkuDeltaCalculator {
    data class DeltaItem(
        val skuLabel: String,
        val delta: Int,
        val avgConfidence: Float,
        val needReview: Boolean,
    )

    data class DeltaResult(
        val items: List<DeltaItem>,
        val needReview: Boolean,
    )

    fun diff(
        openDetections: List<NcnnYoloDetector.Detection>,
        closeDetections: List<NcnnYoloDetector.Detection>,
    ): DeltaResult {
        val openCounts = countBySku(openDetections)
        val closeCounts = countBySku(closeDetections)
        val confBySku = (openDetections + closeDetections)
            .groupBy { it.skuLabel }
            .mapValues { (_, list) -> list.map { it.avgConfidence }.average().toFloat() }
        val skus = (openCounts.keys + closeCounts.keys)
        val items = skus.map { sku ->
            val d = (closeCounts[sku] ?: 0) - (openCounts[sku] ?: 0)
            val conf = confBySku[sku] ?: 0f
            DeltaItem(
                skuLabel = sku,
                delta = d,
                avgConfidence = conf,
                needReview = conf < 0.5f,
            )
        }.filter { it.delta != 0 }
        return DeltaResult(
            items = items,
            needReview = items.any { it.needReview } || items.isEmpty(),
        )
    }

    private fun countBySku(detections: List<NcnnYoloDetector.Detection>): Map<String, Int> =
        detections.groupingBy { it.skuLabel }.eachCount()
}
