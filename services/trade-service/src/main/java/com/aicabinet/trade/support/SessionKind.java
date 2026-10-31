package com.aicabinet.trade.support;

import java.util.Set;

/**
 * 开门会话「类型」的唯一字面量源（MA-007，2026-10-31）。
 *
 * <p>同一批前缀既被 {@code DeviceValidationService} 的 Java 判定使用，也被
 * {@code ShoppingSessionMapper} 的运营列表筛选条件使用——两侧必须同源（铁律 16），
 * 否则「列表筛出来的类型」与「行上显示的类型」会分叉。
 */
public final class SessionKind {

    /** 补货开门会话的幂等键前缀。 */
    public static final String RESTOCK_KEY_PREFIX = "RESTOCK:";
    /** 运维远程开门会话的幂等键前缀。 */
    public static final String OPS_REMOTE_KEY_PREFIX = "OPS_REMOTE:";
    /** 商户盘点开门会话的幂等键前缀。 */
    public static final String STOCKTAKE_KEY_PREFIX = "STOCKTAKE:";

    public static final String CONSUMER = "CONSUMER";
    public static final String RESTOCK = "RESTOCK";
    public static final String OPS = "OPS";
    public static final String STOCKTAKE = "STOCKTAKE";

    public static final Set<String> ALLOWED = Set.of(CONSUMER, RESTOCK, OPS, STOCKTAKE);

    private SessionKind() {}

    /** 空值放行（不筛）；未知值返回 null 交由调用方拒绝，避免静默忽略筛选。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String upper = raw.trim().toUpperCase();
        return ALLOWED.contains(upper) ? upper : null;
    }
}
