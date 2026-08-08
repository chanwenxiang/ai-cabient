package com.aicabinet.device;

import com.aicabinet.common.security.InternalApiAuthInterceptor;
import com.aicabinet.common.security.InternalApiProperties;
import com.aicabinet.device.config.MqttProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(InternalApiAuthInterceptor.class)
@EnableConfigurationProperties({InternalApiProperties.class, MqttProperties.class})
public class DeviceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeviceServiceApplication.class, args);
    }
}
