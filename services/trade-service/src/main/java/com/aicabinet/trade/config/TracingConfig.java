package com.aicabinet.trade.config;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 分布式追踪配置 — OpenTelemetry + OTLP HTTP 导出。
 * 配合 Jaeger / Grafana Tempo / SigNoz 使用。
 * 默认关闭（通过 otlp.endpoint 控制），生产环境配置 OTLP_ENDPOINT 即可启用。
 */
@Configuration
public class TracingConfig {

    private static final Logger log = LoggerFactory.getLogger(TracingConfig.class);

    @Bean
    public OtlpHttpSpanExporter otlpHttpSpanExporter(
            @Value("${tracing.otlp.endpoint:}") String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            log.info("OTLP tracing disabled (no endpoint configured)");
            return OtlpHttpSpanExporter.builder()
                    .setEndpoint("http://localhost:4318/v1/traces")
                    .build();
        }
        log.info("OTLP tracing enabled, exporting to {}", endpoint);
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .setTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Bean
    public DefaultTracingObservationHandler tracingObservationHandler(Tracer tracer) {
        return new DefaultTracingObservationHandler(tracer);
    }
}
