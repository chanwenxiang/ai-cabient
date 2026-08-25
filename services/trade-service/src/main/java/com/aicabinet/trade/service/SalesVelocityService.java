package com.aicabinet.trade.service;

import com.aicabinet.trade.config.RopProperties;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SalesVelocityService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final CabinetOrderLineMapper lineRepository;
    private final RopProperties ropProperties;

    public SalesVelocityService(CabinetOrderLineMapper lineRepository, RopProperties ropProperties) {
        this.lineRepository = lineRepository;
        this.ropProperties = ropProperties;
    }

    public record SkuVelocity(int soldQty7d, int soldQty14d, double avgDailySales, int ropPoint) {}

    @Transactional(readOnly = true)
    public Map<String, SkuVelocity> velocityBySku(String deviceId) {
        Instant since7 = LocalDate.now(ZONE).minusDays(7).atStartOfDay(ZONE).toInstant();
        Instant since14 = LocalDate.now(ZONE).minusDays(14).atStartOfDay(ZONE).toInstant();
        Map<String, Integer> sold7 = toMap(lineRepository.sumSoldQtyBySkuSince(deviceId, since7));
        Map<String, Integer> sold14 = toMap(lineRepository.sumSoldQtyBySkuSince(deviceId, since14));

        Map<String, SkuVelocity> out = new HashMap<>();
        for (String skuId : sold14.keySet()) {
            int qty7 = sold7.getOrDefault(skuId, 0);
            int qty14 = sold14.getOrDefault(skuId, 0);
            out.put(skuId, toVelocity(qty7, qty14));
        }
        for (Map.Entry<String, Integer> entry : sold7.entrySet()) {
            out.putIfAbsent(entry.getKey(), toVelocity(entry.getValue(), 0));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public SkuVelocity velocityFor(String deviceId, String skuId) {
        return velocityBySku(deviceId).getOrDefault(skuId, new SkuVelocity(0, 0, 0, 0));
    }

    private SkuVelocity toVelocity(int qty7, int qty14) {
        double avgDaily = qty7 > 0 ? qty7 / 7.0 : qty14 / 14.0;
        int ropPoint = avgDaily <= 0 ? 0
                : (int) Math.ceil(avgDaily * ropProperties.coverDays());
        return new SkuVelocity(qty7, qty14, avgDaily, ropPoint);
    }

    private static Map<String, Integer> toMap(List<Object[]> rows) {
        Map<String, Integer> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((String) row[0], ((Number) row[1]).intValue());
        }
        return map;
    }
}
