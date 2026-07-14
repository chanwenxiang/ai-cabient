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
    private final Counter commandPublished;
    private final Counter commandAckSuccess;
    private final Counter commandAckFailure;
    private final Counter commandAckTimeout;
    private final Counter commandAckUnknown;

    public DeviceMqttMetrics(MeterRegistry registry) {
        this.mqttMessagesIn = registry.counter("device.mqtt.messages.in");
        this.doorEventsForwarded = registry.counter("device.mqtt.door", "result", "forwarded");
        this.doorEventsDeduped = registry.counter("device.mqtt.door", "result", "deduped");
        this.heartbeatsForwarded = registry.counter("device.mqtt.heartbeat", "result", "forwarded");
        this.heartbeatsDropped = registry.counter("device.mqtt.heartbeat", "result", "dropped");
        this.acksReceived = registry.counter("device.mqtt.ack");
        this.tradeForwardFailures = registry.counter("device.trade.forward", "result", "failure");
        this.commandPublished = registry.counter("device.command", "result", "published");
        this.commandAckSuccess = registry.counter("device.command", "result", "ack_success");
        this.commandAckFailure = registry.counter("device.command", "result", "ack_failure");
        this.commandAckTimeout = registry.counter("device.command", "result", "ack_timeout");
        this.commandAckUnknown = registry.counter("device.command", "result", "ack_unknown");
    }

    public void recordMessageIn() { mqttMessagesIn.increment(); }
    public void recordDoorForwarded() { doorEventsForwarded.increment(); }
    public void recordDoorDeduped() { doorEventsDeduped.increment(); }
    public void recordHeartbeatForwarded() { heartbeatsForwarded.increment(); }
    public void recordHeartbeatDropped() { heartbeatsDropped.increment(); }
    public void recordAck() { acksReceived.increment(); }
    public void recordTradeFailure() { tradeForwardFailures.increment(); }
    public void recordCommandPublished() { commandPublished.increment(); }
    public void recordCommandAckSuccess() { commandAckSuccess.increment(); }
    public void recordCommandAckFailure() { commandAckFailure.increment(); }
    public void recordCommandAckTimeout() { commandAckTimeout.increment(); }
    public void recordCommandAckUnknown() { commandAckUnknown.increment(); }
}
