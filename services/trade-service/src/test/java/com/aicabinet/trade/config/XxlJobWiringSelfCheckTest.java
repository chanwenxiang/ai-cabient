package com.aicabinet.trade.config;

import com.aicabinet.trade.service.OpsAlertDispatcher;
import com.aicabinet.trade.service.OpsExceptionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

/**
 * XXL-JOB 接线自检判据测试。
 *
 * <p>重点不是「能跑通」，而是钉死每条失败原因的判别 —— 判据全部来自 2026-09-17 对
 * xxl-job-admin 3.4.2 的实测响应（404=路径前缀错、401=token 错、200+Json=通路正常），
 * 以及「自检本身绝不能拖垮启动」这条硬约束。</p>
 */
class XxlJobWiringSelfCheckTest {

    /** 实测：地址与 token 都正确时，空 body 会被调度中心以「参数非法」拒绝 —— 这正是通路正常的证据。 */
    private static final String ILLEGAL_ARGUMENT_BODY =
            "{\"code\":500,\"data\":null,\"msg\":\"Illegal Argument.\",\"success\":false}";

    private final OpsExceptionService exceptionService = mock(OpsExceptionService.class);
    private final OpsAlertDispatcher alertDispatcher = mock(OpsAlertDispatcher.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /** 记录最近一次探测的实参，用于断言 token 是否真的被带上（生产强制配置 token，漏传会误报 401）。 */
    private final AtomicReference<String> probedUrl = new AtomicReference<>();
    private final AtomicReference<String> probedToken = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        probedUrl.set(null);
        probedToken.set(null);
    }

    private XxlJobWiringSelfCheck newSelfCheck(String addresses, String token,
                                               XxlJobWiringSelfCheck.ProbeTransport transport) {
        return new XxlJobWiringSelfCheck(exceptionService, alertDispatcher, addresses, token, "trade-service",
                Duration.ofMillis(500), Duration.ZERO, transport, meterRegistry);
    }

    private double gaugeValue() {
        return meterRegistry.get("aicabinet.xxl.job.wiring.ok").gauge().value();
    }

    // ── 配置缺失 ────────────────────────────────────────────────────────────

    @Test
    void check_whenAdminAddressBlank_reportsAddressMissing() {
        List<XxlJobWiringSelfCheck.AddressProbe> probes =
                newSelfCheck("", "", (url, token, timeout) -> {
                    throw new AssertionError("地址为空时不应发起探测");
                }).check();

        assertEquals(1, probes.size());
        assertEquals(XxlJobWiringSelfCheck.Fault.ADDRESS_MISSING, probes.get(0).fault());
    }

    @Test
    void check_whenAppnameBlank_reportsAppnameMissing() {
        // 调度中心按 appname 归属执行器；为空时注册会被拒，故在探测地址之前就拦下。
        List<XxlJobWiringSelfCheck.AddressProbe> probes =
                new XxlJobWiringSelfCheck(exceptionService, alertDispatcher, "http://xxl-job-admin:8080", "",
                        " ", Duration.ofMillis(500), Duration.ZERO,
                        (url, token, timeout) -> new XxlJobWiringSelfCheck.TransportResult(200, ILLEGAL_ARGUMENT_BODY),
                        meterRegistry).check();

        assertEquals(1, probes.size());
        assertEquals(XxlJobWiringSelfCheck.Fault.APPNAME_MISSING, probes.get(0).fault());
    }

    // ── 通路判据（按实测响应校准）────────────────────────────────────────────

    @Test
    void check_whenIllegalArgumentJson_passes() {
        // 正例：实测 200 + {"code":500,"msg":"Illegal Argument."} 即表示接口在、方法对、token 通过。
        List<XxlJobWiringSelfCheck.AddressProbe> probes =
                newSelfCheck("http://xxl-job-admin:8080", "",
                        (url, token, timeout) -> new XxlJobWiringSelfCheck.TransportResult(200, ILLEGAL_ARGUMENT_BODY))
                        .check();

        assertEquals(1, probes.size());
        assertTrue(probes.get(0).ok(), "实测确认的通路形态必须判为通过");
    }

    @Test
    void check_when404_reportsPathPrefixWrong() {
        // §18 真实事故：地址多带 /xxl-job-admin 前缀 → 注册 404 → 注册表为空 → 11 个任务停跑 19 小时。
        List<XxlJobWiringSelfCheck.AddressProbe> probes =
                newSelfCheck("http://xxl-job-admin:8080/xxl-job-admin", "",
                        (url, token, timeout) -> new XxlJobWiringSelfCheck.TransportResult(404, ""))
                        .check();

        assertEquals(XxlJobWiringSelfCheck.Fault.PATH_PREFIX_WRONG, probes.get(0).fault());
    }

