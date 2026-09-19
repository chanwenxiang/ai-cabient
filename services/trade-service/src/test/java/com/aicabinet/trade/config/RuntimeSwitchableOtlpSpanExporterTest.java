package com.aicabinet.trade.config;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-6：运行期可开关 OTLP 导出器的判定语义。
 *
 * <p>重点覆盖「假绿」风险最高的几处：<b>三态开关的边界</b>（留空必须跟随、非法值不能静默变关）、
 * <b>端点优先级</b>（运行期压过启动期）、以及<b>配置源抛异常时必须 fail-safe 丢弃而不是把业务带崩</b>。</p>
 */
class RuntimeSwitchableOtlpSpanExporterTest {

    private static final String SPRING_ENDPOINT = "http://spring-fallback:4318/v1/traces";

    private RuntimeSwitchableOtlpSpanExporter exporter;

    @AfterEach
    void tearDown() {
        if (exporter != null) {
            exporter.shutdown();
            exporter = null;
        }
    }

    /** 可编程的配置源；抛异常用 {@link #throwing} 打开。 */
    private static final class FakeSource implements RuntimeSwitchableOtlpSpanExporter.ConfigSource {
        String rawSwitch;
        String endpoint;
        boolean throwing;

        FakeSource(String rawSwitch, String endpoint) {
            this.rawSwitch = rawSwitch;
            this.endpoint = endpoint;
        }

        @Override
        public String rawTracingSwitch() {
            if (throwing) {
                throw new IllegalStateException("db down");
            }
            return rawSwitch;
        }

        @Override
        public String databaseEndpoint() {
            if (throwing) {
                throw new IllegalStateException("db down");
            }
            return endpoint;
        }
    }

    private RuntimeSwitchableOtlpSpanExporter exposer(FakeSource source, String springEndpoint) {
        exporter = new RuntimeSwitchableOtlpSpanExporter(source, springEndpoint);
        return exporter;
    }

    // ── 三态解析 ───────────────────────────────────────────────────────────

    @Test
    void parseSwitch_threeState_blankAndNullMeanFollow() {
        assertNull(RuntimeSwitchableOtlpSpanExporter.parseSwitch(null), "null 必须=跟随");
        assertNull(RuntimeSwitchableOtlpSpanExporter.parseSwitch(""), "空串必须=跟随");
        assertNull(RuntimeSwitchableOtlpSpanExporter.parseSwitch("   "), "空白必须=跟随");
    }

    @Test
    void parseSwitch_acceptsTrueAndOneFalseAndZero() {
        assertEquals(Boolean.TRUE, RuntimeSwitchableOtlpSpanExporter.parseSwitch("true"));
        assertEquals(Boolean.TRUE, RuntimeSwitchableOtlpSpanExporter.parseSwitch("TRUE"));
        assertEquals(Boolean.TRUE, RuntimeSwitchableOtlpSpanExporter.parseSwitch(" 1 "));
        assertEquals(Boolean.FALSE, RuntimeSwitchableOtlpSpanExporter.parseSwitch("false"));
        assertEquals(Boolean.FALSE, RuntimeSwitchableOtlpSpanExporter.parseSwitch("False"));
        assertEquals(Boolean.FALSE, RuntimeSwitchableOtlpSpanExporter.parseSwitch("0"));
    }

    @Test
    void parseSwitch_illegalValueMeansFollowNotOff() {
        // 关键：写错一个字符不能静默失去追踪（那会让人以为「追踪坏了」）
        assertNull(RuntimeSwitchableOtlpSpanExporter.parseSwitch("yes"));
        assertNull(RuntimeSwitchableOtlpSpanExporter.parseSwitch("on"));
        assertTrue(RuntimeSwitchableOtlpSpanExporter.isIllegalSwitchValue("yes"));
        assertTrue(RuntimeSwitchableOtlpSpanExporter.isIllegalSwitchValue("1.0"));
        assertFalse(RuntimeSwitchableOtlpSpanExporter.isIllegalSwitchValue("true"));
        assertFalse(RuntimeSwitchableOtlpSpanExporter.isIllegalSwitchValue(""));
        assertFalse(RuntimeSwitchableOtlpSpanExporter.isIllegalSwitchValue(null));
        assertFalse(RuntimeSwitchableOtlpSpanExporter.isIllegalSwitchValue("0"));
    }

    // ── 决策表 ─────────────────────────────────────────────────────────────

    @Test
    void decide_forcedOffBeatsEndpoint() {
        assertEquals(RuntimeSwitchableOtlpSpanExporter.Decision.DROP_DISABLED,
                RuntimeSwitchableOtlpSpanExporter.decide(Boolean.FALSE, "http://tempo:4318/v1/traces"));
        assertEquals(RuntimeSwitchableOtlpSpanExporter.Decision.DROP_DISABLED,
                RuntimeSwitchableOtlpSpanExporter.decide(Boolean.FALSE, null));
    }

    @Test
    void decide_noEndpointDropsEvenWhenForcedOn() {
        assertEquals(RuntimeSwitchableOtlpSpanExporter.Decision.DROP_NO_ENDPOINT,
                RuntimeSwitchableOtlpSpanExporter.decide(Boolean.TRUE, null));
        assertEquals(RuntimeSwitchableOtlpSpanExporter.Decision.DROP_NO_ENDPOINT,
                RuntimeSwitchableOtlpSpanExporter.decide(Boolean.TRUE, "   "));
        assertEquals(RuntimeSwitchableOtlpSpanExporter.Decision.DROP_NO_ENDPOINT,
                RuntimeSwitchableOtlpSpanExporter.decide(null, ""));
    }

