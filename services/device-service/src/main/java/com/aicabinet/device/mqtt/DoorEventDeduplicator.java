package com.aicabinet.device.mqtt;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DoorEventDeduplicator {

    private static final long TTL_MS = 60_000;

    private final ConcurrentHashMap<String, Long> recent = new ConcurrentHashMap<>();

    public boolean isDuplicate(String sessionId, String doorState) {
        return isDuplicate(sessionId, doorState, "");
    }

    public boolean isDuplicate(String sessionId, String doorState, String fingerprint) {
        if (sessionId == null || doorState == null) {
            return false;
        }
        String key = sessionId + ":" + doorState + ":" + (fingerprint != null ? fingerprint : "");
        long now = System.currentTimeMillis();
        Long previous = recent.put(key, now);
        evictExpired(now);
        return previous != null && now - previous < TTL_MS;
    }

    private void evictExpired(long now) {
        if (recent.size() < 256) {
            return;
        }
        Iterator<Map.Entry<String, Long>> it = recent.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > TTL_MS) {
                it.remove();
            }
        }
    }
}
