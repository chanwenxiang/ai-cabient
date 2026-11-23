package com.aicabinet.trade.client;

import com.aicabinet.common.constants.CabinetConstants;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 将邑柜「指令能力表」——**单一判据来源**：每条 MQTT 运维指令在将邑柜上究竟走哪条通道。
 *
 * <p>🔴 <b>为什么必须有这张表</b>：将邑柜<b>不接 MQTT</b>，它只连 jiangyi-gateway 的
 * WebSocket（V16 下行 17 条，我方实现 10 个 msgType）。而 trade 侧
 * {@code DeviceServiceClient.requestOpsCommand} / {@code requestSetTargetTemp} 只发
 * device-service 的 MQTT 主题 —— 对将邑柜等于发给<b>没有订阅者</b>的主题：接口当场回 200
 * 「已下发」，15s 后 {@code DeviceCommandTracker} 置 TIMEOUT，柜机什么都没发生。
 *
 * <p>2026-11-23 复核实例：商户端「重启」（{@code POST /merchant/devices/{id}/commands}
 * → {@code AdminDeviceOpsService#reboot}）对将邑柜即此情形 —— 而将邑侧其实早有
 * {@code reboot}（§4.3.2.1，真机验过：30s 后重连）。这属于「本机看着成功、设备零动作」，
 * 比直接失败更难排查。
 *
 * <p>⇒ 本表把「能路由 / 只能平台侧 / 不支持」显式写下来，
 * {@code check:jiangyi-command-capability} 门禁按 {@link CabinetConstants} 的
 * {@code MQTT_CMD_*} 常量逐个核对：新增指令不登记就红。
 */
public final class JiangyiCommandCapability {

    private JiangyiCommandCapability() {}

    /** 将邑柜上一条指令的真实归属。 */
    public enum Route {
        /** 走 jiangyi-gateway 的 WS 下行（我方已实现且真机验过）。 */
        GATEWAY,
        /**
         * 将邑协议无对应<b>物理</b>动作，只在平台侧生效（营业停售＝平台拒绝开门）。
         * ⚠️ 不得为此伪造一条下发指令 —— 那会让运维以为设备真的被锁。
         */
        PLATFORM_ONLY,
        /** 将邑协议没有该能力：必须明确拒绝，不能假成功。 */
        UNSUPPORTED
    }

    /**
     * 常量值 → 归属。
     *
     * <p>用 {@link LinkedHashMap} 而非 {@code Map.of}：后者<b>不保证顺序</b>，
     * 而门禁报错与运维文档都希望按常量声明顺序展示。
     */
    private static final Map<String, Route> ROUTES;

    static {
        Map<String, Route> m = new LinkedHashMap<>();
        m.put(CabinetConstants.MQTT_CMD_OPEN_DOOR, Route.GATEWAY);
        m.put(CabinetConstants.MQTT_CMD_REBOOT, Route.GATEWAY);
        m.put(CabinetConstants.MQTT_CMD_LOCK, Route.PLATFORM_ONLY);
        m.put(CabinetConstants.MQTT_CMD_UNLOCK, Route.PLATFORM_ONLY);
        m.put(CabinetConstants.MQTT_CMD_SELF_TEST, Route.UNSUPPORTED);
        m.put(CabinetConstants.MQTT_CMD_SET_TARGET_TEMP, Route.UNSUPPORTED);
        ROUTES = Collections.unmodifiableMap(m);
    }

    /**
     * 查归属。
     *
     * @throws IllegalArgumentException 指令未登记 —— <b>fail-closed</b>：宁可当场报错，
     *         也不能把未声明语义的指令发到将邑柜上（静默无效比报错危险得多）。
     */
    public static Route routeOf(String command) {
        String cmd = command == null ? "" : command.trim().toUpperCase(Locale.ROOT);
        Route route = ROUTES.get(cmd);
        if (route == null) {
            throw new IllegalArgumentException("将邑柜未声明指令能力: " + cmd);
        }
        return route;
    }

    /** 已登记的指令值集合（门禁与用例用）。 */
    public static java.util.Set<String> declaredCommands() {
        return ROUTES.keySet();
    }
}
