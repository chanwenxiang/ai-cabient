package com.aicabinet.trade.support;

import com.aicabinet.trade.domain.DeviceSkuInventory;

import java.util.Map;
import java.util.Set;

/**
 * 开门柜「柜×SKU」库存判定唯一出处（CB-076，2026-10-30）。
 *
 * <p>两档互斥：{@link #OOS} 可售 ≤ 0（Amazon「缺货=可售为零」）；{@link #LOW}
 * 0 &lt; 可售 ≤ 低库存阈值（Amazon「低库存=降至最低库存水平以下」）。边界取严用 {@code <=}，
 * 与补货建议 {@code WarehouseService.suggestFromLowStock} 同源——否则会出现
 * 「界面说正常、备货建议里又有它」（CB-076 结论②）。</p>
 *
 * <p>数量源同样只此一处：该 SKU 建过批次账本 ⇒ 取批次合计（卖光/全过期=0）；
 * 从未建账 ⇒ 回落台账 quantity。只判「柜是否启用账本」会把从未建账的 SKU 读成 0
 * （2026-10-26 雪碧实测：inventory=2、device_sku_lot 零行）。</p>
 */
public enum SkuStockLevel {
    /** 缺货：可售库存 ≤ 0。 */
    OOS,
    /** 低库存：0 &lt; 可售库存 ≤ 低库存阈值。 */
    LOW,
    /** 正常。 */
    OK;

    public static SkuStockLevel of(int sellableQty, int lowThreshold) {
        if (sellableQty <= 0) {
            return OOS;
        }
        return sellableQty <= Math.max(lowThreshold, 0) ? LOW : OK;
    }

    /** 是否需要在运营/商户界面上提示（缺货或低库存）。 */
    public boolean warning() {
        return this != OK;
    }

    /**
     * 柜×SKU 可售量：建过批次账本的 SKU 取批次合计，其余回落台账 quantity。
     *
     * @param lotLedgerSkus 该柜进过批次账本的 SKU（{@code InventoryLotService.lotLedgerSkuIds}）
     * @param sellableBySku 该柜批次合计（{@code InventoryLotService.sellableQtyBySku}），可为 null
     */
    public static int sellableQty(DeviceSkuInventory inv, Set<String> lotLedgerSkus,
                                  Map<String, Integer> sellableBySku) {
        String skuId = inv.getId().getSkuId();
        if (lotLedgerSkus == null || !lotLedgerSkus.contains(skuId)) {
            return inv.getQuantity();
        }
        return sellableBySku == null ? 0 : sellableBySku.getOrDefault(skuId, 0);
    }
}
