package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderReadModel;

import java.time.Instant;
import java.util.List;

/** 测试用订单读模型样例。 */
public final class OrderReadModelFixtures {

    private OrderReadModelFixtures() {
    }

    public static OrderReadModel sample(String orderId, String sessionId) {
        return sample(orderId, sessionId, 500, "PAID");
    }

    public static OrderReadModel sample(String orderId, String sessionId, int totalAmountCents, String status) {
        Instant now = Instant.now();
        return new OrderReadModel(
                orderId,
                sessionId,
                1L,
                "DEV-1",
                null,
                null,
                null,
                totalAmountCents,
                totalAmountCents,
                0,
                0,
                status,
                "BALANCE",
                0,
                "",
                null,
                null,
                null,
                0,
                false,
                null,
                now,
                now,
                null,
                List.of(),
                null,
                null
        );
    }
}
