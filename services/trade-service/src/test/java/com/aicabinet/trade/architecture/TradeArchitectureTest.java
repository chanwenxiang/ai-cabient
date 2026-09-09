package com.aicabinet.trade.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 防回潮：Controller 不得直调 Mapper；订单摘要类须显式 @Deprecated（引导走 OrderReadModel）。
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
}
