package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 演示播种不得落到将邑绑定真机上（2026-10-28）。
 *
 * <p>背景：{@code DemoDataBootstrap} 每次启动都会跑 {@code ensureDemoData()}，
 * 而挑柜偏好是「在线 &gt; 已投放 &gt; deviceId 升序」。真机柜（将邑 BOUND）恰好是最常见的
 * 「在线 + 已投放」设备，于是演示 SKU 与 8 条默认货道被反复写回真实柜：
 * 台账口径把它们统计成缺货（实测 EG943322621391 首页低库存 3 变成 9），
 * 运营手工清掉也会被下一次启动覆盖。
 *
 * <p>这里钉住两条：① 将邑柜无论在线/投放与否都不进候选；② 排除后偏好顺序不变。
 */
class DemoDataServiceTest {

    private static final String JIANGYI_DEVICE = "EG943322621391";
    private static final String DEMO_DEVICE = "719325384517";
    private static final String LEGACY_DEVICE = "500909625160";

    private final DeviceInfoMapper deviceInfoMapper = mock(DeviceInfoMapper.class);
    private final JiangyiDeviceDirectory jiangyiDeviceDirectory = mock(JiangyiDeviceDirectory.class);

    @Test
    void pickExistingDemoDevice_skipsJiangyiBoundDevice_evenWhenOnlineAndDeployed() {
        DeviceInfo real = device(JIANGYI_DEVICE, "MCH-DEFAULT", "ONLINE", "DEPLOYED");
        DeviceInfo demo = device(DEMO_DEVICE, "MCH-DEFAULT", "OFFLINE", "DEPLOYED");
        when(deviceInfoMapper.findAllOrderByDeviceIdAsc()).thenReturn(List.of(real, demo));
        when(jiangyiDeviceDirectory.isJiangyi(JIANGYI_DEVICE)).thenReturn(true);

        Optional<DeviceInfo> picked = service().pickExistingDemoDevice();

        assertTrue(picked.isPresent(), "排除将邑柜后应仍有候选");
        assertEquals(DEMO_DEVICE, picked.get().getDeviceId());
    }

    @Test
    void pickExistingDemoDevice_keepsOnlinePreferenceAmongEligibleDevices() {
        DeviceInfo offline = device(LEGACY_DEVICE, "MCH-DEFAULT", "OFFLINE", "DEPLOYED");
        DeviceInfo online = device(DEMO_DEVICE, "MCH-DEFAULT", "ONLINE", "DEPLOYED");
        when(deviceInfoMapper.findAllOrderByDeviceIdAsc()).thenReturn(List.of(offline, online));

        Optional<DeviceInfo> picked = service().pickExistingDemoDevice();

        assertTrue(picked.isPresent());
        assertEquals(DEMO_DEVICE, picked.get().getDeviceId(), "偏好顺序（在线优先）不应被本次修复改动");
    }

    @Test
    void pickExistingDemoDevice_skipsLegacyOrphanDevices() {
        DeviceInfo orphan = device("CAB-OTHER", "MCH-OTHER", "ONLINE", "DEPLOYED");
        DeviceInfo demo = device(DEMO_DEVICE, "MCH-DEFAULT", "OFFLINE", null);
        when(deviceInfoMapper.findAllOrderByDeviceIdAsc()).thenReturn(List.of(orphan, demo));

        Optional<DeviceInfo> picked = service().pickExistingDemoDevice();

        assertTrue(picked.isPresent());
        assertEquals(DEMO_DEVICE, picked.get().getDeviceId());
    }

    /** 只填本用例用到的依赖：被测方法只碰 deviceInfoRepository 与将邑目录。 */
    private DemoDataService service() {
        return new DemoDataService(
                null, null, deviceInfoMapper, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, jiangyiDeviceDirectory, null);
    }

    private static DeviceInfo device(String deviceId, String merchantId, String onlineStatus,
                                     String lifecycleStatus) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(deviceId);
        d.setMerchantId(merchantId);
        d.setOnlineStatus(onlineStatus);
        d.setLifecycleStatus(lifecycleStatus);
        return d;
    }
}
