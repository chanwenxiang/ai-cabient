package com.aicabinet.trade.config;

import com.aicabinet.trade.service.OpsAlertDispatcher;
import com.aicabinet.trade.service.OpsExceptionService;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「{@code @Scheduled} 并发 × 连接池上限」容量自检（启动期一次）。
 *
 * <p><b>为什么必须有这一层。</b>{@code scripts/check-scheduling-vs-db-pool.mjs} 只能在 CI 里校验
 * <b>声明值</b>（含 {@code ${VAR:默认}} 的默认值）。部署期一旦用环境变量把连接池改小、把调度并发调大，
 * 静态门禁看不到 —— 而那份门禁自己的注释里就把这条边界写成了「需要启动期自检兜底（尚未实现）」。
 * 本类补的正是这一侧：读<b>运行进程里真正生效的值</b>，判据与静态门禁同源（并发 ≤ 连接池），
 * 但取值口径从「YAML 声明」换成「运行时对象 / 已解析属性」。</p>
 *
 * <p><b>已发生过的真实事故（本判据的来历）。</b>2026-09-17 CI run 35198060847：主
 * {@code application.yml} 把 {@code spring.task.scheduling.pool.size} 从 Spring 默认的 1 放大到 8
 * （本意是本地兜底任务互不阻塞），测试 profile 没声明该键于是<b>通过继承</b>一并拿到 8，而测试库
 * hikari 只有 5。trade-service 有十多个带 {@code fixedRate} 且<b>无 {@code initialDelay}</b> 的
 * {@code @Scheduled}，上下文启动瞬间同时开跑 ⇒ 连接池打满 ⇒ E2E 上下文 30s 拿不到连接直接加载失败
 * （{@code AdminE2ETest} 5/5 ERROR，日志 {@code total=5, active=5, idle=0, waiting=8}），
 * 全仓单测从 913/0 退化为 915/5。</p>
 *
 * <p><b>取值为什么必须走「有效值」而不是「声明值」。</b>同一个键可能有三个来源互相覆盖
 * （YAML 默认值 → profile → 环境变量 → 代码里 {@code setPoolSize}），最后<b>谁说了算</b>只有运行时
 * 才知道。故取值的优先级是：<b>运行中的对象</b>（{@link ThreadPoolTaskScheduler} 的实际核心线程数、
 * {@link HikariDataSource} 的实际 {@code maximumPoolSize}）→ 拿不到对象/未初始化时退回
 * <b>已解析的属性值</b>（{@code @Value} 注入，同样已含环境变量覆盖）。结论里会<b>如实标注每个数字
 * 取自哪一侧</b> —— 「两个数字都对但都取自声明」正是这份自检要消灭的形态。</p>
 *
 * <p><b>判据是必要条件，不是充分条件。</b>{@code 并发 ≤ 连接池} 只保证「池子容得下最大并发」，
 * 并不保证任务不长期持锁握连接（那要靠 {@code leak-detection-threshold} 与任务本身守规矩）。
 * 因此本自检失败 =「已存在容量风险」，通过 ≠「调度一定健康」。</p>
 *
 * <p><b>失败只报不改，绝不阻断启动</b>（与 {@link XxlJobWiringSelfCheck} 同一语义）：
 * 容量配置偏紧不该级联成「交易服务起不来」，那只会更难排查；且 {@code ApplicationReadyEvent}
 * 的监听器抛异常会向上传播到 {@code SpringApplication.run}。故 {@link #selfCheck()} 吞掉一切异常。</p>
 *
 * <p><b>能力边界（必须知道）。</b>一次性自检：指标 {@code aicabinet.scheduling.pool.capacity.ok}
 * 表达的是<b>本进程启动那一刻</b>的容量关系，不是实时值 —— 运行中若有人改池，本类不会重算。
 * 另外「池子够大但任务真的在抢」属于运行期现象，由连接池指标（{@code hikari_connections_*}）覆盖。</p>
 *
 * <p><b>与静态门禁的分工。</b>{@code scripts/check-scheduling-vs-db-pool.mjs}（CI，判 YAML 声明值与
 * profile 继承）管「提交进仓库的配置自洽」；本类（运行期，判有效值）管「部署时被环境变量改过的配置」。
 * 两者判据同源、取值口径不同，缺一侧就会留一个盲区。</p>
 */
@Component
public class SchedulingPoolCapacitySelfCheck {

    private static final Logger log = LoggerFactory.getLogger(SchedulingPoolCapacitySelfCheck.class);

    /** 告警类型；沿用 ops_exception 的 type 语义。 */
    public static final String ALERT_TYPE = "SCHEDULING_POOL_CAPACITY_RISK";
    /** 系统级异常没有业务引用，去重键落到 GLOBAL（见 OpsExceptionService#first）。 */
    private static final String GLOBAL_BUSINESS_KEY = "GLOBAL";

    /** Micrometer 指标名；落到 Prometheus 是 {@code aicabinet_scheduling_pool_capacity_ok}。 */
    public static final String GAUGE_NAME = "aicabinet.scheduling.pool.capacity.ok";

    private final OpsExceptionService exceptionService;
    private final OpsAlertDispatcher alertDispatcher;
    private final CapacityProbe probe;

    /**
     * 自检结论；1 = 通过（含「尚未自检」的乐观初值，避免启动过程中误报），0 = 容量风险。
     * 语义见类注释「能力边界」。
     */
    private final AtomicLong capacityOk = new AtomicLong(1);

    /**
     * 一次「容量读数」：并发度、连接池上限，以及各自的取值来源。
     *
     * @param concurrency       {@code @Scheduled} 可同时占用的线程数上限
     * @param concurrencySource 该数字取自哪里（面向排障，必须能在结论里读出来）
     * @param pool              JDBC 连接池上限
     * @param poolSource        该数字取自哪里
     */
    record Capacity(int concurrency, String concurrencySource, int pool, String poolSource) {

        boolean overCapacity() {
            return concurrency > pool;
        }
    }

    /** 读数来源抽象：单测注入 stub，避免单测依赖真实数据源与调度器（单测不跑 docker）。 */
    @FunctionalInterface
    interface CapacityProbe {
        Capacity read();
    }

    /**
     * Spring 装配用的构造器。
     *
     * <p><b>这里的 {@code @Autowired} 不能删。</b>本类刻意有两个构造器（下面那个包内可见的供单测注入
     * stub）。Spring 只在「<b>有且仅有一个</b>构造器」时才免注解自动选择；一旦存在多个且都没标注，
     * 它就退化为去找<b>无参构造器</b>，启动期直接 {@code BeanInstantiationException:
     * No default constructor found} —— 服务起不来。这正是 {@link XxlJobWiringSelfCheck} 踩过的坑
     * （2026-09-18 首次以 {@code XXL_JOB_ENABLED=true} 重启才爆）。</p>
     */
    @Autowired
    public SchedulingPoolCapacitySelfCheck(OpsExceptionService exceptionService,
                                           OpsAlertDispatcher alertDispatcher,
                                           MeterRegistry meterRegistry,
                                           DataSource dataSource,
                                           ObjectProvider<ThreadPoolTaskScheduler> schedulerProvider,
                                           @Value("${spring.task.scheduling.pool.size:1}")
                                           int schedulingPoolProperty,
                                           @Value("${spring.datasource.hikari.maximum-pool-size:10}")
                                           int datasourcePoolProperty) {
        this(exceptionService, alertDispatcher, meterRegistry,
                () -> readCapacity(dataSource, schedulerProvider.getIfAvailable(),
                        schedulingPoolProperty, datasourcePoolProperty));
    }

    /** 包内可见：单测注入读数，不依赖 Spring 上下文与真实连接池。 */
    SchedulingPoolCapacitySelfCheck(OpsExceptionService exceptionService,
                                    OpsAlertDispatcher alertDispatcher,
                                    MeterRegistry meterRegistry,
                                    CapacityProbe probe) {
        this.exceptionService = exceptionService;
        this.alertDispatcher = alertDispatcher;
        this.probe = probe;
        Gauge.builder(GAUGE_NAME, capacityOk, AtomicLong::doubleValue)
                .description("定时任务并发 × 连接池容量自检结果（1=启动期自检通过，0=并发大于连接池；"
                        + "仅代表本进程启动那一刻的取值）")
                .register(meterRegistry);
    }

    /**
     * 启动期自检入口。失败只报不改 —— 见类注释「失败只报不改」。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void selfCheck() {
        try {
            Capacity capacity = probe.read();
            if (!capacity.overCapacity()) {
                capacityOk.set(1);
                log.info("scheduling/datasource capacity self-check PASSED: "
                                + "@Scheduled 并发 {}（{}） ≤ 连接池 {}（{}）",
                        capacity.concurrency(), capacity.concurrencySource(),
                        capacity.pool(), capacity.poolSource());
                // 容量恢复后自动关闭历史异常单，避免运维台上留一条永不消失的红点。
                exceptionService.resolveSystem(ALERT_TYPE, GLOBAL_BUSINESS_KEY, "定时任务并发与连接池容量已恢复一致");
                return;
            }
            capacityOk.set(0);
            String detail = detailOf(capacity);
            log.error("scheduling/datasource capacity self-check FAILED: @Scheduled 并发 {} > 连接池 {} —— "
                            + "上下文启动瞬间多个定时任务会同时抢连接\n{}",
                    capacity.concurrency(), capacity.pool(), detail);
            exceptionService.report(ALERT_TYPE, "CRITICAL",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(null, null, null, null),
                    "定时任务并发超出连接池上限（" + capacity.concurrency() + " > " + capacity.pool() + "）",
                    detail);
            alertDispatcher.send(ALERT_TYPE, "定时任务并发超出连接池上限",
                    detail + "\n影响：多个 @Scheduled（多为无 initialDelay）会在启动瞬间同时抢连接，"
                            + "连接池打满后 E2E/启动期上下文可能直接加载失败，运行期请求也会排队等连接",
                    refsOf(capacity));
        } catch (Exception e) {
            // 自检绝不能拖垮启动：ApplicationReadyEvent 的监听器抛异常会向上传播到
            // SpringApplication.run，把「容量配置偏紧」升级成「交易服务起不来」。
            log.error("scheduling/datasource capacity self-check 自身异常（已忽略，不影响启动）", e);
        }
    }

    /** 只读读数，便于单测直接断言（不触发告警与指标刷新）。 */
    Capacity check() {
        return probe.read();
    }

    /**
     * 读「当前生效」的容量。
     *
     * <p>两侧都遵循同一优先级：<b>运行中的对象 → 已解析的属性值</b>。之所以不能只读属性：
     * 代码里显式 {@code setPoolSize}/{@code setMaximumPoolSize} 会绕过属性；之所以不能只读对象：
     * 调度器可能尚未初始化（{@code getScheduledThreadPoolExecutor()} 会抛
     * {@code IllegalStateException}），此时属性值是唯一的线索。</p>
     *
     * <p>包内可见是为了让单测直接钉住「取自哪一侧」这条语义 —— 这正是「判据按有效值判」的落点，
     * 只靠注入 stub 测不到。</p>
     */
    static Capacity readCapacity(DataSource dataSource,
                                 ThreadPoolTaskScheduler scheduler,
                                 int schedulingPoolProperty,
                                 int datasourcePoolProperty) {
        int concurrency = schedulingPoolProperty > 0 ? schedulingPoolProperty : 1;
        String concurrencySource = "属性 spring.task.scheduling.pool.size（已含环境变量覆盖）";
        if (scheduler != null) {
            try {
                // 用**核心线程数**而非 getPoolSize()：ScheduledThreadPoolExecutor 队列无界，
                // max 永不生效 ⇒ 能同时跑几个任务由 corePoolSize 决定；而 getPoolSize() 返回的是
                // 「当前已有几个线程」，启动瞬间可能是 0，拿它当并发上限会得出假绿。
                int core = scheduler.getScheduledThreadPoolExecutor().getCorePoolSize();
                if (core > 0) {
                    concurrency = core;
                    concurrencySource = "运行中的 ThreadPoolTaskScheduler 核心线程数";
                }
            } catch (Exception ignored) {
                // 未初始化 / 已关闭：保留属性值，结论里会如实标注来源
            }
        }

        int pool = datasourcePoolProperty > 0 ? datasourcePoolProperty : 10;
        String poolSource = "属性 spring.datasource.hikari.maximum-pool-size（已含环境变量覆盖）";
        HikariDataSource hikari = unwrapHikari(dataSource);
        if (hikari != null) {
            try {
                int max = hikari.getMaximumPoolSize();
                if (max > 0) {
                    pool = max;
                    poolSource = "运行中的 HikariDataSource 有效上限";
                }
            } catch (Exception ignored) {
                // 已关闭：保留属性值
            }
        }
        return new Capacity(concurrency, concurrencySource, pool, poolSource);
    }

    /** 取底层 Hikari 池；被代理包过时用 {@link DataSource#unwrap} 穿透。 */
    private static HikariDataSource unwrapHikari(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari;
        }
        try {
            return dataSource.unwrap(HikariDataSource.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 排障文案：现象 + 每个数字的来源 + 该怎么改。 */
    private static String detailOf(Capacity capacity) {
        StringBuilder sb = new StringBuilder();
        sb.append("@Scheduled 并发 = ").append(capacity.concurrency())
                .append("（取自：").append(capacity.concurrencySource()).append("）\n");
        sb.append("连接池上限 = ").append(capacity.pool())
                .append("（取自：").append(capacity.poolSource()).append("）\n");
        sb.append("⇒ 调整任一侧即可：调大 DB_POOL_SIZE，或调小 SPRING_TASK_SCHEDULING_POOL_SIZE；")
                .append("若确实需要更多并发，请同时确认没有任务长期持有连接");
        return sb.toString();
    }

    private static Map<String, Object> refsOf(Capacity capacity) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("schedulingConcurrency", capacity.concurrency());
        refs.put("datasourcePool", capacity.pool());
        refs.put("concurrencySource", capacity.concurrencySource());
        refs.put("poolSource", capacity.poolSource());
        return refs;
    }
}
