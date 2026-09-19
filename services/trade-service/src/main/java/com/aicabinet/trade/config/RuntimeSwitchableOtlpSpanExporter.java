package com.aicabinet.trade.config;

import com.aicabinet.trade.service.SystemConfigService;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行期可开关的 OTLP 跨度导出器（P0-6）。
 *
 * <h2>为什么需要它</h2>
 * Spring Boot 的 {@code OpenTelemetryTracingAutoConfiguration} 只带
 * {@code @ConditionalOnClass({OtelTracer, SdkTracerProvider, OpenTelemetry})}，**没有任何 property 条件**，
 * 并且用 {@code spanExporters(ObjectProvider&lt;SpanExporter&gt;)} **收集全部 {@code SpanExporter} bean**
 * 装进 {@code BatchSpanProcessor} → {@code SdkTracerProvider}（以上为 3.5.16 字节码实证）。
 * 也就是说「Tracer 存在、traceId/spanId 能进 MDC 日志」**一直成立**，
 * 唯一被启动期条件卡住的就是「exporter bean 在不在」。
 * <p>
 * 改造前 {@code TracingConfig} 用
 * {@code @ConditionalOnExpression("T().hasText('${tracing.otlp.endpoint:}')")} 决定该 bean 是否注册
 * ⇒ <b>想开关追踪就必须改配置/改代码并重启</b>。
 *
 * <h2>改造后</h2>
 * 本导出器 bean **常驻**，把「开不开」从**启动期**挪到**每次 export 的运行期判定**：
 * <ul>
 *   <li>{@code ops.observability.tracing_enabled} = {@code true}/{@code 1} ⇒ 强制开；</li>
 *   <li>{@code = false}/{@code 0} ⇒ 强制关（<b>压过</b> Spring 属性）；</li>
 *   <li>留空/缺行 ⇒ 跟随：按「有没有解析出端点」决定（端点非空即开）。</li>
 * </ul>
 * 端点优先取 {@code ops.observability.otlp_endpoint}，留空回退 Spring 属性
 * {@code tracing.otlp.endpoint}；两者都空 ⇒ 丢弃。端点变化时自动重建底层导出器。
 *
 * <h2>纪律</h2>
 * 配置读取与导出全部容错：数据库异常一律按「丢弃」处理，
 * <b>绝不让追踪把业务带崩</b>（导出跑在 {@code BatchSpanProcessor} 自己的线程上）。
 * 静默丢弃不打日志噪声；**只在「显式开启却没端点」这一种误配**下限频告警。
 */
public class RuntimeSwitchableOtlpSpanExporter implements SpanExporter {

    /** 单次导出的决策结果。抽成枚举是为了让判定逻辑可脱离网络做单测。 */
    public enum Decision {
        /** 真正外发到 OTLP 端点。 */
        SEND,
        /** 被运行期开关显式关闭。 */
        DROP_DISABLED,
        /** 没解析出端点（开关跟随，或显式开启但端点为空）。 */
        DROP_NO_ENDPOINT
    }

    /** 配置读取源。抽成接口是为了让本类可以脱离 Spring / 数据库做单测。 */
    public interface ConfigSource {
        /** {@code ops.observability.tracing_enabled} 的原始值；null 或空白 = 跟随。 */
        String rawTracingSwitch();

        /** {@code ops.observability.otlp_endpoint} 的原始值；可能为 null 或空白。 */
        String databaseEndpoint();
    }

    private static final Logger log = LoggerFactory.getLogger(RuntimeSwitchableOtlpSpanExporter.class);

    /** 单次导出的超时，与改造前保持一致（5s）。 */
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(5);

    /** 误配告警的最小间隔，避免每 5 秒一个批次刷屏。 */
    private static final long WARN_INTERVAL_MILLIS = 60_000L;

    private final ConfigSource configSource;
    private final String springEndpoint;

    private final AtomicLong sentBatches = new AtomicLong();
    private final AtomicLong droppedDisabledBatches = new AtomicLong();
    private final AtomicLong droppedNoEndpointBatches = new AtomicLong();
    private final AtomicLong lastWarnAtMillis = new AtomicLong();

    private final Object delegateLock = new Object();
    private volatile String delegateEndpoint;
    private volatile OtlpHttpSpanExporter delegate;

    public RuntimeSwitchableOtlpSpanExporter(ConfigSource configSource, String springEndpoint) {
        this.configSource = configSource;
        this.springEndpoint = springEndpoint == null ? "" : springEndpoint.trim();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        Boolean forced = readSwitch();
        String endpoint = resolveEndpoint();
        Decision decision = decide(forced, endpoint);
        switch (decision) {
            case DROP_DISABLED:
                droppedDisabledBatches.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            case DROP_NO_ENDPOINT:
                droppedNoEndpointBatches.incrementAndGet();
                if (Boolean.TRUE.equals(forced)) {
                    warnThrottled("追踪被显式开启（{} = true），但既没有 ops.observability.otlp_endpoint "
                                    + "也没有 Spring 属性 tracing.otlp.endpoint，跨度将全部丢弃。",
                            SystemConfigService.OPS_OBSERVABILITY_TRACING_ENABLED);
                }
                return CompletableResultCode.ofSuccess();
            case SEND:
            default:
                sentBatches.incrementAndGet();
                return delegateFor(endpoint).export(spans);
        }
    }

