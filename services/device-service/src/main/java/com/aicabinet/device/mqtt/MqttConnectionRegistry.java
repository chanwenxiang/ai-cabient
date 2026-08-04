package com.aicabinet.device.mqtt;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class MqttConnectionRegistry {

    private final AtomicBoolean publisherConnected = new AtomicBoolean(false);
    private final AtomicBoolean listenerConnected = new AtomicBoolean(false);

    public void setPublisherConnected(boolean connected) {
        publisherConnected.set(connected);
    }

    public void setListenerConnected(boolean connected) {
        listenerConnected.set(connected);
    }

    public boolean isPublisherConnected() {
        return publisherConnected.get();
    }

    public boolean isListenerConnected() {
        return listenerConnected.get();
    }

    public boolean isAllConnected() {
        return publisherConnected.get() && listenerConnected.get();
    }
}
