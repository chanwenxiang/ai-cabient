package com.aicabinet.trade.config;

import com.aicabinet.trade.service.OpsAlertDispatcher;
import com.aicabinet.trade.service.OpsExceptionService;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「{@code @Scheduled} 并发 × 连接池上限」容量自检判据测试。
 *
 * <p>重点有两层，缺一层这份测试就是装饰品：</p>
 * <ol>
 *   <li><b>取值口径</b>（{@link SchedulingPoolCapacitySelfCheck#readCapacity}）：必须取自
 *       <b>运行中的对象</b>，而不是 YAML 里写的那个数 —— 只测「结论对不对」而用注入 stub 喂数字，
 *       就等于把「有效值」这条核心语义跳过了。故这里用**真的**
 *       {@link ThreadPoolTaskScheduler} 与 mock 的 {@link HikariDataSource} 直接钉住优先级。</li>
 *   <li><b>三路可见性 + 不拖垮启动</b>：失败必须同时落指标、落异常单、发告警；且自检自身任何异常
 *       都不得向上传播（{@code ApplicationReadyEvent} 监听器抛异常会让服务起不来）。</li>
 * </ol>
 */
class SchedulingPoolCapacitySelfCheckTest {

    private final OpsExceptionService exceptionService = mock(OpsExceptionService.class);
    private final OpsAlertDispatcher alertDispatcher = mock(OpsAlertDispatcher.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private SchedulingPoolCapacitySelfCheck newSelfCheck(SchedulingPoolCapacitySelfCheck.CapacityProbe probe) {
        return new SchedulingPoolCapacitySelfCheck(exceptionService, alertDispatcher, meterRegistry, probe);
    }

    private static SchedulingPoolCapacitySelfCheck.Capacity capacity(int concurrency, int pool) {
        return new SchedulingPoolCapacitySelfCheck.Capacity(concurrency, "测试-并发", pool, "测试-池");
    }

    private double gaugeValue() {
        return meterRegistry.get(SchedulingPoolCapacitySelfCheck.GAUGE_NAME).gauge().value();
    }

    /** 造一个已初始化的真调度器；用完必须 shutdown，否则线程池会跨测试残留。 */
    private static ThreadPoolTaskScheduler initializedScheduler(int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.initialize();
        return scheduler;
    }

    // ── 取值口径：运行中的对象优先于属性 ──────────────────────────────────────

    @Test
    void readCapacity_prefersRunningSchedulerCoreOverProperty() {
        ThreadPoolTaskScheduler scheduler = initializedScheduler(4);
        try {
            SchedulingPoolCapacitySelfCheck.Capacity read =
                    SchedulingPoolCapacitySelfCheck.readCapacity(null, scheduler, 8, 20);

            assertEquals(4, read.concurrency(), "必须取运行中调度器的实际并发上限，而不是属性里的 8");
            assertTrue(read.concurrencySource().contains("ThreadPoolTaskScheduler"),
                    "来源要如实标注取自运行时对象：" + read.concurrencySource());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void readCapacity_usesCorePoolSizeNotCurrentThreadCount() {
        // 反向陷阱：ScheduledThreadPoolExecutor 队列无界、max 永不生效，能同时跑几个任务由
        // corePoolSize 决定；而 getPoolSize() 给的是「当前已有几个线程」，冷启动时是 0。
        // 若判据取 getPoolSize()，这台配置为 4 的调度器会被读成 0 ⇒ 与任何池子比都判绿（假绿）。
        ThreadPoolTaskScheduler scheduler = initializedScheduler(4);
        try {
            assertEquals(0, scheduler.getPoolSize(), "前置：还没跑过任务，当前线程数应为 0");

            SchedulingPoolCapacitySelfCheck.Capacity read =
                    SchedulingPoolCapacitySelfCheck.readCapacity(null, scheduler, 1, 10);

            assertEquals(4, read.concurrency(), "冷启动时也必须读出 4；读出 0 就是那条假绿");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void readCapacity_prefersRunningHikariPoolSizeOverProperty() {
        HikariDataSource hikari = mock(HikariDataSource.class);
        when(hikari.getMaximumPoolSize()).thenReturn(3);

        SchedulingPoolCapacitySelfCheck.Capacity read =
                SchedulingPoolCapacitySelfCheck.readCapacity(hikari, null, 8, 20);

        assertEquals(3, read.pool(), "必须取运行中连接池的有效上限，而不是属性里的 20");
        assertTrue(read.poolSource().contains("HikariDataSource"),
                "来源要如实标注取自运行时对象：" + read.poolSource());
    }

    @Test
    void readCapacity_whenNoRuntimeObjects_fallsBackToProperties() {
        SchedulingPoolCapacitySelfCheck.Capacity read =
                SchedulingPoolCapacitySelfCheck.readCapacity(null, null, 8, 20);

        assertEquals(8, read.concurrency());
        assertEquals(20, read.pool());
        assertTrue(read.concurrencySource().contains("属性"), read.concurrencySource());
        assertTrue(read.poolSource().contains("属性"), read.poolSource());
    }

    @Test
    void readCapacity_whenSchedulerNotInitialized_fallsBackToProperty() {
        // 未 initialize 的调度器 getScheduledThreadPoolExecutor() 会抛 IllegalStateException；
        // 此时属性值是唯一线索，不能因此让自检自身抛异常。
        SchedulingPoolCapacitySelfCheck.Capacity read =
                SchedulingPoolCapacitySelfCheck.readCapacity(null, new ThreadPoolTaskScheduler(), 8, 20);

        assertEquals(8, read.concurrency(), "未初始化时应退回属性值");
        assertTrue(read.concurrencySource().contains("属性"), read.concurrencySource());
    }

    // ── 判据与三路可见性 ────────────────────────────────────────────────────

    @Test
    void selfCheck_whenWithinCapacity_passesAndSetsGaugeOne() {
        newSelfCheck(() -> capacity(4, 20)).selfCheck();

        assertEquals(1d, gaugeValue());
        verify(exceptionService).resolveSystem(SchedulingPoolCapacitySelfCheck.ALERT_TYPE, "GLOBAL",
                "定时任务并发与连接池容量已恢复一致");
        verify(exceptionService, never()).report(anyString(), anyString(), any(), anyString(), anyString());
        verify(alertDispatcher, never()).send(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void selfCheck_whenConcurrencyEqualsPool_passes() {
        // 边界：判据是「并发 > 池」才失败。相等必须判绿 —— 把 > 写成 >= 会凭空制造一条
        // 永远无法通过的红线（池子为 5、并发为 5 时配置本身是自洽的）。
        newSelfCheck(() -> capacity(5, 5)).selfCheck();

        assertEquals(1d, gaugeValue());
        verify(exceptionService, never()).report(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void selfCheck_whenOverCapacity_reportsCriticalAndSetsGaugeZero() {
        newSelfCheck(() -> capacity(8, 5)).selfCheck();

        assertEquals(0d, gaugeValue(), "容量风险时指标必须置 0，否则告警规则无从消费");
        verify(exceptionService).report(eq(SchedulingPoolCapacitySelfCheck.ALERT_TYPE), eq("CRITICAL"),
                any(), anyString(), anyString());
        verify(exceptionService, never()).resolveSystem(anyString(), anyString(), anyString());

        ArgumentCaptor<Map<String, Object>> refs = ArgumentCaptor.forClass(Map.class);
        verify(alertDispatcher).send(eq(SchedulingPoolCapacitySelfCheck.ALERT_TYPE), anyString(),
                anyString(), refs.capture());
        assertEquals(8, refs.getValue().get("schedulingConcurrency"));
        assertEquals(5, refs.getValue().get("datasourcePool"));
        assertTrue(refs.getValue().containsKey("concurrencySource"),
                "告警里必须带取值来源，否则排障时看不出是配置声明值还是运行时值：" + refs.getValue());
    }

    @Test
    void selfCheck_whenReportingThrows_doesNotPropagate() {
        // ApplicationReadyEvent 的监听器抛异常会向上传播到 SpringApplication.run ——
        // 一旦异常单写入失败就把「容量配置偏紧」升级成「交易服务起不来」。故必须被吞掉。
        doThrow(new IllegalStateException("ops_exception 写入失败"))
                .when(exceptionService).report(anyString(), anyString(), any(), anyString(), anyString());

        newSelfCheck(() -> capacity(8, 5)).selfCheck();

        assertEquals(0d, gaugeValue(), "上报失败也要先落下指标");
    }

    @Test
    void selfCheck_whenAlertDispatchThrows_doesNotPropagate() {
        // 告警通道（钉钉/企微 webhook）不可用同样不该让启动失败 —— 异常单已落库、指标已置 0。
        doThrow(new IllegalStateException("webhook 不可用"))
                .when(alertDispatcher).send(anyString(), anyString(), anyString(), anyMap());

        newSelfCheck(() -> capacity(8, 5)).selfCheck();

        assertEquals(0d, gaugeValue());
    }

    @Test
    void selfCheck_whenProbeThrows_doesNotPropagate() {
        // 读数本身炸掉（例如数据源在此期间被关闭）同样只能记日志，不能拖垮启动；
        // 且此时不应对容量下任何结论 —— 指标保持「尚未判定」的乐观初值，不制造虚假告警。
        newSelfCheck(() -> {
            throw new IllegalStateException("数据源已关闭");
        }).selfCheck();

        assertEquals(1d, gaugeValue(), "无法判定时不得谎报风险（假红与假绿同害）");
        verify(exceptionService, never()).report(anyString(), anyString(), any(), anyString(), anyString());
        verify(alertDispatcher, never()).send(anyString(), anyString(), anyString(), anyMap());
    }
}
