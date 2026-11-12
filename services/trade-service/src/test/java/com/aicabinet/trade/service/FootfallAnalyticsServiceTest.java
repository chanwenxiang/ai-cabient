package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FootfallAnalyticsDto;
import com.aicabinet.common.dto.FootfallDeviceDto;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FootfallAnalyticsServiceTest {

    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private CabinetOrderLineMapper orderLineRepository;
    @Mock private DeviceInfoMapper deviceRepository;

    private FootfallAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new FootfallAnalyticsService(sessionRepository, orderRepository,
                orderLineRepository, deviceRepository);
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
        // R20 P2-17：各柜开门数改成一条 group-by 取回，不再逐台 count
        when(sessionRepository.countOpensGroupByDeviceSince(any())).thenReturn(List.of(
                opensRow("CAB-001", 6L), opensRow("CAB-002", 4L)));
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

        // R20 P2-17：不得回退成「每台柜机一次 count」
        verify(sessionRepository, never()).countByDeviceIdAndCreatedAtAfter(anyString(), any());
        // 柜机清单只加载一次（原来 overview 与设备循环各一次）
        verify(deviceRepository, times(1)).findAllOrderByDeviceIdAsc();
    }

    /**
     * R20 P2-17：group-by 结果里缺失的柜机按 0 记，不能因为聚合行没覆盖到就当它「没数据」而跳过。
     * 同时聚合结果里的 null 设备（历史脏行）不得凭空造出一台柜机。
     */
    @Test
    void analytics_deviceWithoutOpenRows_isStillListedWithZeroOpens() {
        when(orderRepository.findByStatusAndCreatedAtAfter(eq("PAID"), any())).thenReturn(List.of());
        when(sessionRepository.countByCreatedAtAfter(any())).thenReturn(3L);
        when(sessionRepository.countOpensGroupByDeviceSince(any())).thenReturn(List.of(opensRow("CAB-001", 3L)));
        when(deviceRepository.findAllOrderByDeviceIdAsc()).thenReturn(List.of(
                device("CAB-001", "柜A"), device("CAB-002", "柜B")));
        when(orderLineRepository.skuBreakdownSince(any())).thenReturn(List.of());

        FootfallAnalyticsDto dto = service.analytics(7, 50, 20);

        assertEquals(2, dto.devices().size(), "两台柜机都要在榜上，没开门的那台记 0");
        assertEquals(2, dto.overview().deviceCount());
        FootfallDeviceDto zero = dto.devices().stream()
                .filter(x -> "CAB-002".equals(x.deviceId())).findFirst().orElseThrow();
        assertEquals(0, zero.opens());
        assertEquals(0.0, zero.conversionRate(), 1e-9);
    }

    private static java.util.Map<String, Object> opensRow(String deviceId, long opens) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("device_id", deviceId);
        row.put("opens", opens);
        return row;
    }

    private static DeviceInfo device(String id, String name) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(id);
        d.setDeviceName(name);
        return d;
    }

}
