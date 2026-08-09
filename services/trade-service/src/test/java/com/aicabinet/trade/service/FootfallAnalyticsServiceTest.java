package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FootfallAnalyticsDto;
import com.aicabinet.common.dto.SlotHeatDto;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSlot;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FootfallAnalyticsServiceTest {

    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private CabinetOrderLineMapper orderLineRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private DeviceSlotMapper slotRepository;

    private FootfallAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new FootfallAnalyticsService(sessionRepository, orderRepository,
                orderLineRepository, deviceRepository, slotRepository);
    }

    private static CabinetOrder order(String orderId, long userId, String deviceId, int cents, Instant createdAt) {
        CabinetOrder o = new CabinetOrder();
        o.setOrderId(orderId);
        o.setUserId(userId);
        o.setDeviceId(deviceId);
        o.setStatus("PAID");
        o.setTotalAmountCents(cents);
        o.setCreatedAt(createdAt);
        return o;
    }

    @Test
    void analytics_shouldComputeOverviewDevicesHourlyAndSkus() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant t1 = LocalDateTime.of(2026, 8, 9, 10, 0).atZone(zone).toInstant();
        Instant t2 = LocalDateTime.of(2026, 8, 9, 11, 30).atZone(zone).toInstant();
        Instant t3 = LocalDateTime.of(2026, 8, 9, 11, 45).atZone(zone).toInstant();
        List<CabinetOrder> orders = List.of(
                order("O1", 1L, "CAB-001", 1000, t1),
                order("O2", 1L, "CAB-001", 2000, t2),
                order("O3", 2L, "CAB-002", 3000, t3));

        when(orderRepository.findByStatusAndCreatedAtAfter(eq("PAID"), any())).thenReturn(orders);
        when(sessionRepository.countByCreatedAtAfter(any())).thenReturn(10L);
        when(sessionRepository.countByDeviceIdAndCreatedAtAfter(eq("CAB-001"), any())).thenReturn(6L);
        when(sessionRepository.countByDeviceIdAndCreatedAtAfter(eq("CAB-002"), any())).thenReturn(4L);
        when(deviceRepository.findAllOrderByDeviceIdAsc()).thenReturn(List.of(
                device("CAB-001", "柜A"), device("CAB-002", "柜B")));
        when(orderLineRepository.skuBreakdownSince(any())).thenReturn(List.<Object[]>of(
                new Object[]{"SKU-1", "可乐", 3, 6000}));

        FootfallAnalyticsDto dto = service.analytics(7, 50, 20);

        // 开门 10 次，订单 3，转化 30%，客单价 2000 分，复购用户 1（userId=1 两单）
        assertEquals(10, dto.overview().totalOpens());
        assertEquals(3, dto.overview().totalPaidOrders());
        assertEquals(6000, dto.overview().revenueCents());
        assertEquals(30.0, dto.overview().conversionRate(), 0.01);
        assertEquals(2000, dto.overview().avgOrderValueCents());
        assertEquals(1, dto.overview().repeatBuyers());
        // 坪效排行：CAB-001 营收 3000 居首
        assertEquals("CAB-001", dto.devices().get(0).deviceId());
        assertEquals(6, dto.devices().get(0).opens());
        assertEquals(33.33, dto.devices().get(0).conversionRate(), 0.01);
        // 时段热力：10 点 1 单、11 点 2 单
        assertEquals(1, dto.hourly().get(10).orders());
        assertEquals(2, dto.hourly().get(11).orders());
        // 商品热区
        assertEquals("SKU-1", dto.topSkus().get(0).skuId());
    }

    @Test
    void slotHeat_shouldAggregateBySlotWithHeatLevels() {
        when(slotRepository.findByIdDeviceId(eq("CAB-001"))).thenReturn(List.of(
                slot("A1", 1, 1, "SKU-1"), slot("B2", 2, 2, "SKU-2")));
        when(orderLineRepository.slotBreakdownByDeviceSince(eq("CAB-001"), any())).thenReturn(List.<Object[]>of(
                new Object[]{"A1", "SKU-1", "可乐", 30, 9000},
                new Object[]{"B2", "SKU-2", "薯片", 5, 1500}));

        List<SlotHeatDto> out = service.slotHeat("CAB-001", 7);

        assertEquals(2, out.size());
        assertEquals("A1", out.get(0).slotId());
        assertEquals(30, out.get(0).qtySold());
        // 销量最高 → 热度 3
        assertEquals(3, out.get(0).heatLevel());
        assertEquals("B2", out.get(1).slotId());
        assertEquals(2, out.get(1).rowNo());
        assertEquals(1, out.get(1).heatLevel());
    }

    private static DeviceInfo device(String id, String name) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(id);
        d.setDeviceName(name);
        return d;
    }

    private static DeviceSlot slot(String code, int row, int col, String skuId) {
        DeviceSlot s = new DeviceSlot();
        s.setSlotCode(code);
        s.setRowNo(row);
        s.setColNo(col);
        s.setAssignedSkuId(skuId);
        return s;
    }
}