    @Test
    void decide_followWithEndpointSends() {
        assertEquals(RuntimeSwitchableOtlpSpanExporter.Decision.SEND,
                RuntimeSwitchableOtlpSpanExporter.decide(null, "http://tempo:4318/v1/traces"));
        assertEquals(RuntimeSwitchableOtlpSpanExporter.Decision.SEND,
                RuntimeSwitchableOtlpSpanExporter.decide(Boolean.TRUE, "http://tempo:4318/v1/traces"));
    }

    // ── 端点优先级 ─────────────────────────────────────────────────────────

    @Test
    void resolveEndpoint_runtimeValueWinsOverSpringProperty() {
        RuntimeSwitchableOtlpSpanExporter target =
                exposer(new FakeSource(null, "http://tempo:4318/v1/traces"), SPRING_ENDPOINT);
        assertEquals("http://tempo:4318/v1/traces", target.resolveEndpoint());
    }

    @Test
    void resolveEndpoint_fallsBackToSpringPropertyWhenRuntimeBlank() {
        RuntimeSwitchableOtlpSpanExporter target = exposer(new FakeSource(null, "  "), SPRING_ENDPOINT);
        assertEquals(SPRING_ENDPOINT, target.resolveEndpoint());
    }

    @Test
    void resolveEndpoint_emptyWhenBothBlank() {
        RuntimeSwitchableOtlpSpanExporter target = exposer(new FakeSource(null, null), "");
        assertEquals("", target.resolveEndpoint());
    }

    // ── export() 的三种落地行为 ─────────────────────────────────────────────

    @Test
    void export_whenSwitchOff_dropsAndReportsSuccess() {
        RuntimeSwitchableOtlpSpanExporter target =
                exposer(new FakeSource("false", "http://127.0.0.1:1/v1/traces"), SPRING_ENDPOINT);
        assertTrue(target.export(Collections.emptyList()).isSuccess());
        assertEquals(1, target.droppedDisabledBatches());
        assertEquals(0, target.sentBatches());
        assertEquals(0, target.droppedNoEndpointBatches());
    }

    @Test
    void export_whenNoEndpointAnywhere_dropsAndReportsSuccess() {
        RuntimeSwitchableOtlpSpanExporter target = exposer(new FakeSource(null, null), "");
        assertTrue(target.export(Collections.emptyList()).isSuccess());
        assertEquals(1, target.droppedNoEndpointBatches());
        assertEquals(0, target.sentBatches());
    }

    @Test
    void export_whenConfigSourceThrows_failsSafeInsteadOfPropagating() {
        FakeSource broken = new FakeSource(null, null);
        broken.throwing = true;
        RuntimeSwitchableOtlpSpanExporter target = exposer(broken, "");
        // 配置库炸了也必须「不导出、不抛异常」，否则追踪会把业务线程带崩
        assertTrue(target.export(Collections.emptyList()).isSuccess());
        assertEquals(1, target.droppedDisabledBatches());
        assertEquals(0, target.sentBatches());
    }

    @Test
    void export_whenSwitchFollowAndEndpointPresent_sendsThroughOtlpExporter() {
        // 端点指向回环未监听端口：断言只落在「闸门放行」这一事实，不依赖对端可达
        RuntimeSwitchableOtlpSpanExporter target =
                exposer(new FakeSource(null, "http://127.0.0.1:1/v1/traces"), "");
        target.export(Collections.emptyList());
        assertEquals(1, target.sentBatches(), "开关跟随 + 端点非空 ⇒ 必须放行到真实 OTLP 导出器");
        assertEquals(0, target.droppedDisabledBatches());
        assertEquals(0, target.droppedNoEndpointBatches());
    }

    @Test
    void export_switchCanBeFlippedBetweenCalls_withoutRecreatingBean() {
        FakeSource source = new FakeSource("false", "http://127.0.0.1:1/v1/traces");
        RuntimeSwitchableOtlpSpanExporter target = exposer(source, "");

        target.export(Collections.emptyList());
        assertEquals(1, target.droppedDisabledBatches());
        assertEquals(0, target.sentBatches());

        // 运营台把开关打开：同一个 bean 实例，不重启、不重建
        source.rawSwitch = "true";
        target.export(Collections.emptyList());
        assertEquals(1, target.droppedDisabledBatches());
        assertEquals(1, target.sentBatches());

        // 再关回去
        source.rawSwitch = "false";
        target.export(Collections.emptyList());
        assertEquals(2, target.droppedDisabledBatches());
        assertEquals(1, target.sentBatches());
    }

    @Test
    void flushAndShutdown_areSafeBeforeAnyDelegateExists() {
        RuntimeSwitchableOtlpSpanExporter target = exposer(new FakeSource("false", null), "");
        assertTrue(target.flush().isSuccess());
        assertTrue(target.shutdown().isSuccess());
        assertTrue(target.shutdown().isSuccess(), "重复 shutdown 必须幂等");
    }

    @Test
    void implementsOtelSpanExporterContract() {
        RuntimeSwitchableOtlpSpanExporter target = exposer(new FakeSource(null, null), "");
        assertTrue(target instanceof SpanExporter, "必须能被 Boot 的 spanExporters(ObjectProvider<SpanExporter>) 收集");
    }
}
