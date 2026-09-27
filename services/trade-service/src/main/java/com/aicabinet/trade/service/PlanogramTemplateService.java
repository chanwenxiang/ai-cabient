package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertDeviceSlotRequest;

import java.util.List;

/**
 * 设备类型 → 默认货道陈列模板（planogram）。
 *
 * <p>仅定义货道几何与容量；<strong>不</strong>预填演示 SKU（由运营/补货/Demo 库存另行绑定）。
 */
public final class PlanogramTemplateService {

    public static final String DEFAULT_DEVICE_TYPE = "AI_CABINET_V1";
    public static final String COMPACT_DEVICE_TYPE = "AI_CABINET_COMPACT";

    private PlanogramTemplateService() {
    }

    public static List<UpsertDeviceSlotRequest> templateFor(String deviceType) {
        String type = deviceType == null || deviceType.isBlank()
                ? DEFAULT_DEVICE_TYPE
                : deviceType.trim().toUpperCase();
        if (COMPACT_DEVICE_TYPE.equals(type)) {
            return compactTemplate();
        }
        return standardTemplate();
    }

    /** 8 货道标准柜：空陈列，只建货道骨架。 */
    public static List<UpsertDeviceSlotRequest> standardTemplate() {
        return List.of(
                slot("A1", 1, 1, 8, 2),
                slot("A2", 1, 2, 8, 2),
                slot("A3", 1, 3, 6, 2),
                slot("A4", 1, 4, 6, 2),
                slot("B1", 2, 1, 8, 2),
                slot("B2", 2, 2, 6, 2),
                slot("B3", 2, 3, 8, 2),
                slot("B4", 2, 4, 4, 1)
        );
    }

    /** 6 货道紧凑柜。 */
    public static List<UpsertDeviceSlotRequest> compactTemplate() {
        return List.of(
                slot("A1", 1, 1, 6, 2),
                slot("A2", 1, 2, 6, 2),
                slot("A3", 1, 3, 5, 2),
                slot("B1", 2, 1, 6, 2),
                slot("B2", 2, 2, 5, 2),
                slot("B3", 2, 3, 6, 2)
        );
    }

    private static UpsertDeviceSlotRequest slot(String code, int row, int col, int par, int min) {
        return new UpsertDeviceSlotRequest(code, row, col, "SHELF", null, par, min, par, true);
    }
}
