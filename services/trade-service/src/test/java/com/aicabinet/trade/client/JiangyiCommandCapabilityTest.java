package com.aicabinet.trade.client;

import com.aicabinet.common.constants.CabinetConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 将邑指令能力表：**常量全集必须逐条登记**，未登记一律 fail-closed。
 *
 * <p>🔴 这条用例是 {@code check:jiangyi-command-capability} 门禁的镜像：门禁扫源码，
 * 用例扫反射 —— 两侧都钉，任一侧独立发现「新增 MQTT 指令没声明将邑语义」。
 */
class JiangyiCommandCapabilityTest {

    @Test
    void everyMqttCommandConstant_mustBeDeclared() throws Exception {
        List<String> undeclared = new ArrayList<>();
        for (Field f : CabinetConstants.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                continue;
            }
            if (!f.getName().startsWith("MQTT_CMD_")) {
                continue;
            }
            String value = (String) f.get(null);
            if (!JiangyiCommandCapability.declaredCommands().contains(value)) {
                undeclared.add(f.getName() + "=" + value);
            }
        }
        assertTrue(undeclared.isEmpty(),
                "新增 MQTT 指令未在 JiangyiCommandCapability 声明将邑语义：" + undeclared);
        // 底线：至少覆盖 6 条（漏删常量时也会红）
        assertEquals(6, JiangyiCommandCapability.declaredCommands().size(),
                "已声明指令数变化必须显式确认：" + JiangyiCommandCapability.declaredCommands());
    }

    @Test
    void routeOf_splitFollowsRealJiangyiCapability() {
        assertEquals(JiangyiCommandCapability.Route.GATEWAY,
                JiangyiCommandCapability.routeOf(CabinetConstants.MQTT_CMD_OPEN_DOOR));
        assertEquals(JiangyiCommandCapability.Route.GATEWAY,
                JiangyiCommandCapability.routeOf(CabinetConstants.MQTT_CMD_REBOOT));
        // 将邑门锁由开门指令控制，没有独立 LOCK/UNLOCK 物理指令
        assertEquals(JiangyiCommandCapability.Route.PLATFORM_ONLY,
                JiangyiCommandCapability.routeOf(CabinetConstants.MQTT_CMD_LOCK));
        assertEquals(JiangyiCommandCapability.Route.PLATFORM_ONLY,
                JiangyiCommandCapability.routeOf(CabinetConstants.MQTT_CMD_UNLOCK));
        // V16 下行 17 条无自检、无温控
        assertEquals(JiangyiCommandCapability.Route.UNSUPPORTED,
                JiangyiCommandCapability.routeOf(CabinetConstants.MQTT_CMD_SELF_TEST));
        assertEquals(JiangyiCommandCapability.Route.UNSUPPORTED,
                JiangyiCommandCapability.routeOf(CabinetConstants.MQTT_CMD_SET_TARGET_TEMP));
    }

    @Test
    void routeOf_isCaseAndSpaceTolerant_butUnknownFailsClosed() {
        assertEquals(JiangyiCommandCapability.Route.GATEWAY, JiangyiCommandCapability.routeOf(" reboot "));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JiangyiCommandCapability.routeOf("FORMAT_DISK"));
        assertTrue(ex.getMessage().contains("未声明指令能力"), ex.getMessage());
        assertFalse(JiangyiCommandCapability.declaredCommands().contains("FORMAT_DISK"));
    }
}