    @Test
    void check_when401_reportsTokenRejected() {
        List<XxlJobWiringSelfCheck.AddressProbe> probes =
                newSelfCheck("http://xxl-job-admin:8080", "wrong-token",
                        (url, token, timeout) -> new XxlJobWiringSelfCheck.TransportResult(401, ""))
                        .check();

        assertEquals(XxlJobWiringSelfCheck.Fault.TOKEN_REJECTED, probes.get(0).fault());
    }

    @Test
    void check_whenConnectRefused_reportsUnreachable() {
        List<XxlJobWiringSelfCheck.AddressProbe> probes =
                newSelfCheck("http://xxl-job-admin:8080", "", (url, token, timeout) -> {
                    throw new ConnectException("Connection refused");
                }).check();

        assertEquals(XxlJobWiringSelfCheck.Fault.UNREACHABLE, probes.get(0).fault());
        assertTrue(probes.get(0).detail().contains("ConnectException"),
                "排障信息要带异常类型：" + probes.get(0).detail());
    }

    @Test
    void check_when200HtmlLoginPage_reportsUnexpected() {
        // 反代/门户可能把请求 302 到登录页再返回 200 + HTML。不跟随重定向 + 要求 JSON 契约，
        // 才能避免把「配错地址」误判成「通了」。
        List<XxlJobWiringSelfCheck.AddressProbe> probes =
                newSelfCheck("http://wrong-host:8080", "",
                        (url, token, timeout) -> new XxlJobWiringSelfCheck.TransportResult(
                                200, "<html><body>login</body></html>")).check();

        assertEquals(XxlJobWiringSelfCheck.Fault.UNEXPECTED, probes.get(0).fault());
    }

    @Test
    void check_whenAddressNotUrl_reportsInvalid() {
        List<XxlJobWiringSelfCheck.AddressProbe> probes =
                newSelfCheck("not-a-url", "", (url, token, timeout) -> {
                    throw new AssertionError("非法 URL 不应发起探测");
                }).check();

        assertEquals(XxlJobWiringSelfCheck.Fault.ADDRESS_INVALID, probes.get(0).fault());
    }

    // ── 探测请求构造 ────────────────────────────────────────────────────────

    @Test
    void check_sendsAccessTokenHeaderAndNormalizesTrailingSlash() {
        // 生产 docker-compose.production.yml 用 :? 强制要求 accessToken；探测不带该头会误报 401。
        // 同时容忍配置里的尾部斜杠（否则会拼出 //api/registry）。
        newSelfCheck("http://xxl-job-admin:8080/", "prod-token",
                (url, token, timeout) -> {
                    probedUrl.set(url);
                    probedToken.set(token);
                    return new XxlJobWiringSelfCheck.TransportResult(200, ILLEGAL_ARGUMENT_BODY);
                }).check();

        assertEquals("http://xxl-job-admin:8080/api/registry", probedUrl.get());
        assertEquals("prod-token", probedToken.get());
    }

    @Test
    void check_whenMultipleAddresses_reportsOnlyFaultyOneAndDedupes() {
        List<XxlJobWiringSelfCheck.AddressProbe> probes =
                newSelfCheck("http://a:8080, http://a:8080, http://b:8080", "",
                        (url, token, timeout) -> url.contains("//a:")
                                ? new XxlJobWiringSelfCheck.TransportResult(200, ILLEGAL_ARGUMENT_BODY)
                                : new XxlJobWiringSelfCheck.TransportResult(404, "")).check();

        assertEquals(2, probes.size(), "重复地址只探测一次");
        assertTrue(probes.get(0).ok());
        assertEquals(XxlJobWiringSelfCheck.Fault.PATH_PREFIX_WRONG, probes.get(1).fault());
    }

    // ── 自检结论与三路可见性 ────────────────────────────────────────────────

    @Test
    void selfCheck_whenFailed_reportsAlertAndSetsGaugeZero() {
        XxlJobWiringSelfCheck check = newSelfCheck("http://xxl-job-admin:8080", "",
                (url, token, timeout) -> new XxlJobWiringSelfCheck.TransportResult(404, ""));

        check.selfCheck();

        assertEquals(0d, gaugeValue(), "接线异常时指标必须置 0，否则告警规则无从消费");
        verify(exceptionService).report(eq(XxlJobWiringSelfCheck.ALERT_TYPE), eq("CRITICAL"),
                any(), anyString(), anyString());
        verify(alertDispatcher).send(eq(XxlJobWiringSelfCheck.ALERT_TYPE), anyString(), anyString(), anyMap());
        verify(exceptionService, never()).resolveSystem(anyString(), anyString(), anyString());
    }

