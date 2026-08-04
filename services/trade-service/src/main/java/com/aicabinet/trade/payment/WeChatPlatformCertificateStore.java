package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.WeChatPayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信支付平台证书缓存：支持静态配置 PEM，或启动后从 {@code GET /v3/certificates} 自动拉取。
 */
@Component
public class WeChatPlatformCertificateStore {

    private static final Logger log = LoggerFactory.getLogger(WeChatPlatformCertificateStore.class);
    private static final Duration REFRESH_INTERVAL = Duration.ofHours(12);

    private final WeChatPayProperties properties;
    private final WeChatPayV3Client v3Client;
    private final WeChatPayV3Aead aead;

    private final ConcurrentHashMap<String, String> certsBySerial = new ConcurrentHashMap<>();
    private volatile Instant lastRefresh = Instant.EPOCH;

    public WeChatPlatformCertificateStore(WeChatPayProperties properties,
                                          @Lazy WeChatPayV3Client v3Client,
                                          WeChatPayV3Aead aead) {
        this.properties = properties;
        this.v3Client = v3Client;
        this.aead = aead;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        if (!properties.isConfigured() || !properties.platformCertAutoFetch()) {
            return;
        }
        if (hasStaticCert()) {
            log.info("wechat platform cert configured statically, skip auto-fetch warm-up");
            return;
        }
        refresh();
    }

    public Optional<String> resolveCertificatePem(String serialNo) {
        if (serialNo != null && !serialNo.isBlank()) {
            String cached = certsBySerial.get(normalizeSerial(serialNo));
            if (cached != null) {
                return Optional.of(cached);
            }
        }
        if (hasStaticCert()) {
            return Optional.of(properties.platformCert());
        }
        if (properties.platformCertAutoFetch()) {
            refreshIfStale(true);
            if (serialNo != null && !serialNo.isBlank()) {
                String cached = certsBySerial.get(normalizeSerial(serialNo));
                if (cached != null) {
                    return Optional.of(cached);
                }
            }
            if (certsBySerial.size() == 1) {
                return Optional.of(certsBySerial.values().iterator().next());
            }
        }
        return Optional.empty();
    }

    public synchronized void refreshIfStale(boolean forceOnMiss) {
        if (!properties.platformCertAutoFetch() || !properties.isConfigured()) {
            return;
        }
        boolean stale = Duration.between(lastRefresh, Instant.now()).compareTo(REFRESH_INTERVAL) >= 0;
        if (!forceOnMiss && !stale && !certsBySerial.isEmpty()) {
            return;
        }
        refresh();
    }

    public synchronized void refresh() {
        if (!properties.platformCertAutoFetch() || !properties.isConfigured()) {
            return;
        }
        try {
            JsonNode root = v3Client.get("/v3/certificates");
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                log.warn("wechat certificates response missing data array");
                return;
            }
            int loaded = 0;
            for (JsonNode item : data) {
                String serial = item.path("serial_no").asText(null);
                JsonNode encrypted = item.path("encrypt_certificate");
                if (serial == null || serial.isBlank() || encrypted.isMissingNode()) {
                    continue;
                }
                String pem = aead.decrypt(
                        properties.apiV3Key(),
                        encrypted.path("associated_data").asText("certificate"),
                        encrypted.path("nonce").asText(""),
                        encrypted.path("ciphertext").asText("")
                );
                certsBySerial.put(normalizeSerial(serial), pem);
                loaded++;
                log.info("wechat platform cert cached serial={}", serial);
            }
            lastRefresh = Instant.now();
            if (loaded == 0) {
                log.warn("wechat platform cert refresh returned zero certificates");
            }
        } catch (Exception e) {
            log.error("wechat platform cert refresh failed", e);
        }
    }

    private boolean hasStaticCert() {
        return properties.platformCert() != null && !properties.platformCert().isBlank();
    }

    private static String normalizeSerial(String serialNo) {
        return serialNo.trim().toUpperCase(Locale.ROOT);
    }
}
