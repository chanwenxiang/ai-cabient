package com.aicabinet.trade.config;

import com.aicabinet.trade.service.SystemConfigService;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 分布式追踪配置 — OpenTelemetry + OTLP HTTP 导出，配合 Grafana Tempo（P0-6）。
 *
 * <p><b>默认关闭</b>：{@code ops.observability.tracing_enabled} 与端点都留空时，
 * 导出器 bean 虽然存在但**每次都判定为丢弃**，行为与改造前「不注册 exporter」等价
 * （{@code Tracer}/traceId 串联本来就不依赖 exporter，见类注释）。</p>
 *
 * <p><b>运行期开关</b>：不再用启动期 {@code @ConditionalOnExpression} 决定 bean 是否注册 ——
 * 那是「改配置＋重启」才能开关的老路子。现在导出器常驻，开关与端点每次导出时从
 * 系统配置读取，运营台改完**即时生效**。详见
 * {@link RuntimeSwitchableOtlpSpanExporter}。</p>
 *
 * <p>相关配置键：
 * {@link SystemConfigService#OPS_OBSERVABILITY_TRACING_ENABLED}（三态开关）、
 * {@link SystemConfigService#OPS_OBSERVABILITY_OTLP_ENDPOINT}（运行期端点，
 * 留空回退 Spring 属性 {@code tracing.otlp.endpoint}，即环境变量 {@code OTLP_ENDPOINT}）。</p>
 */
@Configuration
public class TracingConfig {

    private static final Logger log = LoggerFactory.getLogger(TracingConfig.class);

    /**
     * 常驻的 OTLP 导出器：把「开不开追踪」从启动期挪到运行期。
     *
     * <p>刻意声明为 {@link SpanExporter} 而非 {@code OtlpHttpSpanExporter}：
     * Spring Boot 的 {@code OtlpTracingConfigurations$Exporters} 是
     * {@code @ConditionalOnMissingBean({OtlpGrpcSpanExporter, OtlpHttpSpanExporter})}，
     * 且另外要求 {@code management.otlp.tracing.endpoint} 有值；本项目不用那条原生通道
     * （只用自定义的 {@code tracing.otlp.endpoint}），故二者不会打架。</p>
     */
    @Bean
    public SpanExporter runtimeSwitchableSpanExporter(
            ObjectProvider<SystemConfigService> systemConfigServiceProvider,
            @Value("${tracing.otlp.endpoint:}") String springEndpoint) {
        RuntimeSwitchableOtlpSpanExporter.ConfigSource source =
                new RuntimeSwitchableOtlpSpanExporter.ConfigSource() {

                    /** 首次导出时再解析，避免把 Spring 装配顺序绑到配置服务上。 */
                    private volatile SystemConfigService resolved;

                    private SystemConfigService service() {
                        SystemConfigService current = resolved;
                        if (current == null) {
                            current = systemConfigServiceProvider.getIfAvailable();
                            resolved = current;
                        }
                        return current;
                    }

                    @Override
                    public String rawTracingSwitch() {
                        SystemConfigService service = service();
                        return service == null
                                ? null
                                : service.getValue(SystemConfigService.OPS_OBSERVABILITY_TRACING_ENABLED, null);
                    }

                    @Override
                    public String databaseEndpoint() {
                        SystemConfigService service = service();
                        return service == null
                                ? null
                                : service.getValue(SystemConfigService.OPS_OBSERVABILITY_OTLP_ENDPOINT, null);
                    }
                };
        log.info("OTLP 导出器已常驻注册：运行期开关={} / 运行期端点={} / 启动期回退端点={}",
                SystemConfigService.OPS_OBSERVABILITY_TRACING_ENABLED,
                SystemConfigService.OPS_OBSERVABILITY_OTLP_ENDPOINT,
                springEndpoint.isBlank() ? "(未设置)" : springEndpoint);
        return new RuntimeSwitchableOtlpSpanExporter(source, springEndpoint);
    }

    @Bean
    public DefaultTracingObservationHandler tracingObservationHandler(Tracer tracer) {
        return new DefaultTracingObservationHandler(tracer);
    }
}
