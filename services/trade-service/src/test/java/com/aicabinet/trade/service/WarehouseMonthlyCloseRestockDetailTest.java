package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WarehouseMonthlyCloseRestockLineDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.WarehouseMovement;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.ExternalChannelFlowMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseLineMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseSheetMapper;
import com.aicabinet.trade.mapper.WarehouseMovementMapper;
import com.aicabinet.trade.mapper.WarehouseOutboundLineMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeLineMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeMapper;
import com.aicabinet.trade.support.WarehouseMonthlyCloseMath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 月结「上柜」下钻与列值同源（F4）：下钻按 OUTBOUND_SHIP 流水取数，逐 SKU 件数合计必须等于
 * {@link WarehouseMonthlyCloseMath.Acc#restockQty}。客情用水在列里是独立列，下钻必须一起剔除。
 */
class WarehouseMonthlyCloseRestockDetailTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant START = LocalDate.of(2026, 9, 26).atStartOfDay(SHANGHAI).toInstant();
    private static final Instant END = LocalDate.of(2026, 10, 26).atStartOfDay(SHANGHAI).toInstant();
    private static final String YEAR_MONTH = "2026-10";

    private WarehouseMovementMapper movementMapper;
    private WarehouseOutboundLineMapper outboundLineMapper;
    private DeviceInfoMapper deviceMapper;
    private SkuCatalogMapper skuCatalogMapper;
    private WarehouseMonthlyCloseService service;

    @BeforeEach
    void setUp() {
        movementMapper = mock(WarehouseMovementMapper.class);
        outboundLineMapper = mock(WarehouseOutboundLineMapper.class);
        deviceMapper = mock(DeviceInfoMapper.class);
        skuCatalogMapper = mock(SkuCatalogMapper.class);
        service = new WarehouseMonthlyCloseService(
                mock(WarehouseMapper.class), movementMapper,
                mock(WarehouseStocktakeMapper.class), mock(WarehouseStocktakeLineMapper.class),
                skuCatalogMapper, mock(WarehouseMonthlyCloseSheetMapper.class),
                mock(WarehouseMonthlyCloseLineMapper.class), mock(InventoryWriteOffMapper.class),
                mock(ExternalChannelFlowMapper.class), mock(UserInfoMapper.class),
                deviceMapper, outboundLineMapper);
    }

    @Test
    void detailQtyTiesToRestockColumnPerSku() {
        List<WarehouseMovement> all = List.of(
                in("SKU-A", "MANUAL_INBOUND", 80, START.plusSeconds(60)),
                ship("SKU-A", "B-1", -5, "11", START.plusSeconds(100)),
                ship("SKU-A", "B-1", -3, "11", START.plusSeconds(120)),
                ship("SKU-B", "B-2", -6, "12", START.plusSeconds(130))
        );
        stubShips(all);
        when(outboundLineMapper.selectList(any())).thenReturn(List.of(
                line(11L, "SKU-A", "B-1", "DEV-1"),
                line(12L, "SKU-B", "B-2", "DEV-1")));
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device("DEV-1", "演示智能柜")));
        when(skuCatalogMapper.selectBatchIds(any())).thenReturn(List.of(sku("SKU-A", "可乐 330ml")));

        List<WarehouseMonthlyCloseRestockLineDto> detail = service.restockDetail("WH-1", YEAR_MONTH);
        Map<String, WarehouseMonthlyCloseMath.Acc> acc =
                WarehouseMonthlyCloseMath.aggregate(all, START, END);

        assertEquals(8, sumOf(detail, "SKU-A"));
        assertEquals(acc.get("SKU-A").restockQty, sumOf(detail, "SKU-A"));
        assertEquals(acc.get("SKU-B").restockQty, sumOf(detail, "SKU-B"));
        // 同一出库单同 SKU 同批次并成一行，单号只记一次
        assertEquals(1, detail.stream().filter(r -> "SKU-A".equals(r.skuId())).count());
        assertEquals("11", detail.stream().filter(r -> "SKU-A".equals(r.skuId()))
                .findFirst().orElseThrow().outboundIds());
        assertEquals("演示智能柜", detail.get(0).deviceName());
    }

    @Test
    void guestWaterShipStaysOutOfBothSides() {
        WarehouseMovement normal = ship("SKU-A", "B-1", -4, "21", START.plusSeconds(100));
        WarehouseMovement guest = ship("SKU-A", "B-1", -2, "22", START.plusSeconds(110));
        guest.setRefType(WarehouseMonthlyCloseMath.REF_GUEST_WATER);
        List<WarehouseMovement> all = List.of(normal, guest);
        stubShips(all);
        when(outboundLineMapper.selectList(any())).thenReturn(List.of(
                line(21L, "SKU-A", "B-1", "DEV-1"),
                line(22L, "SKU-A", "B-1", "DEV-1")));
        when(deviceMapper.selectBatchIds(any())).thenReturn(List.of(device("DEV-1", "演示智能柜")));

        WarehouseMonthlyCloseMath.Acc acc = WarehouseMonthlyCloseMath.aggregate(all, START, END).get("SKU-A");
        assertEquals(4, acc.restockQty);
        assertEquals(2, acc.guestWaterQty);
        assertEquals(4, sumOf(service.restockDetail("WH-1", YEAR_MONTH), "SKU-A"));
    }

    @Test
    void externalRefIdKeepsRowWithBlankDeviceAttribution() {
        List<WarehouseMovement> all = List.of(ship("SKU-A", "B-1", -3, "EXT-9", START.plusSeconds(100)));
        stubShips(all);
        // 非数字 refId 不参与柜机回填：出库明细与柜机都不该被查
        when(outboundLineMapper.selectList(any())).thenReturn(List.of());

        List<WarehouseMonthlyCloseRestockLineDto> detail = service.restockDetail("WH-1", YEAR_MONTH);
        assertEquals(1, detail.size());
        assertNull(detail.get(0).deviceId());
        assertNull(detail.get(0).deviceName());
        assertEquals(3, sumOf(detail, "SKU-A"));
        assertEquals(WarehouseMonthlyCloseMath.aggregate(all, START, END).get("SKU-A").restockQty,
                sumOf(detail, "SKU-A"));
    }

    /** 下钻的 SQL 只捞本账期 OUTBOUND_SHIP ⇒ 桩按同一条件返回，避免测试替实现作弊。 */
    private void stubShips(List<WarehouseMovement> all) {
        when(movementMapper.selectList(any())).thenReturn(all.stream()
                .filter(m -> "OUTBOUND_SHIP".equals(m.getMovementType()))
                .filter(m -> !m.getCreatedAt().isBefore(START) && m.getCreatedAt().isBefore(END))
                .toList());
    }

    private static int sumOf(List<WarehouseMonthlyCloseRestockLineDto> rows, String skuId) {
        return rows.stream().filter(r -> skuId.equals(r.skuId())).mapToInt(r -> r.qty()).sum();
    }

    private static WarehouseMovement ship(String sku, String batch, int delta, String refId, Instant at) {
        WarehouseMovement m = base(sku, "OUTBOUND_SHIP", delta, at);
        m.setBatchNo(batch);
        m.setRefType("WAREHOUSE_OUTBOUND");
        m.setRefId(refId);
        return m;
    }

    private static WarehouseMovement in(String sku, String type, int delta, Instant at) {
        WarehouseMovement m = base(sku, type, delta, at);
        m.setRefType("PURCHASE_RECEIVE");
        return m;
    }

    private static WarehouseMovement base(String sku, String type, int delta, Instant at) {
        WarehouseMovement m = new WarehouseMovement();
        m.setWarehouseId("WH-1");
        m.setSkuId(sku);
        m.setMovementType(type);
        m.setDeltaQty(delta);
        m.setCreatedAt(at);
        return m;
    }

    private static WarehouseOutboundLine line(Long outboundId, String sku, String batch, String deviceId) {
        WarehouseOutboundLine l = new WarehouseOutboundLine();
        l.setOutboundId(outboundId);
        l.setSkuId(sku);
        l.setBatchNo(batch);
        l.setDeviceId(deviceId);
        return l;
    }

    private static DeviceInfo device(String id, String name) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(id);
        d.setDeviceName(name);
        return d;
    }

    private static SkuCatalog sku(String id, String name) {
        SkuCatalog s = new SkuCatalog();
        s.setSkuId(id);
        s.setSkuName(name);
        s.setBarcode("6901028300018");
        return s;
    }
}
