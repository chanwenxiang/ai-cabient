package com.aicabinet.trade.config;

import com.aicabinet.trade.service.OpsAlertDispatcher;
import com.aicabinet.trade.service.OpsExceptionService;
import com.aicabinet.trade.service.XxlJobManagedTasks;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * XXL-JOB 执行器「接线自检」（启动期一次）。
 *
 * <p><b>为什么必须有这一层。</b>开启 {@code aicabinet.xxljob.enabled=true} 后，清单内任务的
 * {@code @Scheduled} 会经 {@link com.aicabinet.trade.service.ScheduledTaskService#tryBegin}
 * <b>无条件让位</b>给调度中心 —— 让位条件只判「开关开 + key 在清单」，
 * <b>不校验调度中心是否可达、执行器是否注册得上</b>。于是任意一处配置漂移都会让两边同时失效：
 * 任务永久停跑，{@code scheduled_task.last_run_at} 只是停止推进，<b>没有报错、没有告警、
 * 测试也覆盖不到</b>（测试不跑 docker）。</p>
 *
 * <p><b>已发生过的真实事故。</b>2026-09 实测 {@code XXL_JOB_ADMIN_ADDRESSES} 多带
 * {@code /xxl-job-admin} 前缀（3.x 起 context-path 为 {@code /}）→ 执行器注册吃 404 →
 * {@code xxl_job_registry} 为空 → 11 个资金/对账/分佣任务 100% 派发 "Address Router Fail"，
 * <b>停跑约 19 小时无人发现</b>（审计 §18）。当时启动日志只有一行
 * {@code xxl-job executor init ...}，看不出任何异常 —— 本类补的就是这个洞。</p>
 *
 * <p><b>启动期能自证什么。</b>执行器进程内无法直接得知「我注册成功了吗」：XXL 的注册线程
 * （{@code ExecutorRegistryThread}）吞掉失败只打 warn，且不暴露任何状态查询接口。但「注册得上」
 * 的<b>必要条件</b>全部可在进程内验证，而这恰好覆盖上述真实事故：</p>
 * <ol>
 *   <li>地址是否配置（为空则注册线程根本不启动）；</li>
 *   <li>地址是否可达（连接/超时）；</li>
 *   <li><b>地址的路径部分是否与调度中心 context-path 一致</b> —— 探测
 *       {@code POST {addr}/api/registry} 的响应码即可判定；</li>
 *   <li>accessToken 是否被调度中心接受（生产 {@code docker-compose.production.yml} 用
 *       {@code :?} 强制要求配置，故探测必须带该头，否则会误报）。</li>
 * </ol>
 *
 * <p><b>探测用空 body，零副作用。</b>实测（2026-09-17，admin 3.4.2）：
 * 正确地址 + 空对象 body → {@code 200 {"code":500,"msg":"Illegal Argument."}}；
 * 带 {@code /xxl-job-admin} 前缀 → {@code 404}；GET 方法 → {@code 200 ...HttpMethod not support}。
 * 即「空 body 被调度中心拒绝」本身就是通路正常的证据，且<b>不会真的写入一条注册记录</b>。
 * 判据全部来自实测响应，不是推断。</p>
 *
 * <p><b>不跟随重定向。</b>地址配错时反向代理可能把 404 变成 {@code 302 → 登录页 → 200 + HTML}；
 * 跟随重定向会把「配错」伪装成「通」，故显式 {@code Redirect.NEVER}，并要求 200 响应体含
 * {@code "code"} 字段（JSON 契约）才算通过。</p>
 *
 * <p><b>失败只报不改，绝不阻断启动。</b>与 {@link ProductionStartupValidator} 的严格 profile
 * 闸门语义不同：调度中心故障不该级联拖垮交易服务本身，且让位已生效，服务起不来只会更难排查。
 * 因此 {@link #selfCheck()} <b>吞掉一切异常</b> —— {@code ApplicationReadyEvent} 的监听器抛异常
 * 会向上传播到 {@code SpringApplication.run}，反而把「配置写错」升级成「服务起不来」。</p>
 *
 * <p><b>与超期看护的分工。</b>本自检管「接线通不通」（启动期立即可知，分钟级发现配置漂移）；
 * {@link com.aicabinet.trade.service.ScheduledTaskStaleMonitor} 管「任务实际跑没跑」
 * （持续巡检，按 {@code last_run_at} 判定）。两者互补。</p>
 *
 * <p><b>能力边界（必须知道）。</b>这是<b>一次性</b>自检，指标
 * {@code aicabinet.xxl.job.wiring.ok} 表达的是<b>「本进程启动那一刻的接线状态」</b>，
 * 不是实时状态 —— 启动之后才挂掉的调度中心由超期看护兜底（故该 Gauge 的告警描述里已写明语义）。
 * 另外，「appname 是否与调度中心执行器管理里登记的一致」在进程内不可知（需读调度中心库），
 * 由 {@code scripts/check-xxl-job-wiring.mjs} 静态校验（seed 的 {@code xxl_job_group.app_name}）；此处
 * 只校验其非空。</p>
 */
@Component
@ConditionalOnProperty(prefix = "aicabinet.xxljob", name = "enabled", havingValue = "true")
public class XxlJobWiringSelfCheck {

    private static final Logger log = LoggerFactory.getLogger(XxlJobWiringSelfCheck.class);

    /** 告警类型；沿用 ops_exception 的 type 语义。 */
    public static final String ALERT_TYPE = "XXL_JOB_WIRING_BROKEN";
    /** 系统级异常没有业务引用，去重键落到 GLOBAL（见 OpsExceptionService#first）。 */
    private static final String GLOBAL_BUSINESS_KEY = "GLOBAL";

    /** 调度中心注册接口；用空 body 探测（会被拒，但拒绝本身即证明通路）。 */
    private static final String REGISTRY_PATH = "/api/registry";
    /** XXL-JOB 约定的 token 头（对应 XxlJobRemotingUtil.XXL_JOB_ACCESS_TOKEN）。 */
    private static final String TOKEN_HEADER = "XXL-JOB-ACCESS-TOKEN";

    /**
     * 探测尝试次数（首次 + 重试）。
     *
     * <p><b>为什么必须重试。</b>compose 里 trade-service 对 xxl-job-admin 只声明了
     * {@code condition: service_started}（容器起来 ≠ Spring Boot 就绪 —— admin 还要等 MySQL 并完成
     * 自身初始化），而 admin 在 {@code docker-compose.full.yml} 里没有 healthcheck。所以「admin 尚未
     * 监听 8080」是一个**正常会出现的启动瞬间**：只探一次就会把这个瞬态报成 CRITICAL，制造一次虚假告警。
     * 虚假告警会连带削弱真告警的可信度（假红与假绿同病两面），故宁可多等几秒。</p>
     */
    private static final int PROBE_ATTEMPTS = 3;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            // 不跟随重定向：反代可能把 404 变成「302 → 登录页 → 200 + HTML」，
            // 跟随重定向会把「配错」伪装成「通」。
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 探测失败原因。文案面向排障：现象 + 该怎么改。 */
    enum Fault {
        ADDRESS_MISSING("调度中心地址为空，执行器不会启动注册线程，托管任务永久停跑",
                "设置 XXL_JOB_ADMIN_ADDRESSES（例如 http://xxl-job-admin:8080）"),
        ADDRESS_INVALID("地址不是合法 URL",
                "检查 XXL_JOB_ADMIN_ADDRESSES 是否含协议与端口"),
        APPNAME_MISSING("执行器 appname 为空，调度中心会拒绝注册",
                "设置 XXL_JOB_EXECUTOR_APPNAME，并确保与调度中心执行器管理里的 appname 一致"),
        UNREACHABLE("调度中心不可达",
                "确认 xxl-job-admin 已启动且网络可达（容器内用服务名，不是 localhost）"),
        PATH_PREFIX_WRONG("调度中心返回 404：地址带了多余的路径前缀",
                "xxl-job-admin 3.x 起 context-path 为 \"/\"，XXL_JOB_ADMIN_ADDRESSES 不能带 /xxl-job-admin"),
        TOKEN_REJECTED("调度中心拒绝 accessToken",
                "核对 XXL_JOB_ACCESS_TOKEN 与调度中心 xxl.job.accessToken 是否一致"),
        UNEXPECTED("调度中心响应非预期",
                "确认地址指向的是调度中心本身，而非其它服务、登录页或反向代理错误页");

        private final String text;
        private final String hint;

        Fault(String text, String hint) {
            this.text = text;
            this.hint = hint;
        }

        String text() {
            return text;
        }

        String hint() {
            return hint;
        }
    }

    /** 单地址探测结果；{@code fault == null} 表示通过。 */
    record AddressProbe(String address, Fault fault, String detail) {
        boolean ok() {
            return fault == null;
        }
    }

    /** 探测传输层抽象：单测注入 stub，避免单测依赖真实调度中心（单测不跑 docker）。 */
    @FunctionalInterface
    interface ProbeTransport {
        TransportResult send(String url, String accessToken, Duration timeout) throws Exception;
    }

    record TransportResult(int status, String body) {
    }

    private final OpsExceptionService exceptionService;
    private final OpsAlertDispatcher alertDispatcher;
    private final ProbeTransport transport;
    private final String adminAddresses;
    private final String accessToken;
    private final String appname;
    private final Duration timeout;
    private final Duration retryDelay;

    /**
     * 自检结论；1 = 通过（含「尚未自检」的乐观初值，避免启动过程中误报），0 = 异常。
     * 语义见类注释「能力边界」：它表达的是启动那一刻的状态。
     */
    private final AtomicLong wiringOk = new AtomicLong(1);

    public XxlJobWiringSelfCheck(OpsExceptionService exceptionService,
                                 OpsAlertDispatcher alertDispatcher,
                                 MeterRegistry meterRegistry,
                                 @Value("${xxl.job.admin.addresses:}") String adminAddresses,
                                 @Value("${xxl.job.access-token:}") String accessToken,
                                 @Value("${xxl.job.executor.appname:trade-service}") String appname,
                                 @Value("${aicabinet.xxljob.self-check-timeout-ms:3000}") long timeoutMs,
                                 @Value("${aicabinet.xxljob.self-check-retry-delay-ms:2000}") long retryDelayMs) {
        this(exceptionService, alertDispatcher, adminAddresses, accessToken, appname,
                Duration.ofMillis(Math.max(200, timeoutMs)), Duration.ofMillis(Math.max(0, retryDelayMs)),
                XxlJobWiringSelfCheck::httpProbe, meterRegistry);
    }

    /** 包内可见：单测注入 stub 传输层与受控配置，不依赖 Spring 上下文。 */
    XxlJobWiringSelfCheck(OpsExceptionService exceptionService,
                          OpsAlertDispatcher alertDispatcher,
                          String adminAddresses,
                          String accessToken,
                          String appname,
                          Duration timeout,
                          Duration retryDelay,
                          ProbeTransport transport,
                          MeterRegistry meterRegistry) {
        this.exceptionService = exceptionService;
        this.alertDispatcher = alertDispatcher;
        this.adminAddresses = adminAddresses;
        this.accessToken = accessToken;
        this.appname = appname;
        this.timeout = timeout;
        this.retryDelay = retryDelay;
        this.transport = transport;
        Gauge.builder("aicabinet.xxl.job.wiring.ok", wiringOk, AtomicLong::doubleValue)
                .description("XXL-JOB 执行器接线自检结果（1=启动期自检通过，0=异常；"
                        + "仅代表本进程启动那一刻的状态，运行中失效由超期看护兜底）")
                .register(meterRegistry);
    }

    /**
     * 启动期自检入口。失败只报不改 —— 见类注释「失败只报不改」。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void selfCheck() {
        try {
            List<AddressProbe> probes = checkWithRetry(adminAddresses, accessToken, appname);
            List<AddressProbe> faults = faultsOf(probes);
            if (faults.isEmpty()) {
                wiringOk.set(1);
                log.info("xxl-job executor wiring self-check PASSED: admin={}, appname={}, 托管任务 {} 个",
                        adminAddresses, appname, XxlJobManagedTasks.KEYS.size());
                // 接线恢复后自动关闭历史异常单，避免运维台上留一条永不消失的红点。
                exceptionService.resolveSystem(ALERT_TYPE, GLOBAL_BUSINESS_KEY, "XXL-JOB 执行器接线已恢复");
                return;
            }
            wiringOk.set(0);
            String detail = detailOf(faults);
            log.error("xxl-job executor wiring self-check FAILED: 托管任务 {} 个将不会被任何一侧触发"
                            + "（内置 @Scheduled 已让位，调度中心又接不到）\n{}",
                    XxlJobManagedTasks.KEYS.size(), detail);
            exceptionService.report(ALERT_TYPE, "CRITICAL",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(null, null, null, null),
                    "XXL-JOB 执行器接线异常（" + faults.size() + " 个地址）", detail);
            alertDispatcher.send(ALERT_TYPE, "XXL-JOB 执行器接线异常",
                    detail + "\n影响：让位已生效，托管任务既不会被 Spring 也不会被调度中心触发",
                    Map.of("adminAddresses", String.valueOf(adminAddresses),
                            "appname", String.valueOf(appname),
                            "badAddresses", faults.stream().map(AddressProbe::address).toList()));
        } catch (Exception e) {
            // 自检绝不能拖垮启动：ApplicationReadyEvent 的监听器抛异常会向上传播到
            // SpringApplication.run，把「调度中心配置写错」升级成「交易服务起不来」。
            log.error("xxl-job executor wiring self-check 自身异常（已忽略，不影响启动）", e);
        }
    }

    /** 按实例配置自检。 */
    List<AddressProbe> check() {
        return check(adminAddresses, accessToken, appname);
    }

    /** 只读探测，便于单测直接断言（不触发告警与指标刷新）。 */
    List<AddressProbe> check(String rawAddresses, String token, String appnameValue) {
        if (appnameValue == null || appnameValue.isBlank()) {
            return List.of(new AddressProbe("(appname)", Fault.APPNAME_MISSING, "(未配置)"));
        }
        List<String> addresses = splitAddresses(rawAddresses);
        if (addresses.isEmpty()) {
            return List.of(new AddressProbe("(未配置)", Fault.ADDRESS_MISSING, "(未配置)"));
        }
        List<AddressProbe> probes = new ArrayList<>();
        for (String address : addresses) {
            probes.add(probeOne(address, token));
        }
        return List.copyOf(probes);
    }

    /**
     * 带重试的探测。只为覆盖「调度中心比本服务晚就绪」这个**正常会出现的启动瞬间**
     * （原因见 {@link #PROBE_ATTEMPTS}）—— 没有它，一次瞬态就会变成一条虚假 CRITICAL。
     * 间隔可配，单测传 0 以保持快速。
     */
    List<AddressProbe> checkWithRetry(String rawAddresses, String token, String appnameValue) {
        List<AddressProbe> probes = check(rawAddresses, token, appnameValue);
        for (int attempt = 2; attempt <= PROBE_ATTEMPTS && hasFault(probes); attempt++) {
            String summary = faultsOf(probes).stream()
                    .map(p -> p.address() + "=" + p.fault())
                    .collect(Collectors.joining("、"));
            log.warn("xxl-job executor wiring self-check 第 {} 次探测未通过（{}），{} ms 后重试",
                    attempt - 1, summary, retryDelay.toMillis());
            try {
                Thread.sleep(retryDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return probes;
            }
            probes = check(rawAddresses, token, appnameValue);
        }
        return probes;
    }

    private static boolean hasFault(List<AddressProbe> probes) {
        return probes.stream().anyMatch(p -> !p.ok());
    }

    private static List<AddressProbe> faultsOf(List<AddressProbe> probes) {
        return probes.stream().filter(p -> !p.ok()).toList();
    }

    private AddressProbe probeOne(String address, String token) {
        String url = registryUrl(address);
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException e) {
            return new AddressProbe(address, Fault.ADDRESS_INVALID, String.valueOf(e.getMessage()));
        }
        // 必须是「带主机名的 http(s) 绝对地址」。漏写协议（`xxl-job-admin:8080`）时 URI 会把它当成
        // 合法的相对引用而不报错，一直要到 HttpRequest 构造时才失败 —— 那时错误信息已看不出是配置问题。
        String scheme = parsed.getScheme();
        if (scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || parsed.getHost() == null || parsed.getHost().isBlank()) {
            return new AddressProbe(address, Fault.ADDRESS_INVALID,
                    "不是带主机名的 http(s) 绝对地址：" + url);
        }
        try {
            TransportResult result = transport.send(url, token, timeout);
            int status = result.status();
            if (status == 404) {
                return new AddressProbe(address, Fault.PATH_PREFIX_WRONG, "HTTP 404");
            }
            if (status == 401 || status == 403) {
                return new AddressProbe(address, Fault.TOKEN_REJECTED, "HTTP " + status);
            }
            // 实测：地址与 token 都正确时，空 body 会被调度中心以「参数非法」拒绝。
            // 故 200 且响应体是带 code 字段的 JSON（而非 HTML）即证明「接口在、方法对、token 通过」。
            if (status == 200 && result.body() != null && result.body().contains("\"code\"")) {
                return new AddressProbe(address, null, "HTTP 200");
            }
            return new AddressProbe(address, Fault.UNEXPECTED,
                    "HTTP " + status + " body=" + abbreviate(result.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AddressProbe(address, Fault.UNREACHABLE, "探测被中断");
        } catch (Exception e) {
            return new AddressProbe(address, Fault.UNREACHABLE,
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    /** 拼注册接口地址；容忍配置里的尾部斜杠与前后空格。 */
    private static String registryUrl(String address) {
        String base = address.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + REGISTRY_PATH;
    }

    /** 逗号分隔的多地址，按顺序去重（重复配置不值得报两次）。 */
    private static List<String> splitAddresses(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                unique.add(value);
            }
        }
        return List.copyOf(unique);
    }

    private static String detailOf(List<AddressProbe> faults) {
        StringBuilder sb = new StringBuilder();
        for (AddressProbe probe : faults) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(probe.address()).append("：").append(probe.fault().text())
                    .append("（").append(probe.detail()).append("）")
                    .append(" → ").append(probe.fault().hint());
        }
        return sb.toString();
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "null";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    private static TransportResult httpProbe(String url, String accessToken, Duration timeout) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header(TOKEN_HEADER, accessToken);
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new TransportResult(response.statusCode(), response.body());
    }
}
