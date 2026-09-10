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
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 防回潮：Controller 不得直调 Mapper；订单摘要类须显式 @Deprecated；
 * 写事务内禁止 MQTT 开门指令（须事务外或 @AllowTransactionalRemote）。
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
    static final ArchRule legacyOrderSummaryDtosMustBeDeprecated =
            classes().that().resideInAPackage("com.aicabinet.common.dto..")
                    .and().haveSimpleNameEndingWith("OrderSummaryDto")
                    .should().beAnnotatedWith(Deprecated.class)
                    .because("订单契约已收敛到 OrderReadModel，旧 *OrderSummaryDto 须 @Deprecated");

    @ArchTest
    static final ArchRule legacyOrderDtoMustBeDeprecated =
            classes().that().resideInAPackage("com.aicabinet.common.dto..")
                    .and().haveSimpleName("OrderDto")
                    .should().beAnnotatedWith(Deprecated.class)
                    .because("订单契约已收敛到 OrderReadModel，OrderDto 须 @Deprecated");

    @ArchTest
    static final ArchRule writeTransactionalMustNotRequestOpenDoor =
            classes().that().resideInAPackage("..service..")
                    .should(notCallOpenDoorFromWriteTransactional())
                    .because("写事务内禁止 MQTT 开门；先短事务落 OPENING 再下发指令");

    private static ArchCondition<JavaClass> notCallOpenDoorFromWriteTransactional() {
        return new ArchCondition<>("not call requestOpenDoor* from write @Transactional") {
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
                        if ("requestOpenDoor".equals(name) || "requestOpenDoorOperator".equals(name)) {
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " calls " + call.getDescription()));
                        }
                    }
                }
            }
        };
    }
}
