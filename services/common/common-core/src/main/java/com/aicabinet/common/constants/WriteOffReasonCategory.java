package com.aicabinet.common.constants;

/**
 * V311：货损/报损原因分类（受控枚举）。
 *
 * <p>🔴 <b>为什么需要它</b>：{@code InventoryWriteOff.reason} 是自由文本
 * （历史字段，UI 传 {@code 'EXPIRED'} 这类值），无法统计。
 * 责任判定与供应商对账都需要「过期 / 破损 / 丢失」这类**可聚合的分类**。
 *
 * <p>⚠️ <b>刻意不加 DB CHECK 约束</b>：生产库的 {@code reason} 尚未归一化，
 * 加了约束会让历史数据写入失败。待归一化后另一次迁移再补。
 * 本枚举是「应用侧的契约」，不是「DB 层的强约束」。
 *
 * <p>📌 <b>null 的语义</b>：{@code null} = <b>未归类</b>，
 * <b>不等于</b> {@link #OTHER}。两者在统计上必须分开：
 * 「没填分类」是要治理的问题，「填了其他」是运营的明确选择。
 */
public final class WriteOffReasonCategory {

    /** 商品过期/临期报废（UI 现用值 {@code EXPIRED}）。 */
    public static final String EXPIRED = "EXPIRED";
    /** 外力损坏（挤压、跌落）。 */
    public static final String DAMAGED = "DAMAGED";
    /** 运输丢失。 */
    public static final String LOST = "LOST";
    /** 错发/ 少发（供方责任）。 */
    public static final String SHORT_SUPPLIED = "SHORT_SUPPLIED";
    /** 盘亏（账实不符，原因待查）。 */
    public static final String SHRINKAGE = "SHRINKAGE";
    /** 样品/试吃等主动损耗。 */
    public static final String SAMPLING = "SAMPLING";
    /** 运营明确选择「其他」。 */
    public static final String OTHER = "OTHER";

    private WriteOffReasonCategory() {
    }

    /**
     * 从自由文本 {@code reason} 推断分类。
     *
     * <p>🔴 <b>只映射高置信值，推断不出就返回 null</b> —— <b>不猜</b>。
     * 错误分类会污染责任判定（把「破损」误判成「过期」会导致找错责任方），
     * 比「没有分类」危险得多。
     *
     * <p>识别方式：大小写无关的精确匹配 + 包含匹配（中文按包含）。
     * 精确匹配优先 —— 「过期」和「不过期」不能混为一谈。
     *
     * @return 分类；无法确定返回 {@code null}
     */
    public static String infer(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String r = reason.trim();
        String upper = r.toUpperCase();

        // 🔴 否定词优先：「不过期」「未过期」「没损坏」等描述的是**没有**损耗，
        //    却被包含匹配命中「过期」/「损坏」⇒ 被误判成报损原因
        //    ⇒ 找错责任方。这一段必须跑在所有正向匹配之前。
        //    注：报损原因里出现否定词本身就是输入错误（都报损了还说「不过期」），
        //    但**不能靠猜**——返回 null 让运营自己填分类，比静默误判好。
        if (containsNegation(r, upper)) {
            return null;
        }

        // 精确匹配优先（避免「不过期」被包含匹配误判为「过期」）
        if ("EXPIRED".equals(upper) || "过期".equals(r) || "临期".equals(r)) {
            return EXPIRED;
        }
        if ("DAMAGED".equals(upper) || "破损".equals(r) || "损坏".equals(r)) {
            return DAMAGED;
        }
        // 🔴 THEFT 是**现有白名单里的值**（InventoryOpsService.WRITE_OFF_REASONS），
        //    但与 LOST 语义相近（都是「货少了」）。漏了它会导致既有报损记录分类全空。
        if ("LOST".equals(upper) || "THEFT".equals(upper) || "丢失".equals(r)) {
            return LOST;
        }
        if ("SHORT_SUPPLIED".equals(upper) || "少发".equals(r) || "错发".equals(r)) {
            return SHORT_SUPPLIED;
        }
        if ("SHRINKAGE".equals(upper) || "盘亏".equals(r)) {
            return SHRINKAGE;
        }
        if ("SAMPLING".equals(upper) || "样品".equals(r) || "试吃".equals(r)) {
            return SAMPLING;
        }

        // 包含匹配（放在精确匹配之后，避免误判）
        if (r.contains("过期") || r.contains("临期")) {
            return EXPIRED;
        }
        if (r.contains("破损") || r.contains("损坏")) {
            return DAMAGED;
        }
        if (r.contains("丢失") || r.contains("遗失") || r.contains("被盗")) {
            return LOST;
        }
        if (r.contains("盘亏")) {
            return SHRINKAGE;
        }
        // 🔴 走到这里不返回 OTHER —— 「可能属于其他」和「确实是其他」是两件事。
        return null;
    }

    /**
     * 是否含否定词（描述「没有发生」而非「发生了」）。
     *
     * <p>🔴 只认<b>紧贴在损耗词前面的否定词</b>，不做泛化匹配：
     *「确认过期」里的「确认」不是否定、「不过期」里的「不」才是。
     * 泛化成「包含『不』就否定」会把「不错」「不良」等正常描述也打掉。
     */
    private static boolean containsNegation(String trimmed, String upper) {
        // 中文否定词：不 / 未 / 没 / 无
        String[] zh = {"不过期", "未过期", "没过期", "无破损", "未破损", "没损坏", "未损坏"};
        for (String n : zh) {
            if (trimmed.contains(n)) {
                return true;
            }
        }
        // 英文否定词：NOT_ / NO_ 前缀，以及 _NOT 后缀
        return upper.startsWith("NOT_") || upper.startsWith("NO_") || upper.endsWith("_NOT");
    }
}