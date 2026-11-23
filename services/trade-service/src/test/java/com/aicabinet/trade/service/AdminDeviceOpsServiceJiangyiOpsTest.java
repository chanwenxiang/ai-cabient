package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceOpsCommandRequest;
import com.aicabinet.common.dto.DeviceOpsCommandResultDto;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 将邑车队运维指令：**能路由就路由，不能就诚实拒绝**（2026-11-23）。
 *
 * <p>🔴 背景缺陷：将邑柜不接 MQTT，而 {@code AdminDeviceOpsService} 的自检/重启/设温一律
 * 经 {@code DeviceServiceClient} 往 MQTT 主题发 —— 对将邑柜等于发给没有订阅者的主题，
 * 接口回 200「已下发」，15s 后 TIMEOUT，柜机零动作。
 *
 * <p>本类钉三件事：
 * <ol>
 *   <li>REBOOT 走将邑网关（复用 {@code JiangyiDeviceOpsService}，「进行中业务」闸一并复用）；</li>
 *   <li>SELF_TEST / SET_TEMP 明确拒绝或如实说明，不许再出现「已下发」；</li>
 *   <li>非将邑设备的失败文案不许把「通道不对」误报成「device-service 在线」。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDeviceOpsServiceJiangyiOpsTest {

    private static final long OPERATOR_ID = 100_000_001L;
    private static final String JIANGYI_DEVICE = "EG943322621391";
    private static final String MQTT_DEVICE = "CAB-001";

    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private DeviceValidationService deviceValidationService;
    @Mock private DeviceSalesLockService salesLockService;
    @Mock private DeviceServiceClient deviceClient;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private JiangyiDeviceDirectory jiangyiDeviceDirectory;
    @Mock private JiangyiDeviceOpsService jiangyiDeviceOpsService;

    private AdminDeviceOpsService service;

    @BeforeEach
    void setUp() {
        service = new AdminDeviceOpsService(deviceRepository, sessionRepository, deviceValidationService,
                salesLockService, deviceClient, merchantScopeService, permissionService, auditService,
                distributedLockService, null, null, jiangyiDeviceDirectory, jiangyiDeviceOpsService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        when(jiangyiDeviceDirectory.isJiangyi(JIANGYI_DEVICE)).thenReturn(true);
        when(deviceRepository.findById(JIANGYI_DEVICE)).thenReturn(Optional.of(device(JIANGYI_DEVICE)));
        when(deviceRepository.findById(MQTT_DEVICE)).thenReturn(Optional.of(device(MQTT_DEVICE)));
    }

    @Test
    void reboot_onJiangyiDevice_routesThroughGatewayInsteadOfMqtt() {
        DeviceOpsCommandResultDto result = service.execute(OPERATOR_ID, JIANGYI_DEVICE,
                new DeviceOpsCommandRequest("REBOOT", "远程重启", null));

        // 走网关（复用其「进行中业务」闸：force=false 表示不越过）
        verify(jiangyiDeviceOpsService).reboot(OPERATOR_ID, JIANGYI_DEVICE, false);
        // 绝不发 MQTT（那是有订阅者的前提才成立）
        verify(deviceClient, never()).requestOpsCommand(anyString(), anyString());
        assertEquals("REBOOT", result.command());
        assertNull(result.commandId(), "将邑 reboot 无回执/无指令号，不能伪造一个");
        assertTrue(result.message().contains("网关"), "文案须点明走的是网关通道：" + result.message());
    }

    @Test
    void selfTest_onJiangyiDevice_isRejectedInsteadOfFakeSuccess() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.execute(OPERATOR_ID, JIANGYI_DEVICE,
                        new DeviceOpsCommandRequest("SELF_TEST", "自检", null)));

        assertEquals(HttpStatus.NOT_IMPLEMENTED, ex.getStatusCode());
        assertTrue(ex.getReason().contains("无远程自检"), ex.getReason());
        verify(deviceClient, never()).requestOpsCommand(anyString(), anyString());
    }

    @Test
    void setTemp_onJiangyiDevice_recordsButSaysNoRemoteControl() {
        DeviceOpsCommandResultDto result = service.execute(OPERATOR_ID, JIANGYI_DEVICE,
                new DeviceOpsCommandRequest("SET_TEMP", "冷链检查", 5));

        verify(deviceClient, never()).requestSetTargetTemp(anyString(), anyInt());
        // 目标温度仍落库（运营记录/报表口径）
        verify(deviceRepository).save(any(DeviceInfo.class));
        assertTrue(result.commandId().startsWith("LOCAL-"), result.commandId());
        assertTrue(result.message().contains("无远程控温"), result.message());
    }

    @Test
    void setTemp_onMqttDevice_passesRouteRefusalReasonThrough() {
        // 路由层的明确拒绝（如 501）必须原样进文案：旧代码 catch(Exception) 一律写成
        // 「请确认 device-service 在线」，把「通道不对」误报成「服务挂了」。
        when(deviceClient.requestSetTargetTemp(eq(MQTT_DEVICE), anyInt()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "柜机不受理该指令"));

        DeviceOpsCommandResultDto result = service.execute(OPERATOR_ID, MQTT_DEVICE,
                new DeviceOpsCommandRequest("SET_TEMP", "巡检", 3));

        assertTrue(result.message().contains("柜机不受理该指令"), result.message());
        assertTrue(!result.message().contains("device-service 在线"),
                "不得把明确拒绝误报成服务不可用：" + result.message());
    }

    private static DeviceInfo device(String deviceId) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(deviceId);
        d.setOnlineStatus("ONLINE");
        return d;
    }
}
