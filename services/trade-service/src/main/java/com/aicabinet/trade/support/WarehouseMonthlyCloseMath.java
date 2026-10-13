package com.aicabinet.trade.support;

import com.aicabinet.trade.domain.WarehouseMovement;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分仓月结件数汇总（纯计算，不含金额）。
 * 应有 = 上期 + 采购入库 + 调入 − 退货 − 调出 − 上柜；损耗单独列，不计入应有（盘亏=差异台账化口径）。
 *
 * <p>🔴 V348 账期制返工的流水分流（口径与 lossQty 同构：核销/客情单独列展示，expected 保持「损耗前应有」）：
 * <ul>
 *   <li>{@code BIN_STOCK + refType=WRITE_OFF}（仓库报损核销）→ {@code writeOffQty}，
 *       不再混入 transferOut（旧实现把核销当「外调」，18 列口径错）；Service 层再按
 *       {@code inventory_write_off.reasonCategory} 细分过期/摇机列，余量兜底进 lossQty</li>
 *   <li>{@code OUTBOUND_SHIP/CANCEL + refType=GUEST_WATER}（客情出库，预留：补货目的链路待返工）
 *       → {@code guestWaterQty}，不混入 restockQty</li>
 * </ul>
 */
public final class WarehouseMonthlyCloseMath {

    /** 仓库报损核销流水标记（InventoryOpsService.doWarehouseWriteOff 经 binStockChange 写入）。 */
    public static final String REF_WRITE_OFF = "WRITE_OFF";
    /** 客情用水出库流水标记（预留：出库单 purpose=GUEST_WATER 时发运写入）。 */
    public static final String REF_GUEST_WATER = "GUEST_WATER";

    private WarehouseMonthlyCloseMath() {}

    public static final class Acc {
        public int openingQty;
        public int purchaseInQty;
        public int transferInQty;
        public int transferOutQty;
        public int restockQty;
        public int returnQty;
        public int lossQty;
        /** 仓库报损核销量（V348，从 transferOut 分流；不进 expected）。 */
        public int writeOffQty;
        /** 客情出库量（V348，从 restockQty 分流；不进 expected）。 */
        public int guestWaterQty;

        public int expectedQty() {
            return openingQty + purchaseInQty + transferInQty - returnQty - transferOutQty - restockQty;
        }
    }

    public static Map<String, Acc> aggregate(Collection<WarehouseMovement> movements, Instant start, Instant end) {
        Map<String, Acc> bySku = new LinkedHashMap<>();
        if (movements == null) {
            return bySku;
        }
        for (WarehouseMovement row : movements) {
            if (row == null || row.getSkuId() == null || row.getSkuId().isBlank() || row.getCreatedAt() == null) {
                continue;
            }
            Instant at = row.getCreatedAt();
            boolean opening = at.isBefore(start);
            boolean inMonth = !opening && at.isBefore(end);
            if (!opening && !inMonth) {
                continue;
            }
            Acc acc = bySku.computeIfAbsent(row.getSkuId().trim(), k -> new Acc());
            int delta = row.getDeltaQty();
            if (opening) {
                acc.openingQty += delta;
            } else {
                applyInMonth(acc, row.getMovementType(), row.getRefType(), delta);
            }
        }
        // 审计批次4：restockQty 钳制移到**全部 movement 应用完之后**——原来在 applyInMonth
        // 逐条末尾钳制，月内「先取消后发运」（OUTBOUND_CANCEL 使 restockQty 暂时为负）会被
        // 过早钳回 0，后续发运再叠加 ⇒ 少算上柜量、应有量虚高。
        for (Acc acc : bySku.values()) {
            if (acc.restockQty < 0) {
                acc.restockQty = 0;
            }
        }
        return bySku;
    }

    static void applyInMonth(Acc acc, String movementType, String refType, int delta) {
        String type = movementType == null ? "" : movementType.trim().toUpperCase();
        String ref = refType == null ? "" : refType.trim().toUpperCase();
        switch (type) {
            case "PURCHASE_RECEIVE", "MANUAL_INBOUND" -> acc.purchaseInQty += Math.max(delta, 0);
            case "PURCHASE_RETURN" -> acc.returnQty += Math.max(-delta, 0);
            case "OUTBOUND_SHIP" -> {
                if (REF_GUEST_WATER.equals(ref)) {
                    acc.guestWaterQty += Math.max(-delta, 0);
                } else {
                    acc.restockQty += Math.max(-delta, 0);
                }
            }
            case "OUTBOUND_CANCEL" -> {
                if (REF_GUEST_WATER.equals(ref)) {
                    acc.guestWaterQty -= Math.max(delta, 0);
                } else {
                    acc.restockQty -= Math.max(delta, 0);
                }
            }
            case "STOCKTAKE" -> {
                if (delta < 0) {
                    acc.lossQty += -delta;
                }
            }
            case "BIN_STOCK" -> {
                if (REF_WRITE_OFF.equals(ref)) {
                    // 仓库报损核销：单独列，Service 层按 write_off.reasonCategory 细分四损耗列
                    acc.writeOffQty += Math.max(-delta, 0);
                } else if ("TRANSFER_IN".equals(ref)) {
                    acc.transferInQty += Math.max(delta, 0);
                } else if ("TRANSFER_OUT".equals(ref)) {
                    acc.transferOutQty += Math.max(-delta, 0);
                } else if (delta > 0) {
                    acc.purchaseInQty += delta;
                } else {
                    acc.transferOutQty += -delta;
                }
            }
            default -> {
                if (delta > 0) {
                    acc.purchaseInQty += delta;
                } else if (delta < 0) {
                    acc.transferOutQty += -delta;
                }
            }
        }
    }
}
