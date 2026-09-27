package com.aicabinet.trade.support;

import com.aicabinet.trade.service.DemoDataService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * IT/E2E 演示柜与 SKU 解析：禁止写死 {@code CAB-001}（V287 已删除该孤儿柜）。
 *
 * <p>与 lessons #212 / #216 一致：柜号由 {@link DemoDataService#ensureDemoData()} 发号或选库内合格柜。
 */
public final class DemoFixture {

    private DemoFixture() {
    }

    public static DemoDataService.DemoContext requireDemo(DemoDataService demoDataService) {
        assertNotNull(demoDataService, "DemoDataService required");
        DemoDataService.DemoContext ctx = demoDataService.ensureDemoData();
        assertNotNull(ctx, "ensureDemoData returned null");
        assertFalse(ctx.deviceId() == null || ctx.deviceId().isBlank(),
                "demo deviceId blank — ensureDemoData failed to allocate/select a cabinet");
        return ctx;
    }

    public static String requireDeviceId(DemoDataService demoDataService) {
        return requireDemo(demoDataService).deviceId();
    }

    /** 柜上可售兜底 SKU；空则退回目录首个可视觉结算 SKU（仍可能无库存，调用方应断言非空）。 */
    public static String requireFallbackSkuId(DemoDataService demoDataService) {
        DemoDataService.DemoContext ctx = requireDemo(demoDataService);
        String sku = ctx.fallbackSkuId();
        assertFalse(sku == null || sku.isBlank(),
                "demo fallbackSku blank — no chargeable SKU on device " + ctx.deviceId());
        return sku;
    }
}