    @Test
    void selfCheck_whenPassed_resolvesHistoricalAlertAndSetsGaugeOne() {
        XxlJobWiringSelfCheck check = newSelfCheck("http://xxl-job-admin:8080", "",
                (url, token, timeout) -> new XxlJobWiringSelfCheck.TransportResult(200, ILLEGAL_ARGUMENT_BODY));

        check.selfCheck();

        assertEquals(1d, gaugeValue());
        verify(exceptionService).resolveSystem(XxlJobWiringSelfCheck.ALERT_TYPE, "GLOBAL",
                "XXL-JOB 执行器接线已恢复");
        verify(exceptionService, never()).report(anyString(), anyString(), any(), anyString(), anyString());
        verify(alertDispatcher, never()).send(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void selfCheck_whenReportingThrows_doesNotPropagate() {
        // ApplicationReadyEvent 的监听器抛异常会向上传播到 SpringApplication.run ——
        // 一旦异常单写入失败就把「调度中心配置写错」升级成「交易服务起不来」。故必须被吞掉。
        doThrow(new IllegalStateException("ops_exception 写入失败"))
                .when(exceptionService).report(anyString(), anyString(), any(), anyString(), anyString());
        XxlJobWiringSelfCheck check = newSelfCheck("http://xxl-job-admin:8080", "",
                (url, token, timeout) -> new XxlJobWiringSelfCheck.TransportResult(404, ""));

        check.selfCheck();

        assertEquals(0d, gaugeValue(), "上报失败也要先落下指标");
    }

    @Test
    void selfCheck_whenAlertDispatchThrows_doesNotPropagate() {
        // 告警通道（钉钉/企微 webhook）不可用同样不该让启动失败 —— 异常单已落库，指标已置 0。
        doThrow(new IllegalStateException("webhook 不可用"))
                .when(alertDispatcher).send(anyString(), anyString(), anyString(), anyMap());
        XxlJobWiringSelfCheck check = newSelfCheck("http://xxl-job-admin:8080", "",
                (url, token, timeout) -> new XxlJobWiringSelfCheck.TransportResult(404, ""));

        check.selfCheck();

        assertEquals(0d, gaugeValue());
    }

    // ── 瞬态容错：调度中心可能比本服务晚就绪 ────────────────────────────────

    @Test
    void checkWithRetry_whenAdminNotReadyYet_recoversOnRetry() {
        // compose 里 trade-service 对 xxl-job-admin 只有 condition: service_started（容器起来 ≠
        // Spring Boot 就绪），且 admin 没有 healthcheck ——「admin 尚未监听」是正常启动瞬间，
        // 不能因为探了一次就报 CRITICAL（虚假告警会连带削弱真告警的可信度）。
        AtomicInteger attempts = new AtomicInteger();
        XxlJobWiringSelfCheck check = newSelfCheck("http://xxl-job-admin:8080", "",
                (url, token, timeout) -> attempts.incrementAndGet() < 3
                        ? new XxlJobWiringSelfCheck.TransportResult(503, "")
                        : new XxlJobWiringSelfCheck.TransportResult(200, ILLEGAL_ARGUMENT_BODY));

        check.selfCheck();

        assertEquals(1d, gaugeValue(), "瞬态失败被重试覆盖后必须判为通过");
        assertEquals(3, attempts.get());
        verify(exceptionService, never()).report(anyString(), anyString(), any(), anyString(), anyString());
        // 判为通过后仍会走成功分支关掉历史异常单（幂等空操作），所以这里只能断言 report/alert 未发生；
        // 把 resolveSystem 也写成 never() 会与上一行的「必须判为通过」自相矛盾。
        verify(exceptionService).resolveSystem(XxlJobWiringSelfCheck.ALERT_TYPE, "GLOBAL",
                "XXL-JOB 执行器接线已恢复");
        verify(alertDispatcher, never()).send(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void checkWithRetry_whenAllAttemptsFail_reportsAfterRetriesExhausted() {
        // 反向：持续故障必须报出来，不能因为「反正会重试」而静默。
        AtomicInteger attempts = new AtomicInteger();
        XxlJobWiringSelfCheck check = newSelfCheck("http://xxl-job-admin:8080", "",
                (url, token, timeout) -> {
                    attempts.incrementAndGet();
                    return new XxlJobWiringSelfCheck.TransportResult(404, "");
                });

        check.selfCheck();

        assertEquals(0d, gaugeValue());
        assertEquals(3, attempts.get(), "重试必须真的发生，否则无法区分瞬态与持续故障");
        verify(exceptionService).report(eq(XxlJobWiringSelfCheck.ALERT_TYPE), eq("CRITICAL"),
                any(), anyString(), anyString());
    }
}
