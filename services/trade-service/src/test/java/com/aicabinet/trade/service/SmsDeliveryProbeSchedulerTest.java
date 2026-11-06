package com.aicabinet.trade.service;

import com.aicabinet.trade.config.SmsDeliveryProbeProperties;
import com.aicabinet.trade.sms.AliyunSmsSender;
import com.aicabinet.trade.sms.AliyunSmsSender.SmsReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 短信到达率巡检（C 档）。这里钉的是「什么时候**不该**说话」和「什么时候必须说话」：
 * 关总闸、凭据缺失、窗口无数据、样本不足都必须**不出外呼也不告警**；只有超阈值才告警，
 * 且同一结果不重复刷屏。假绿（把「查不到」读成「都送达」）是本任务的主缺陷方向。
 */
@ExtendWith(MockitoExtension.class)
class SmsDeliveryProbeSchedulerTest {

    @Mock
    private AliyunSmsSender aliyunSmsSender;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private OpsAlertDispatcher alertDispatcher;
    @Mock
    private OpsExceptionService exceptionService;
    @Mock
    private ScheduledTaskService taskService;

    private SmsDeliveryProbeScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SmsDeliveryProbeScheduler(aliyunSmsSender, jdbcTemplate, systemConfigService,
                alertDispatcher, exceptionService, taskService,
                new SmsDeliveryProbeProperties(true, 20, 3, 5));
    }

    @Test
    void runOnce_paramConfigOff_doesNotEvenQueryTheCloud() {
        when(systemConfigService.getBoolean(SystemConfigService.SMS_DELIVERY_PROBE_ENABLED, true))
                .thenReturn(false);

        String summary = scheduler.runOnce();

        assertTrue(summary.contains("参数配置关闭"), summary);
        verifyNoInteractions(aliyunSmsSender, jdbcTemplate, alertDispatcher, exceptionService);
    }

    /** env 兜底关闭（properties.enabled=false）时文案要区分开，否则运维不知道该去参数配置还是改 env。 */
    @Test
    void runOnce_envFallbackOff_saysSo() {
        SmsDeliveryProbeScheduler off = new SmsDeliveryProbeScheduler(aliyunSmsSender, jdbcTemplate,
                systemConfigService, alertDispatcher, exceptionService, taskService,
                new SmsDeliveryProbeProperties(false, 20, 3, 5));
        when(systemConfigService.getBoolean(SystemConfigService.SMS_DELIVERY_PROBE_ENABLED, false))
                .thenReturn(false);

        assertTrue(off.runOnce().contains("env 兜底关闭"));
        verifyNoInteractions(aliyunSmsSender, jdbcTemplate);
    }

    @Test
    void runOnce_aliyunUnconfigured_isNotReportedAsHealthy() {
        enableProbe();
        when(aliyunSmsSender.isConfigured()).thenReturn(false);

        String summary = scheduler.runOnce();

        assertTrue(summary.contains("凭据未配齐"), summary);
        assertTrue(summary.contains("不判为健康"), summary);
        verifyNoInteractions(jdbcTemplate, alertDispatcher, exceptionService);
    }

    @Test
    void runOnce_windowHasNoSmsRows_isNotReportedAsHealthy() {
        enableProbeWithWindow();
        when(aliyunSmsSender.isConfigured()).thenReturn(true);
        stubTargets(List.of(), List.of());

        String summary = scheduler.runOnce();

        assertTrue(summary.contains("无可查回执"), summary);
        verify(aliyunSmsSender, never()).querySendDetails(anyString(), anyString());
        verifyNoInteractions(alertDispatcher, exceptionService);
    }

    /**
     * 1 条回执里 1 条失败 = 100%，但那是噪声不是结论：样本不足必须**不判定**，
     * 否则把人训练成忽略告警。
     */
    @Test
    void runOnce_belowMinSample_judgesNothing() {
        enableProbeJudging(5);
        when(aliyunSmsSender.isConfigured()).thenReturn(true);
        stubTargets(List.of(pair("13900000001", "20261106")), List.of());
        when(aliyunSmsSender.querySendDetails("13900000001", "20261106"))
                .thenReturn(List.of(row(2, "CONTENT_KEYWORD")));

        String summary = scheduler.runOnce();

        assertTrue(summary.contains("样本不足不判定"), summary);
        verifyNoInteractions(alertDispatcher);
        verify(exceptionService, never()).report(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void runOnce_overThreshold_alertsOnBothChannels() {
        enableProbeJudging(1);
        when(aliyunSmsSender.isConfigured()).thenReturn(true);
        stubTargets(List.of(pair("13900000001", "20261106")), List.of());
        when(aliyunSmsSender.querySendDetails("13900000001", "20261106"))
                .thenReturn(List.of(
                        row(3, "DELIVERED"),
                        row(2, "CONTENT_KEYWORD"),
                        row(2, "CONTENT_KEYWORD"),
                        row(2, "MOBILE_NOT_ON_SERVICE")));

        String summary = scheduler.runOnce();

        assertTrue(summary.contains("失败 3"), summary);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(exceptionService).report(eq("SMS_DELIVERY_FAILURE_HIGH"), eq("HIGH"), any(),
                title.capture(), anyString());
        assertEquals("短信到达率异常（失败 75%）", title.getValue());
        verify(alertDispatcher).send(eq("SMS_DELIVERY_FAILURE_HIGH"), eq("短信到达率异常"),
                anyString(), any(Map.class));
    }

    /** 同一结果每小时都超阈值 ⇒ 只刷屏一次；6 小时后才重述（避免「天天一样」把群打满）。 */
    @Test
    void runOnce_sameFailure_notAlertedTwice() {
        enableProbeJudging(1);
        when(aliyunSmsSender.isConfigured()).thenReturn(true);
        stubTargets(List.of(pair("13900000001", "20261106")), List.of());
        List<SmsReceipt> broken = List.of(
                row(3, "DELIVERED"), row(2, "CONTENT_KEYWORD"), row(2, "CONTENT_KEYWORD"));
        when(aliyunSmsSender.querySendDetails("13900000001", "20261106")).thenReturn(broken);

        scheduler.runOnce();
        scheduler.runOnce();

        verify(alertDispatcher, times(1)).send(anyString(), anyString(), anyString(), any(Map.class));
        verify(exceptionService, times(1)).report(anyString(), anyString(), any(), anyString(), anyString());
    }

    /** 恢复健康后要把上一条系统级异常关掉，否则台账永远挂着一条已经平了的高危。 */
    @Test
    void runOnce_backToHealthy_resolvesPreviousException() {
        enableProbeJudging(1);
        when(aliyunSmsSender.isConfigured()).thenReturn(true);
        stubTargets(List.of(pair("13900000001", "20261106")), List.of());
        when(aliyunSmsSender.querySendDetails("13900000001", "20261106"))
                .thenReturn(List.of(row(3, "DELIVERED"), row(3, "DELIVERED"), row(3, "DELIVERED"),
                        row(3, "DELIVERED"), row(2, "CONTENT_KEYWORD")));
        scheduler.markAlerted("stale-fingerprint");

        scheduler.runOnce();

        verify(exceptionService).resolveSystem("SMS_DELIVERY_FAILURE_HIGH", "GLOBAL",
                "失败占比 20% 未超阈值 20%");
        assertNull(scheduler.currentAlertFingerprint());
    }

    /** 阿里云查询也吃调用配额：单轮组合数必须有硬上界，否则号码多的那天把配额打满、验证码发不出去。 */
    @Test
    void runOnce_capsPairsPerRound() {
        enableProbeJudging(1);
        when(aliyunSmsSender.isConfigured()).thenReturn(true);
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 75; i++) {
            many.add(pair(String.format("1390000%04d", i), "20261106"));
        }
        stubTargets(many, List.of());
        when(aliyunSmsSender.querySendDetails(anyString(), anyString())).thenReturn(List.of());

        String summary = scheduler.runOnce();

        verify(aliyunSmsSender, times(SmsDeliveryProbeScheduler.MAX_PAIRS_PER_ROUND))
                .querySendDetails(anyString(), anyString());
        assertTrue(summary.contains("查询 " + SmsDeliveryProbeScheduler.MAX_PAIRS_PER_ROUND + "/75"), summary);
    }

    @Test
    void probe_leaseHeldByAnotherNode_doesNothing() {
        when(taskService.tryBegin(SmsDeliveryProbeScheduler.TASK_KEY, 600)).thenReturn(false);

        scheduler.probe();

        verifyNoInteractions(systemConfigService, aliyunSmsSender, jdbcTemplate);
        verify(taskService, never()).finish(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void probe_recordsSuccessSummary() {
        when(taskService.tryBegin(SmsDeliveryProbeScheduler.TASK_KEY, 600)).thenReturn(true);
        when(systemConfigService.getBoolean(SystemConfigService.SMS_DELIVERY_PROBE_ENABLED, true))
                .thenReturn(false);

        scheduler.probe();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(taskService).finish(eq(SmsDeliveryProbeScheduler.TASK_KEY), eq("SUCCESS"),
                summary.capture(), anyLong());
        assertTrue(summary.getValue().contains("参数配置关闭"), summary.getValue());
    }

    @Test
    void probe_recordsFailureAndRethrows() {
        when(taskService.tryBegin(SmsDeliveryProbeScheduler.TASK_KEY, 600)).thenReturn(true);
        enableProbeWithWindow();
        when(aliyunSmsSender.isConfigured()).thenReturn(true);
        stubTargets(List.of(pair("13900000001", "20261106")), List.of());
        when(aliyunSmsSender.querySendDetails("13900000001", "20261106"))
                .thenThrow(new IllegalStateException("Aliyun receipt query Code=InvalidAccessKeyId"));

        assertThrows(IllegalStateException.class, () -> scheduler.probe());

        verify(taskService).finish(eq(SmsDeliveryProbeScheduler.TASK_KEY), eq("FAILED"),
                contains("InvalidAccessKeyId"), anyLong());
        verify(alertDispatcher, never()).send(anyString(), anyString(), anyString(), any(Map.class));
    }

    /** 回执查询失败 ≠ 失败占比 0：异常必须冒出来（由 probe 记 FAILED），不能被吞成绿色。 */
    @Test
    void runOnce_receiptQueryError_propagates() {
        enableProbeJudging(1);
        when(aliyunSmsSender.isConfigured()).thenReturn(true);
        stubTargets(List.of(pair("13900000001", "20261106")), List.of());
        when(aliyunSmsSender.querySendDetails("13900000001", "20261106"))
                .thenThrow(new IllegalStateException("Aliyun receipt query HTTP 403"));

        assertThrows(IllegalStateException.class, () -> scheduler.runOnce());
        verifyNoInteractions(alertDispatcher, exceptionService);
    }

    // ── 夹具 ──

    /** 只 stub 到「跑不跑」这一层（严格桩：多 stub 就是 UnnecessaryStubbing 红）。 */
    private void enableProbe() {
        when(systemConfigService.getBoolean(SystemConfigService.SMS_DELIVERY_PROBE_ENABLED, true))
                .thenReturn(true);
    }

    /** 会走到取目标（读窗口）。 */
    private void enableProbeWithWindow() {
        enableProbe();
        when(systemConfigService.getInt(eq(SystemConfigService.SMS_DELIVERY_PROBE_LOOKBACK_DAYS), anyInt()))
                .thenReturn(3);
    }

    /** 会走到判定（读阈值与最小样本）。 */
    private void enableProbeJudging(int minSample) {
        enableProbeWithWindow();
        when(systemConfigService.getInt(eq(SystemConfigService.SMS_DELIVERY_PROBE_FAIL_ALERT_PERCENT), anyInt()))
                .thenReturn(20);
        when(systemConfigService.getInt(eq(SystemConfigService.SMS_DELIVERY_PROBE_MIN_SAMPLE), anyInt()))
                .thenReturn(minSample);
    }

    private static Map<String, Object> pair(String phone, String sendDate) {
        return Map.of("phone_number", phone, "send_date", sendDate);
    }

    private void stubTargets(List<Map<String, Object>> captchaRows, List<Map<String, Object>> notifyRows) {
        when(jdbcTemplate.queryForList(contains("sms_verification_code"), any(Object[].class)))
                .thenReturn(captchaRows);
        when(jdbcTemplate.queryForList(contains("notification_log"), any(Object[].class)))
                .thenReturn(notifyRows);
    }

    private static SmsReceipt row(int sendStatus, String errCode) {
        return new SmsReceipt("139****0001", "2026-11-06 10:00:00",
                sendStatus == 3 ? "2026-11-06 10:00:05" : null, sendStatus, errCode, "SMS_X");
    }
}
