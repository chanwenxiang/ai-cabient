package com.aicabinet.trade.reconciliation;

import com.aicabinet.trade.config.ReconciliationProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地 dev：用账本数据模拟平台账单（与 ledger 一致，便于联调对账流程）。
 */
@Component
public class MockPlatformBillProvider implements PlatformBillProvider {

    private final ReconciliationProperties properties;
    private final CabinetOrderMapper orderRepository;
    private final RechargeOrderMapper rechargeRepository;

    public MockPlatformBillProvider(ReconciliationProperties properties,
                                    CabinetOrderMapper orderRepository,
                                    RechargeOrderMapper rechargeRepository) {
        this.properties = properties;
        this.orderRepository = orderRepository;
        this.rechargeRepository = rechargeRepository;
    }

    @Override
    public String channel() {
        return "MOCK";
    }

    @Override
    public List<PlatformBillLine> fetchDailyBill(LocalDate date) {
        if (!properties.mockEnabled()) {
            return List.of();
        }
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<PlatformBillLine> lines = new ArrayList<>();

        for (CabinetOrder order : orderRepository.findByCreatedAtBetween(start, end)) {
            lines.add(new PlatformBillLine(
                    "WX-MOCK-" + order.getOrderId(),
                    order.getOrderId(),
                    order.getTotalAmountCents(),
                    order.getCreatedAt(),
                    "PAY",
                    "{\"source\":\"mock\",\"type\":\"order\"}"
            ));
        }
        for (RechargeOrder recharge : rechargeRepository.findPaidBetween(start, end)) {
            lines.add(new PlatformBillLine(
                    "WX-MOCK-RCH-" + recharge.getOrderId(),
                    recharge.getOrderId(),
                    recharge.getAmountCents(),
                    recharge.getPaidAt(),
                    "RECHARGE",
                    "{\"source\":\"mock\",\"type\":\"recharge\"}"
            ));
        }
        return lines;
    }
}