    @Override
    public CompletableResultCode flush() {
        OtlpHttpSpanExporter current = delegate;
        return current == null ? CompletableResultCode.ofSuccess() : current.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        OtlpHttpSpanExporter current;
        synchronized (delegateLock) {
            current = delegate;
            delegate = null;
            delegateEndpoint = null;
        }
        return current == null ? CompletableResultCode.ofSuccess() : current.shutdown();
    }

    // ── 判定逻辑（纯函数，单测直接打这两条） ─────────────────────────────────

    /**
     * 解析开关原始值。三态语义：
     * {@code true}/{@code 1} ⇒ 强制开；{@code false}/{@code 0} ⇒ 强制关；
     * null/空白/**非法值** ⇒ 跟随（非法值刻意不按「关」处理，避免写错一个字符就静默失去追踪）。
     */
    static Boolean parseSwitch(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 原始值是否非空却不是合法三态取值（用于限频告警，不影响判定）。 */
    static boolean isIllegalSwitchValue(String raw) {
        return raw != null && !raw.isBlank() && parseSwitch(raw) == null;
    }

    /** 决策：显式关 > 无端点 > 外发。 */
    static Decision decide(Boolean forced, String endpoint) {
        if (Boolean.FALSE.equals(forced)) {
            return Decision.DROP_DISABLED;
        }
        if (endpoint == null || endpoint.isBlank()) {
            return Decision.DROP_NO_ENDPOINT;
        }
        return Decision.SEND;
    }

    // ── 供测试与运维观测的只读计数 ──────────────────────────────────────────

    /** 实际外发的批次数。 */
    public long sentBatches() {
        return sentBatches.get();
    }

    /** 因运行期开关关闭而丢弃的批次数。 */
    public long droppedDisabledBatches() {
        return droppedDisabledBatches.get();
    }

    /** 因无端点而丢弃的批次数。 */
    public long droppedNoEndpointBatches() {
        return droppedNoEndpointBatches.get();
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private Boolean readSwitch() {
        String raw;
        try {
            raw = configSource.rawTracingSwitch();
        } catch (RuntimeException e) {
            warnThrottled("读取追踪运行期开关失败，本次按「丢弃」处理: {}", e.toString());
            return Boolean.FALSE;
        }
        if (isIllegalSwitchValue(raw)) {
            warnThrottled("{} 取值非法（{}），按「跟随」处理；合法值为 true/false/1/0 或留空",
                    SystemConfigService.OPS_OBSERVABILITY_TRACING_ENABLED, raw.trim());
        }
        return parseSwitch(raw);
    }

    /** 包级可见（非 private）：便于单测直测「运行期端点优先于启动期端点」的优先级。 */
    String resolveEndpoint() {
        String database = null;
        try {
            database = configSource.databaseEndpoint();
        } catch (RuntimeException e) {
            warnThrottled("读取追踪运行期端点失败，本次回退启动期端点: {}", e.toString());
        }
        if (database != null && !database.isBlank()) {
            return database.trim();
        }
        return springEndpoint;
    }

    private OtlpHttpSpanExporter delegateFor(String endpoint) {
        OtlpHttpSpanExporter current = delegate;
        if (current != null && endpoint.equals(delegateEndpoint)) {
            return current;
        }
        synchronized (delegateLock) {
            if (delegate != null && endpoint.equals(delegateEndpoint)) {
                return delegate;
            }
            OtlpHttpSpanExporter previous = delegate;
            OtlpHttpSpanExporter rebuilt = OtlpHttpSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .setTimeout(EXPORT_TIMEOUT)
                    .build();
            delegate = rebuilt;
            delegateEndpoint = endpoint;
            if (previous != null) {
                // 端点被运营台改过：旧导出器异步收尾，不阻塞本次导出
                try {
                    previous.shutdown();
                } catch (RuntimeException e) {
                    log.debug("关闭旧 OTLP 导出器时忽略异常: {}", e.toString());
                }
            }
            log.info("OTLP 导出端点已生效: {}", endpoint);
            return rebuilt;
        }
    }

    private void warnThrottled(String template, Object... args) {
        long now = System.currentTimeMillis();
        long last = lastWarnAtMillis.get();
        if (now - last < WARN_INTERVAL_MILLIS) {
            return;
        }
        if (!lastWarnAtMillis.compareAndSet(last, now)) {
            return;
        }
        log.warn(template, args);
    }
}
