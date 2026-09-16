package com.aicabinet.trade.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 防回潮：Controller 不得直调 Mapper；禁止复活旧订单摘要 DTO；
 * 写事务内禁止 MQTT 开门/运维指令；{@code @Scheduled} 须经 {@code tryBegin}（多实例 Redis 锁），
 * 本机资源回收（{@code CacheConfig#purgeExpiredCache}）显式豁免。
 */
@AnalyzeClasses(
        packages = {"com.aicabinet.trade", "com.aicabinet.common.dto"},
        importOptions = ImportOption.DoNotIncludeTests.class)
class TradeArchitectureTest {

    @ArchTest
    static final ArchRule controllersShouldNotDependOnMappers =
            noClasses().that().resideInAPackage("..api..")
                    .should().dependOnClassesThat().resideInAPackage("..mapper..")
                    .because("Controller 须经 Service，禁止直调 Mapper");

    @ArchTest
    static final ArchRule legacyOrderSummaryDtosMustNotExist =
            noClasses().that().haveSimpleNameEndingWith("OrderSummaryDto")
                    .should().resideInAnyPackage("com.aicabinet.common.dto..")
                    .because("旧 *OrderSummaryDto 已删除；订单契约统一 OrderReadModel")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule legacyOrderDtoMustNotExist =
            noClasses().that().haveSimpleName("OrderDto")
                    .should().resideInAnyPackage("com.aicabinet.common.dto..")
                    .because("旧 OrderDto 已删除；订单契约统一 OrderReadModel")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule writeTransactionalMustNotRequestOpenDoor =
            classes().that().resideInAPackage("..service..")
                    .should(notCallRemoteMqttFromWriteTransactional())
                    .because("写事务内禁止 MQTT（开门/运维指令）；先短事务落库再下发");

    /**
     * 本仓用 {@code ScheduledTaskService.tryBegin}（Redis）做多实例选举，作用等同 ShedLock；
     * 新增 {@code @Scheduled} 必须走 tryBegin，禁止裸跑。
     *
     * <p><b>唯一豁免：本机资源回收</b>（{@code CacheConfig#purgeExpiredCache}）。它清理的是
     * 本进程的 {@code ConcurrentHashMap}，必须<em>每个实例各自执行</em>；借分布式锁会让集群里
     * 只有一台被清、其余实例的内存条目永不回收 —— 锁在这里不是防重复，而是造成漏清理。
     * 豁免是显式写死的（类名 + 方法名），不是「不检查 @Scheduled」这种宽泛放行。</p>
     */
    @ArchTest
    static final ArchRule scheduledMustCallTryBegin =
            methods().that().areAnnotatedWith(Scheduled.class)
                    .and().areDeclaredInClassesThat().resideInAPackage("com.aicabinet.trade..")
                    .should(callTryBegin())
                    .because("多实例下 @Scheduled 须经 ScheduledTaskService.tryBegin（Redis 锁）；本机资源回收除外");

    private static final String PER_INSTANCE_TASK_CLASS = "com.aicabinet.trade.config.CacheConfig";
    private static final String PER_INSTANCE_TASK_METHOD = "purgeExpiredCache";

    private static ArchCondition<JavaMethod> callTryBegin() {
        return new ArchCondition<>("call tryBegin for cluster-safe scheduling") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.getOwner().getName().equals(PER_INSTANCE_TASK_CLASS)
                        && method.getName().equals(PER_INSTANCE_TASK_METHOD)) {
                    events.add(SimpleConditionEvent.satisfied(method,
                            "per-instance local cache purge: distributed lock intentionally not used"));
                    return;
                }
                boolean calls = method.getMethodCallsFromSelf().stream()
                        .anyMatch(c -> "tryBegin".equals(c.getTarget().getName()));
                if (!calls) {
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " is @Scheduled but does not call tryBegin"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notCallRemoteMqttFromWriteTransactional() {
        return new ArchCondition<>("not call MQTT remote from write @Transactional") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaMethod method : javaClass.getMethods()) {
                    var tx = method.tryGetAnnotationOfType(Transactional.class);
                    if (tx.isEmpty() || tx.get().readOnly()) {
                        continue;
                    }
                    if (method.isAnnotatedWith(AllowTransactionalRemote.class)) {
                        continue;
                    }
                    for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                        String name = call.getTarget().getName();
                        if ("requestOpenDoor".equals(name)
                                || "requestOpenDoorOperator".equals(name)
                                || "requestOpsCommand".equals(name)
                                || "requestSetTargetTemp".equals(name)) {
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " calls " + call.getDescription()));
                        }
                    }
                }
            }
        };
    }
}
