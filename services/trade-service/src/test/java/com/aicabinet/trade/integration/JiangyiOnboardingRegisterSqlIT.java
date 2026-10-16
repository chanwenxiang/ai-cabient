package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.service.JiangyiOnboardingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 将邑一期入驻第 1 步（register）真库跑通（CB-022 方案 §6.1）。
 *
 * <p>背景（2026-10-13 C-admin F1 实测实锤）：{@code register} 只写
 * deviceId/deviceSn/modelName/status=UNBOUND，**从不填 identifier**，而
 * {@code V332__jiangyi_device.sql:22} 该列 {@code NOT NULL} ⇒ 后台登记恒 500
 * （DataIntegrityViolationException），真机联调日第一步就卡死。
 * {@code setIdentifier} 全仓仅出现在 {@code bindDomain}（第 2 步，需调将邑真租户），
 * 所以「登记时 identifier 未知」是设计事实，列约束与流程矛盾。</p>
 *
 * <p>本 IT 钉两件事：</p>
 * <ul>
 *   <li>UNBOUND 设备能落库（会红：修复前抛 DataIntegrityViolationException）</li>
 *   <li>多台 UNBOUND 设备**可同时存在** ⇒ 证明修法只能是「列可空」而非
 *       「填占位串」：占位串会被 {@code uk_jiangyi_device_identifier} 唯一约束挡住，
 *       第二台就红（本文件 {@code twoUnboundDevices_coexist}）</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class JiangyiOnboardingRegisterSqlIT {

    private static final String DEV_A = "JI-REG-IT-A";
    private static final String DEV_B = "JI-REG-IT-B";
    private static final String SN_A = "jiregita00000001";
    private static final String SN_B = "jiregitb00000002";

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    @Autowired
    private JiangyiOnboardingService onboardingService;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM jiangyi_device WHERE device_id IN (?, ?)", DEV_A, DEV_B);
        jdbc.update("DELETE FROM device_info WHERE device_id IN (?, ?)", DEV_A, DEV_B);
    }

    private void seedDeviceInfo(String deviceId) {
        // device_name 有唯一约束（uk_device_name），每台柜机必须用各自的名字
        jdbc.update("INSERT INTO device_info (device_id, device_name, device_type, online_status) "
                        + "VALUES (?, ?, 'AI_CABINET_V1', 'OFFLINE') ON CONFLICT (device_id) DO NOTHING",
                deviceId, "jiangyi register it " + deviceId);
    }

    @Test
    @DisplayName("登记（后台入口 registerForDevice）应落库为 UNBOUND 且 identifier 为空")
    void register_persistsUnboundRow() {
        seedDeviceInfo(DEV_A);

        JiangyiDevice device = onboardingService.registerForDevice(DEV_A, SN_A, "AUDIT-IT-MODEL");

        assertNotNull(device, "register 必须返回档案");
        assertEquals("UNBOUND", device.getStatus());
        assertEquals(DEV_A, device.getDeviceId());

        Row row = jdbc.queryForObject(
                "SELECT status, identifier FROM jiangyi_device WHERE device_id = ?",
                (rs, i) -> new Row(rs.getString("status"), rs.getString("identifier")),
                DEV_A);
        assertNotNull(row);
        assertEquals("UNBOUND", row.status, "落库状态必须是 UNBOUND（第 2 步 bindDomain 才转 BOUND）");
        assertNull(row.identifier, "未绑定设备的 identifier 必须为空，而不是被占位串污染");
    }

    @Test
    @DisplayName("两台未绑定设备可并存（钉住「列可空」修法，占位串修法会在此变红）")
    void twoUnboundDevices_coexist() {
        seedDeviceInfo(DEV_A);
        seedDeviceInfo(DEV_B);

        onboardingService.registerForDevice(DEV_A, SN_A, "AUDIT-IT-MODEL");
        onboardingService.registerForDevice(DEV_B, SN_B, "AUDIT-IT-MODEL");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jiangyi_device WHERE device_id IN (?, ?) AND status = 'UNBOUND'",
                Integer.class, DEV_A, DEV_B);
        assertEquals(2, count, "UNBOUND 设备各自 identifier 未知，不得因唯一约束互相挤掉");
    }

    @Test
    @DisplayName("幂等：同 SN 重复登记返回既有行；柜机不存在则拒绝（不产生孤儿绑定）")
    void register_isIdempotent_andRejectsUnknownDevice() {
        seedDeviceInfo(DEV_A);

        JiangyiDevice first = onboardingService.registerForDevice(DEV_A, SN_A, "AUDIT-IT-MODEL");
        JiangyiDevice again = onboardingService.registerForDevice(DEV_A, SN_A, "AUDIT-IT-MODEL");
        assertEquals(first.getDeviceId(), again.getDeviceId());

        assertThrows(IllegalArgumentException.class,
                () -> onboardingService.registerForDevice("JI-REG-IT-NONE", "jiregn0000000001", "M"),
                "柜机档案不存在时必须拒绝，否则会造出孤儿绑定行");
    }

    private record Row(String status, String identifier) {}
}
