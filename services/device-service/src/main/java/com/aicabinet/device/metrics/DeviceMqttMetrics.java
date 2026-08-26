package com.aicabinet.device.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class DeviceMqttMetrics {
    private static final String DEVICE_COMMAND = "device.command";
    private static final String RESULT = "result";


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
        this.doorEventsForwarded = registry.counter("device.mqtt.door", RESULT, "forwarded");
        this.doorEventsDeduped = registry.counter("device.mqtt.door", RESULT, "deduped");
        this.heartbeatsForwarded = registry.counter("device.mqtt.heartbeat", RESULT, "forwarded");
        this.heartbeatsDropped = registry.counter("device.mqtt.heartbeat", RESULT, "dropped");
        this.acksReceived = registry.counter("device.mqtt.ack");
        this.tradeForwardFailures = registry.counter("device.trade.forward", RESULT, "failure");
        this.commandPublished = registry.counter(DEVICE_COMMAND, RESULT, "published");
        this.commandAckSuccess = registry.counter(DEVICE_COMMAND, RESULT, "ack_success");
        this.commandAckFailure = registry.counter(DEVICE_COMMAND, RESULT, "ack_failure");
        this.commandAckTimeout = registry.counter(DEVICE_COMMAND, RESULT, "ack_timeout");
        this.commandAckUnknown = registry.counter(DEVICE_COMMAND, RESULT, "ack_unknown");
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
