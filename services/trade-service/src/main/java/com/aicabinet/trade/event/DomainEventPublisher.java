package com.aicabinet.trade.event;

import java.util.Map;

public interface DomainEventPublisher {
    void publish(String eventType, String aggregateId, Map<String, Object> payload);
}
