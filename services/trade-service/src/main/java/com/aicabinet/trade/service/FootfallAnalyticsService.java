package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FootfallAnalyticsDto;
import com.aicabinet.common.dto.FootfallDeviceDto;
import com.aicabinet.common.dto.FootfallOverviewDto;
import com.aicabinet.common.dto.HourlyHeatDto;
import com.aicabinet.common.dto.SkuHeatDto;
import com.aicabinet.common.dto.SlotHeatDto;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSlot;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客流 / 时段热区 / 坪效分析：以开门会话为客流、支付订单为转化，输出柜机坪效排行、
 * 24 小时时段热力与商品热区（货道级订单行暂未带货道字段，热区以商品维度呈现）。
 */
@Service
public class FootfallAnalyticsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final DeviceInfoMapper deviceRepository;
    private final DeviceSlotMapper slotRepository;

    public FootfallAnalyticsService(ShoppingSessionMapper sessionRepository,
                                    CabinetOrderMapper orderRepository,
                                    CabinetOrderLineMapper orderLineRepository,
                                    DeviceInfoMapper deviceRepository,
                                    DeviceSlotMapper slotRepository) {
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.deviceRepository = deviceRepository;
        this.slotRepository = slotRepository;
    }

    @Transactional(readOnly = true)
    public FootfallAnalyticsDto analytics(int days, int deviceLimit, int skuLimit) {
        int d = Math.max(1, Math.min(days, 90));
        Instant since = Instant.now().minusSeconds(d * 86400L);
        List<CabinetOrder> paidOrders = orderRepository.findByStatusAndCreatedAtAfter("PAID", since);
        long opens = sessionRepository.countByCreatedAtAfter(since);
        long orders = paidOrders.size();
        long revenue = paidOrders.stream().mapToLong(CabinetOrder::getTotalAmountCents).sum();
        long repeatBuyers = countRepeatBuyers(paidOrders);

        FootfallOverviewDto overview = new FootfallOverviewDto(
                opens, orders, revenue,
                opens == 0 ? 0 : round2(orders * 100.0 / opens),
                orders == 0 ? 0 : Math.round(revenue / (double) orders),
                repeatBuyers,
                deviceRepository.findAllOrderByDeviceIdAsc().size());

        Map<String, long[]> perDevice = bucketByDevice(paidOrders);
        List<FootfallDeviceDto> devices = new ArrayList<>();
        for (DeviceInfo device : deviceRepository.findAllOrderByDeviceIdAsc()) {
            long deviceOpens = sessionRepository.countByDeviceIdAndCreatedAtAfter(device.getDeviceId(), since);
            long[] agg = perDevice.getOrDefault(device.getDeviceId(), new long[]{0, 0});
            devices.add(new FootfallDeviceDto(
                    device.getDeviceId(),
                    device.getDeviceName() != null ? device.getDeviceName() : device.getDeviceId(),
                    deviceOpens,
                    agg[0],
                    agg[1],
                    deviceOpens == 0 ? 0 : round2(agg[0] * 100.0 / deviceOpens),
                    agg[1]));
        }
        devices.sort(Comparator.comparingLong(FootfallDeviceDto::revenueCents).reversed());
        int dl = Math.max(1, Math.min(deviceLimit, 200));
        if (devices.size() > dl) {
            devices = devices.subList(0, dl);
        }

        List<HourlyHeatDto> hourly = bucketByHour(paidOrders);
        List<SkuHeatDto> skus = skuHeat(since, skuLimit);
        return new FootfallAnalyticsDto(overview, devices, hourly, skus);
    }

    /** 货道级热区：按销量分位给热度等级（3=最热），行/列来自货道档案。 */
    @Transactional(readOnly = true)
    public List<SlotHeatDto> slotHeat(String deviceId, int days) {
        int d = Math.max(1, Math.min(days, 90));
        Instant since = Instant.now().minusSeconds(d * 86400L);
        Map<String, DeviceSlot> slots = new HashMap<>();
        for (DeviceSlot slot : slotRepository.findByIdDeviceId(deviceId)) {
            slots.putIfAbsent(slot.getSlotCode(), slot);
        }
        List<SlotHeatDto> out = new ArrayList<>();
        for (Object[] row : orderLineRepository.slotBreakdownByDeviceSince(deviceId, since)) {
            String slotId = (String) row[0];
            DeviceSlot slot = slots.get(slotId);
            out.add(new SlotHeatDto(
                    slotId,
                    slot != null ? slot.getRowNo() : 1,
                    slot != null ? slot.getColNo() : 1,
                    (String) row[1],
                    (String) row[2],
                    ((Number) row[3]).longValue(),
                    ((Number) row[4]).longValue(),
                    0));
        }
        out.sort(Comparator.comparingLong(SlotHeatDto::qtySold).reversed());
        int n = out.size();
        for (int i = 0; i < n; i++) {
            double ratio = n <= 1 ? 0 : (double) i / n;
            out.set(i, new SlotHeatDto(
                    out.get(i).slotId(), out.get(i).rowNo(), out.get(i).colNo(),
                    out.get(i).skuId(), out.get(i).skuName(), out.get(i).qtySold(),
                    out.get(i).revenueCents(),
                    ratio < 0.25 ? 3 : ratio < 0.5 ? 2 : ratio < 0.75 ? 1 : 0));
        }
        return out;
    }

    private static long countRepeatBuyers(List<CabinetOrder> paidOrders) {
        Map<Long, Integer> byUser = new HashMap<>();
        for (CabinetOrder order : paidOrders) {
            byUser.merge(order.getUserId(), 1, Integer::sum);
        }
        long repeat = 0;
        for (int c : byUser.values()) {
            if (c >= 2) {
                repeat++;
            }
        }
        return repeat;
    }

    private static Map<String, long[]> bucketByDevice(List<CabinetOrder> paidOrders) {
        Map<String, long[]> map = new HashMap<>();
        for (CabinetOrder order : paidOrders) {
            if (order.getDeviceId() == null) {
                continue;
            }
            long[] agg = map.computeIfAbsent(order.getDeviceId(), k -> new long[]{0, 0});
            agg[0]++;
            agg[1] += order.getTotalAmountCents();
        }
        return map;
    }

    private static List<HourlyHeatDto> bucketByHour(List<CabinetOrder> paidOrders) {
        long[] ordersByHour = new long[24];
        long[] revenueByHour = new long[24];
        for (CabinetOrder order : paidOrders) {
            if (order.getCreatedAt() == null) {
                continue;
            }
            int hour = LocalDateTime.ofInstant(order.getCreatedAt(), ZONE).getHour();
            ordersByHour[hour]++;
            revenueByHour[hour] += order.getTotalAmountCents();
        }
        List<HourlyHeatDto> out = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            out.add(new HourlyHeatDto(h, ordersByHour[h], revenueByHour[h]));
        }
        return out;
    }

    private List<SkuHeatDto> skuHeat(Instant since, int limit) {
        int lim = Math.max(1, Math.min(limit, 100));
        List<SkuHeatDto> out = new ArrayList<>();
        for (Object[] row : orderLineRepository.skuBreakdownSince(since)) {
            out.add(new SkuHeatDto(
                    (String) row[0],
                    (String) row[1],
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue()));
        }
        out.sort(Comparator.comparingLong(SkuHeatDto::revenueCents).reversed());
        return out.size() > lim ? out.subList(0, lim) : out;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
