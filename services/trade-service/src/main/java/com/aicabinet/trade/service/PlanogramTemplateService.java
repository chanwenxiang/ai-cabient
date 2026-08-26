package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertDeviceSlotRequest;

import java.util.List;

/**
 * 设备类型 → 默认货道陈列模板（planogram）。
 */
public final class PlanogramTemplateService {
    private static final String SKU_NOODLE_001 = "SKU-NOODLE-001";
    private static final String SKU_WATER_001 = "SKU-WATER-001";


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

    /** 8 货道标准柜（与 CAB-001 demo 一致）。 */
    public static List<UpsertDeviceSlotRequest> standardTemplate() {
        return List.of(
                slot("A1", 1, 1, "SKU-DEMO-001", 8, 2),
                slot("A2", 1, 2, "SKU-SODA-001", 8, 2),
                slot("A3", 1, 3, SKU_WATER_001, 6, 2),
                slot("A4", 1, 4, SKU_WATER_001, 6, 2),
                slot("B1", 2, 1, "SKU-SNACK-001", 8, 2),
                slot("B2", 2, 2, "SKU-MILK-001", 6, 2),
                slot("B3", 2, 3, SKU_NOODLE_001, 8, 2),
                slot("B4", 2, 4, SKU_NOODLE_001, 4, 1)
        );
    }

    /** 6 货道紧凑柜。 */
    public static List<UpsertDeviceSlotRequest> compactTemplate() {
        return List.of(
                slot("A1", 1, 1, "SKU-DEMO-001", 6, 2),
                slot("A2", 1, 2, "SKU-SODA-001", 6, 2),
                slot("A3", 1, 3, SKU_WATER_001, 5, 2),
                slot("B1", 2, 1, "SKU-SNACK-001", 6, 2),
                slot("B2", 2, 2, "SKU-MILK-001", 5, 2),
                slot("B3", 2, 3, SKU_NOODLE_001, 6, 2)
        );
    }

    private static UpsertDeviceSlotRequest slot(String code, int row, int col, String sku, int par, int min) {
        return new UpsertDeviceSlotRequest(code, row, col, "SHELF", sku, par, min, par, true);
    }
}
