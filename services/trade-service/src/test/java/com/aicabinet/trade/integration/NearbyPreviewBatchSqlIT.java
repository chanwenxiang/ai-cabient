package com.aicabinet.trade.integration;

import com.aicabinet.common.dto.DeviceProductDto;
import com.aicabinet.common.dto.NearbyDeviceDto;
import com.aicabinet.trade.service.DeviceCatalogService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `DeviceCatalogService.previewByDevices`（附近柜批量预览）的**等价性**真库回归。
 *
 * <p>它替掉的是「逐台 {@code listProducts(deviceId)} 再取前 3」，而可售量的取数口径有四道闸：
 * 只算在售批次、只算 `quantity>0`、**排除已禁用货道**、没绑货道的批次按可用兜底；价格还叠了
 * 设备覆盖价与清仓折扣。这些全是 SQL 谓词 ⇒ mock 单测只能证明「我调了哪个方法」，
 * 证不了「批量算出来 == 逐台算出来」。
 *
 * <p>判据：逐台断言 itemCount / skuCount / 前 N 项的 (skuId, name, qty, priceCents) 与
 * 由 {@code listProducts} 现算的结果**逐字段相等**；另加三条显式期望（禁用货道不计、无货道计入、
 * 覆盖价生效），防止两条路径一起错还互相印证。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class NearbyPreviewBatchSqlIT {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test").withUsername("test").withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    private static final String A = "DEV-PRV-LOTS";     // 有批次账本：含一条禁用货道、一条无货道、一条覆盖价
    private static final String B = "DEV-PRV-LEGACY";   // 无批次账本：走聚合行回退
    private static final String C = "DEV-PRV-EMPTY";    // 什么都没有

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeviceCatalogService catalog;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM device_sku_lot WHERE device_id LIKE 'DEV-PRV-%'");
        jdbc.update("DELETE FROM device_slot WHERE device_id LIKE 'DEV-PRV-%'");
        jdbc.update("DELETE FROM device_sku_inventory WHERE device_id LIKE 'DEV-PRV-%'");
        jdbc.update("DELETE FROM device_sku_price WHERE device_id LIKE 'DEV-PRV-%'");
        jdbc.update("DELETE FROM sku_catalog WHERE sku_id LIKE 'SKU-PRV-%'");
        jdbc.update("DELETE FROM device_info WHERE device_id LIKE 'DEV-PRV-%'");

        for (String d : List.of(A, B, C)) {
            jdbc.update("INSERT INTO device_info (device_id, device_name, device_type, online_status) "
                    + "VALUES (?, ?, 'AI_CABINET_V1', 'ONLINE')", d, "预览批量 IT 柜 " + d);
        }
        sku("SKU-PRV-A1", "Alpha", 500);
        sku("SKU-PRV-A2", "Bravo", 300);   // 绑禁用货道 ⇒ 消费者不可见
        sku("SKU-PRV-A3", "Charlie", 400); // 不绑货道 ⇒ 兜底可用
        sku("SKU-PRV-B1", "Delta", 200);

        slot(A, "S1", true);
        slot(A, "S2", false);
        lot(A, "SKU-PRV-A1", "S1", 5);
        lot(A, "SKU-PRV-A2", "S2", 2);
        lot(A, "SKU-PRV-A3", null, 3);
        jdbc.update("INSERT INTO device_sku_price (device_id, sku_id, price_cents, enabled) VALUES (?, 'SKU-PRV-A1', 450, true)", A);
        jdbc.update("INSERT INTO device_sku_inventory (device_id, sku_id, quantity) VALUES (?, 'SKU-PRV-B1', 4)", B);
    }

    private void sku(String id, String name, int price) {
        jdbc.update("INSERT INTO sku_catalog (sku_id, sku_name, price_cents, status) VALUES (?, ?, ?, 'ACTIVE')", id, name, price);
    }

    private void slot(String device, String code, boolean enabled) {
        jdbc.update("INSERT INTO device_slot (device_id, slot_code, enabled) VALUES (?, ?, ?)", device, code, enabled);
    }

    private void lot(String device, String skuId, String slotId, int qty) {
        jdbc.update("INSERT INTO device_sku_lot (lot_id, device_id, sku_id, batch_no, expiry_date, quantity, slot_id, status) "
                        + "VALUES (?, ?, ?, ?, DATE '2099-12-31', ?, ?, 'ON_SALE')",
                device + "|" + skuId, device, skuId, "B-" + skuId, qty, slotId);
    }

    @Test
    @DisplayName("批量预览 == 逐台 listProducts 现算预览（itemCount/skuCount/前 N 项逐字段）")
    void batchPreviewMatchesPerDevice() {
        Map<String, DeviceCatalogService.DevicePreview> batch = catalog.previewByDevices(List.of(A, B, C), null, 3);

        for (String d : List.of(A, B, C)) {
            List<DeviceProductDto> products = catalog.listProducts(d, null);
            DeviceCatalogService.DevicePreview p = batch.get(d);
            int itemCount = products.stream().mapToInt(x -> Math.max(0, x.quantity())).sum();
            List<NearbyDeviceDto.PreviewSku> expect = products.stream()
                    .sorted((x, y) -> Integer.compare(y.quantity(), x.quantity()))
                    .limit(3)
                    .map(x -> new NearbyDeviceDto.PreviewSku(x.skuId(), x.skuName(), x.quantity(), x.priceCents()))
                    .toList();
            assertEquals(itemCount, p.itemCount(), "itemCount 不一致 device=" + d);
            assertEquals(products.size(), p.skuCount(), "skuCount 不一致 device=" + d);
            assertEquals(
                    expect.stream().map(x -> x.skuId() + ":" + x.skuName() + ":" + x.quantity() + ":" + x.unitPriceCents()).toList(),
                    p.preview().stream().map(x -> x.skuId() + ":" + x.skuName() + ":" + x.quantity() + ":" + x.unitPriceCents()).toList(),
                    "预览明细不一致 device=" + d + " ⇒ 附近页显示的件数/单价会与详情页不同");
        }
    }

    @Test
    @DisplayName("显式口径：禁用货道不计、无货道兜底计入、设备覆盖价生效、空柜为 0")
    void explicitSemantics() {
        Map<String, DeviceCatalogService.DevicePreview> batch = catalog.previewByDevices(List.of(A, B, C), null, 3);
        DeviceCatalogService.DevicePreview a = batch.get(A);
        assertEquals(8, a.itemCount(), "5（S1 启用）+3（无货道兜底）；S2 已禁用必须不计");
        assertEquals(2, a.skuCount(), "禁用货道上的 SKU 不应出现");
        assertTrue(a.preview().stream().noneMatch(x -> "SKU-PRV-A2".equals(x.skuId())), "禁用货道的 SKU 不得进预览");
        assertEquals(450, a.preview().stream().filter(x -> "SKU-PRV-A1".equals(x.skuId()))
                .findFirst().orElseThrow().unitPriceCents(), "设备覆盖价 450 必须生效（不是目录价 500）");
        assertEquals(4, batch.get(B).itemCount(), "无批次账本回退聚合行");
        assertEquals(0, batch.get(C).itemCount(), "空柜为 0");
        assertTrue(batch.get(C).preview().isEmpty());
    }
}
