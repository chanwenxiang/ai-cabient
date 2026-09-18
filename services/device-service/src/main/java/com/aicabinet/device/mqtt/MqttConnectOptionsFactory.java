package com.aicabinet.device.mqtt;

import com.aicabinet.device.config.MqttProperties;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

@Component
public class MqttConnectOptionsFactory {

    private static final Logger log = LoggerFactory.getLogger(MqttConnectOptionsFactory.class);

    private final MqttProperties properties;

    public MqttConnectOptionsFactory(MqttProperties properties) {
        this.properties = properties;
    }

    public MqttConnectOptions create() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(false);
        // C11：cleanSession=false 保证处理失败未 ACK 的 QoS1 消息在重连后由 broker 重投；
        // 手动 ACK 开关在 MqttEventListener.connect() 里对 client 实例调用 setManualAcks(true)。
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        if (properties.hasCredentials()) {
            options.setUserName(properties.username());
            options.setPassword(properties.password().toCharArray());
        }
        if (properties.isSsl()) {
            try {
                options.setSocketFactory(createSslSocketFactory());
            } catch (GeneralSecurityException | IOException e) {
                throw new IllegalStateException("failed to configure MQTT SSL", e);
            }
        }
        return options;
    }

    private SSLSocketFactory createSslSocketFactory() throws GeneralSecurityException, IOException {
        if (properties.trustStorePath() != null && !properties.trustStorePath().isBlank()) {
            KeyStore trustStore = KeyStore.getInstance("JKS");
            char[] password = properties.trustStorePassword() != null
                    ? properties.trustStorePassword().toCharArray()
                    : new char[0];
            try (FileInputStream in = new FileInputStream(properties.trustStorePath())) {
                trustStore.load(in, password);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            log.info("MQTT SSL using trust store {}", properties.trustStorePath());
            return ctx.getSocketFactory();
        }
        return SSLContext.getDefault().getSocketFactory();
    }
}
