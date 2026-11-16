package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.service.DistributedLockService;
import com.aicabinet.trade.service.ProfitSharingReturnAlertService;
import com.aicabinet.trade.service.ProfitSharingReturnCompensationService;
import com.aicabinet.trade.service.ProfitSharingReturnLegService;
import com.aicabinet.trade.service.RevenueSplitService;
import com.aicabinet.trade.service.MerchantWalletService;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-276：{@code insertSplitNested} 的 NESTED savepoint 必须在**真 PostgreSQL** 上验证。
 *
 * <p>单测里 {@code self} 是 mock，NESTED 传播根本没经过 Spring 事务拦截器。本测试
 * 不加载全应用上下文（避开 fat-jar/MyBatis 夹具问题），只装：
 * 真 PG + DataSourceTransactionManager + @EnableTransactionManagement + 真实
 * {@link RevenueSplitService} Bean（其余依赖 mock；{@code splitRepository.save} 用真实
 * JdbcTemplate 撞主键模拟 DKE）。断言：
 * <ol>
 *   <li>DKE 后外层事务仍可执行 SQL（没有 25P02）；</li>
 *   <li>外层回滚后只保留已提交的旧行；</li>
 *   <li>方法上的 {@code @Transactional} 传播确实是 NESTED（形态钉）。</li>
 * </ol>
 */
@SpringJUnitConfig(NestedSavepointBehaviorTest.Config.class)
class NestedSavepointBehaviorTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        PG.start();
    }

    @AfterAll
    static void stopContainer() {
        PG.stop();
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        }

        @Bean
        JdbcTemplate jdbc(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        PlatformTransactionManager txManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        OrderRevenueSplitMapper splitRepository(JdbcTemplate jdbc) {
            OrderRevenueSplitMapper mapper = Mockito.mock(OrderRevenueSplitMapper.class);
            // 真实 SQL：重复主键 ⇒ 真 DKE（由 Spring 转成 DuplicateKeyException），不是 mock 抛异常。
            Mockito.doAnswer(inv -> {
                jdbc.update("INSERT INTO it_nested (id) VALUES (?)", "dup");
                return inv.getArgument(0);
            }).when(mapper).save(Mockito.any());
            return mapper;
        }

        @Bean
        RevenueSplitService revenueSplitService(OrderRevenueSplitMapper splitRepository) {
            return new RevenueSplitService(
                    splitRepository,
                    Mockito.mock(DeviceInfoMapper.class),
                    Mockito.mock(MerchantMapper.class),
                    Mockito.mock(WeChatProfitSharingService.class),
                    Mockito.mock(MerchantWalletService.class),
                    Mockito.mock(ProfitSharingReturnCompensationService.class),
                    Mockito.mock(ProfitSharingReturnAlertService.class),
                    Mockito.mock(DistributedLockService.class),
                    Mockito.mock(ProfitSharingReturnLegService.class),
                    Mockito.mock(CabinetOrderMapper.class),
                    Mockito.mock(RevenueSplitService.class));
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RevenueSplitService revenueSplitService;
    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        jdbc.execute("DROP TABLE IF EXISTS it_nested");
        jdbc.execute("CREATE TABLE it_nested (id VARCHAR(16) PRIMARY KEY)");
        jdbc.update("INSERT INTO it_nested (id) VALUES ('dup')");
    }

    @Test
    @DisplayName("NESTED 内 DKE：外层事务仍可执行 SQL（无 25P02），回滚后只留旧行")
    void duplicateInsideNested_doesNotAbortOuterTransaction() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        AtomicBoolean outerStillUsable = new AtomicBoolean(false);

        assertThrows(DuplicateKeyException.class, () -> tx.execute(status -> {
            try {
                revenueSplitService.insertSplitNested(new OrderRevenueSplit());
            } catch (DuplicateKeyException e) {
                // 若 NESTED 未生效，PostgreSQL 已把整个事务置 aborted，这句会抛 25P02。
                Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
                outerStillUsable.set(one != null && one == 1);
                throw e;
            }
            return null;
        }));

        assertTrue(outerStillUsable.get(),
                "NESTED savepoint 未生效：DKE 后外层事务仍 aborted（25P02）");
        Integer count = jdbc.queryForObject("SELECT count(*) FROM it_nested", Integer.class);
        assertEquals(1, count, "外层事务回滚后只应剩已提交的旧行");
    }

    @Test
    @DisplayName("形态钉：insertSplitNested 必须是 Propagation.NESTED")
    void insertSplitNested_annotationIsNested() throws Exception {
        Transactional tx = RevenueSplitService.class
                .getMethod("insertSplitNested", OrderRevenueSplit.class)
                .getAnnotation(Transactional.class);
        assertNotNull(tx, "insertSplitNested 必须带 @Transactional");
        assertEquals(Propagation.NESTED, tx.propagation(),
                "必须 NESTED；改回 REQUIRED 会让 DKE 打爆外层事务");
    }
}