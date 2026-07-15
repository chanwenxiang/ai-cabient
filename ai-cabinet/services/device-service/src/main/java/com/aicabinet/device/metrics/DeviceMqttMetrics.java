package com.aicabinet.device.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class DeviceMqttMetrics {

    private final Counter mqttMessagesIn;
    private final Counter doorEventsForwarded;
    private final Counter doorEventsDeduped;
    private final Counter heartbeatsForwarded;
    private final Counter heartbeatsDropped;
    private final Counter acksReceived;
    private final Counter tradeForwardFailures;

    public DeviceMqttMetrics(MeterRegistry registry) {
        this.mqttMessagesIn = registry.counter("device.mqtt.messages.in");
        this.doorEventsForwarded = registry.counter("device.mqtt.door", "result", "forwarded");
        this.doorEventsDeduped = registry.counter("device.mqtt.door", "result", "deduped");
        this.heartbeatsForwarded = registry.counter("device.mqtt.heartbeat", "result", "forwarded");
        this.heartbeatsDropped = registry.counter("device.mqtt.heartbeat", "result", "dropped");
        this.acksReceived = registry.counter("device.mqtt.ack");
        this.tradeForwardFailures = registry.counter("device.trade.forward", "result", "failure");
    }

    public void recordMessageIn() { mqttMessagesIn.increment(); }
    public void recordDoorForwarded() { doorEventsForwarded.increment(); }
    public void recordDoorDeduped() { doorEventsDeduped.increment(); }
    public void recordHeartbeatForwarded() { heartbeatsForwarded.increment(); }
    public void recordHeartbeatDropped() { heartbeatsDropped.increment(); }
    public void recordAck() { acksReceived.increment(); }
    public void recordTradeFailure() { tradeForwardFailures.increment(); }
}
