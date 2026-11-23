package com.aicabinet.trade.client;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.security.InternalApiProperties;
import com.aicabinet.trade.service.JiangyiDeviceDirectory;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DeviceServiceClient} 的将邑护栏：**任何 MQTT 指令都不许发给将邑柜**。
 *
 * <p>这是「兜底闸」——正常路径由 {@code AdminDeviceOpsService} 先把 REBOOT 转去网关，
 * 但将来任何新调用方直接拿本客户端发指令时，必须当场拒绝而不是假成功。
 */
class DeviceServiceClientJiangyiGuardTest {

    private static final String JIANGYI_DEVICE = "EG943322621391";
    private static final String MQTT_DEVICE = "CAB-001";

    private JiangyiDeviceDirectory directory;
    private DeviceServiceClient client;

    @BeforeEach
    void setUp() {
        directory = mock(JiangyiDeviceDirectory.class);
        when(directory.isJiangyi(JIANGYI_DEVICE)).thenReturn(true);
        when(directory.isJiangyi(MQTT_DEVICE)).thenReturn(false);
        client = new DeviceServiceClient(
                "http://localhost:1",
                "http://localhost:2",
                new InternalApiProperties("test-key", null, null),
                directory,
                RetryRegistry.ofDefaults());
    }

    @Test
    void usesMqttChannel_reflectsJiangyiDirectory() {
        assertFalse(client.usesMqttChannel(JIANGYI_DEVICE));
        assertTrue(client.usesMqttChannel(MQTT_DEVICE));
    }

    @Test
    void gatewayRoutedCommand_isRefusedWithChannelHint() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.requestOpsCommand(JIANGYI_DEVICE, CabinetConstants.MQTT_CMD_REBOOT));
        assertEquals(HttpStatus.NOT_IMPLEMENTED, ex.getStatusCode());
        assertTrue(ex.getReason().contains("须经将邑网关"), ex.getReason());
    }

    @Test
    void platformOnlyCommand_saysNoPhysicalCommandExists() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.requestOpsCommand(JIANGYI_DEVICE, CabinetConstants.MQTT_CMD_LOCK));
        assertTrue(ex.getReason().contains("无 LOCK 物理指令"), ex.getReason());
        assertTrue(ex.getReason().contains("平台侧生效"), ex.getReason());
    }

    @Test
    void unsupportedCommand_isRefusedAsProtocolGap() {
        ResponseStatusException selfTest = assertThrows(ResponseStatusException.class,
                () -> client.requestOpsCommand(JIANGYI_DEVICE, CabinetConstants.MQTT_CMD_SELF_TEST));
        assertTrue(selfTest.getReason().contains("协议不支持"), selfTest.getReason());

        ResponseStatusException temp = assertThrows(ResponseStatusException.class,
                () -> client.requestSetTargetTemp(JIANGYI_DEVICE, 4));
        assertTrue(temp.getReason().contains("协议不支持"), temp.getReason());
    }

    @Test
    void undeclaredCommand_failsClosedInsteadOfSending() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.requestOpsCommand(JIANGYI_DEVICE, "FORMAT_DISK"));
        assertTrue(ex.getReason().contains("未声明指令能力"), ex.getReason());
    }
}
