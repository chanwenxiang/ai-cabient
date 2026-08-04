package com.aicabinet.trade.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LoggingDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingDomainEventPublisher.class);

    @Override
    public void publish(String eventType, String aggregateId, Map<String, Object> payload) {
        log.info("domain_event type={} aggregateId={} payload={}", eventType, aggregateId, payload);
    }
}
