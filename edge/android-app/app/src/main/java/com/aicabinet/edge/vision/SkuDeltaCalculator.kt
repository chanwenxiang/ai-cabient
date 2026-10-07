package com.aicabinet.edge.vision

/**
 * 开门帧 / 关门帧的**检出差分**：关门相对开门增加的件数视为购入。
 *
 * <p><b>为什么从 `NcnnYoloDetector` 里独立出来</b>（2026-10-07）：
 * 原实现把入参写成 {@code NcnnYoloDetector.Detection}，于是「差分算增减」这个
 * **与推理实现无关的纯逻辑**被绑死在 YOLO 上 —— 想换识别来源（用户已定：改用
 * **将邑**端侧识别）就必须连它一起删，而它其实是**要被保留**的能力。
 *
 * <p>现在入参是本文件自己的 {@link Detection}（最小结构：标签 + 置信度），
 * 任何识别来源（YOLO / 将邑 / 其他厂商 SDK）只要能给出「这帧有哪些 SKU、
 * 各自多大把握」，就能复用同一份差分逻辑。
 *
 * <p>⚠️ <b>边界：厂商可能只给最终 SKU 列表，不给「开门帧/关门帧」两帧</b>。
 * 那种形态下本类的两帧差分用不上，需要厂商侧直接给增量，或由我们改用
 * 「开门识别结果 + 结算时结果」做差 —— **采购时必须问清这一点**。
 *
 * <p>🔴 <b>置信度语义</b>：{@code confidence = null} 表示识别侧**未提供**，
 * 不是 0。0 与 1 都是合法置信度，用 0 冒充「缺失」会把「没测到」误判成
 * 「很不确定」，两者对运营的含义完全不同。
 */
object SkuDeltaCalculator {

    /** 置信度低于此值 ⇒ 该行标记 {@code needReview}。 */
    const val LOW_CONFIDENCE_THRESHOLD = 0.5f

    /**
     * 单帧的一个检出项。
     *
     * @param skuLabel SKU 标签（识别侧给什么就是什么，不做归一化）
     * @param confidence 置信度；**null 表示识别侧未提供**，不是 0
     */
    data class Detection(
        val skuLabel: String,
        val confidence: Float?,
    ) {
        /** 置信度未知时按 0 参与平均（保守：更容易触发人工复核）。 */
        internal val effectiveConfidence: Float
            get() = confidence ?: 0f
    }

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

    /**
     * 算「关门帧 − 开门帧」的件数差。
     *
     * <p>🔴 两帧中<b>任一</b>检出项置信度缺失 ⇒ 整个结果 {@code needReview = true}：
     * 置信度缺失时算出的差值不可信，**不能让它静默走自动结算**。
     */
    fun diff(
        openDetections: List<Detection>,
        closeDetections: List<Detection>,
    ): DeltaResult {
        val openCounts = countBySku(openDetections)
        val closeCounts = countBySku(closeDetections)
        val all = openDetections + closeDetections
        val confBySku = all
            .filter { it.confidence != null }
            .groupBy { it.skuLabel }
            .mapValues { (_, list) -> list.map { it.effectiveConfidence }.average().toFloat() }
        val missingConfidence = all.any { it.confidence == null }

        val skus = (openCounts.keys + closeCounts.keys)
        val items = skus.map { sku ->
            val d = (closeCounts[sku] ?: 0) - (openCounts[sku] ?: 0)
            // 置信度整体缺失时用 0f 参与展示（并由 needReview 强制接管）
            val conf = confBySku[sku] ?: 0f
            DeltaItem(
                skuLabel = sku,
                delta = d,
                avgConfidence = conf,
                needReview = conf < LOW_CONFIDENCE_THRESHOLD,
            )
        }.filter { it.delta != 0 }
        return DeltaResult(
            items = items,
            // 空结果也要复核：全都没识别出来时「delta 全为 0」不等于「没买东西」
            needReview = missingConfidence || items.any { it.needReview } || items.isEmpty(),
        )
    }

    private fun countBySku(detections: List<Detection>): Map<String, Int> =
        detections.groupingBy { it.skuLabel }.eachCount()
}