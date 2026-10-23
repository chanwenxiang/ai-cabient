package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.ws.DeviceWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 将邑设备控制指令入口（V363，图2 对照「音量设置」，2026-10-15）：
 * trade JiangyiGatewayClient 路由至此（仅 BOUND 的将邑设备），经 WS 下发——
 * ① 音量开关 {@code {msgType:"sendDeviceSoundSwitch", msgContent:"0"关/"1"开}}（V16 §4.3.2.16）
 * ② 音量大小 {@code {msgType:"sendDeviceSoundVolume", msgContent:"0"大/"1"中/"2"小}}（V16 §4.3.2.17）
 * ③ 信息拉取 {@code {msgType:"getDeviceInfo", msgContent:""}}（§4.3.2.13，回执走
 *    HTTP §4.2.15 getDeviceSimInfo → trade device_info.signal_strength）
 * ④ 关门/回锁 {@code {msgType:"closeDoor", msgContent:""}}（§4.3.2.6 可选，无回执）
 * ⑤ 机器重启 {@code {msgType:"reboot", msgContent:""}}（§4.3.2.1，无回执）
 * ⑥ 门磁查询 {@code {msgType:"getDoorState", msgContent:""}}（§4.3.2.12 可选，回执走
 *    HTTP §4.2.16 getDoorInfo → trade device_info.door_state/lock_state）
 *
 * <p>下发成功 ≠ 设备已执行：音量/关门/重启文档未定义回执，仅审计下发动作。</p>
 *
 * <p>鉴权：/internal/** 由 InternalApiAuthInterceptor 统一校验（GatewayWebConfig）。</p>
 */
@RestController
@RequestMapping("/internal/v1/jiangyi")
public class DeviceCommandController {

    private static final Logger log = LoggerFactory.getLogger(DeviceCommandController.class);

    private final TradeInternalClient tradeInternalClient;
    private final DeviceWebSocketHandler deviceWebSocketHandler;

    public DeviceCommandController(TradeInternalClient tradeInternalClient,
                                   DeviceWebSocketHandler deviceWebSocketHandler) {
        this.tradeInternalClient = tradeInternalClient;
        this.deviceWebSocketHandler = deviceWebSocketHandler;
    }

    /** 音量设置：switchOn 与 volume 至少一项；两项都带时按文档顺序先开关后大小。 */
    @PostMapping("/devices/{deviceId}/sound")
    public void sound(@PathVariable("deviceId") String deviceId,
                      @RequestBody SoundRequest request) {
        if (request.switchOn() == null && request.volume() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "switchOn/volume 至少一项");
        }
        if (request.volume() != null && (request.volume() < 0 || request.volume() > 2)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "volume 取值 0=大/1=中/2=小");
        }
        String identifier = requireOnlineIdentifier(deviceId);
        if (request.switchOn() != null
                && !deviceWebSocketHandler.send(identifier, Map.of(
                        "status", 200,
                        "msgType", "sendDeviceSoundSwitch",
                        "msgContent", request.switchOn() ? "1" : "0"))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "音量开关指令下发失败");
        }
        // 固件音量帧走 AudioManager.setStreamVolume（会解除静音）：静音后再发音量帧会把声音放回来
        boolean muted = Boolean.FALSE.equals(request.switchOn());
        if (!muted && request.volume() != null
                && !deviceWebSocketHandler.send(identifier, Map.of(
                        "status", 200,
                        "msgType", "sendDeviceSoundVolume",
                        "msgContent", String.valueOf(request.volume())))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "音量大小指令下发失败");
        }
        log.info("jiangyi sound dispatched deviceId={} identifier={} switchOn={} volume={}",
                deviceId, identifier, request.switchOn(), request.volume());
    }

    /** 实时拉取设备信息（signalStrength），回执走 HTTP §4.2.15 getDeviceSimInfo。 */
    @PostMapping("/devices/{deviceId}/device-info")
    public void deviceInfo(@PathVariable("deviceId") String deviceId) {
        String identifier = requireOnlineIdentifier(deviceId);
        if (!deviceWebSocketHandler.send(identifier, Map.of(
                "status", 200,
                "msgType", "getDeviceInfo",
                "msgContent", ""))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "设备信息指令下发失败");
        }
        log.info("jiangyi getDeviceInfo dispatched deviceId={} identifier={}", deviceId, identifier);
    }

    /**
     * 按单拉取柜内视频（V16 §4.3.2.11）：msgContent=我方 sessionId（开门时设备即以它作
     * 单号，本地文件名 {@code /sdcard/{单号}-left.mp4}/{@code -right.mp4}，固件证据
     * DealSocketMsg .line 186-200）。不分正常/异常单，文件在则经 getTempToken→OSS→
     * uploadVideoUrl 回传；不在则设备回同类型 WS 帧（见 DeviceWebSocketHandler 分支）。
     * 文档示例里的 {@code _MSG} 后缀是厂商云标注习惯，固件无该字面量，不发。
     */
    @PostMapping("/devices/{deviceId}/pull-video")
    public void pullVideo(@PathVariable("deviceId") String deviceId,
                          @RequestBody PullVideoRequest request) {
        String orderNo = request.orderNo() == null ? "" : request.orderNo().trim();
        if (orderNo.isBlank() || !orderNo.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderNo 非法（1-64 位字母数字/_/-）");
        }
        String identifier = requireOnlineIdentifier(deviceId);
        if (!deviceWebSocketHandler.send(identifier, Map.of(
                "status", 200,
                "msgType", DeviceWebSocketHandler.MSG_TYPE_UPLOAD_ERROR_ORDER_VIDEOS,
                "msgContent", orderNo))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "拉取视频指令下发失败");
        }
        log.info("jiangyi pullVideo dispatched deviceId={} identifier={} orderNo={}",
                deviceId, identifier, orderNo);
    }

    /**
     * 关门/回锁（V16 §4.3.2.6，可选指令）：{@code {msgType:"closeDoor", msgContent:""}}。
     * 文档未定义回执，仅审计下发动作；2026-10-23 现场实测柜门被采集开门会话
     * （gatherOpenDoor）解锁后无法回锁，运营需要显式关门手段。
     */
    @PostMapping("/devices/{deviceId}/close-door")
    public void closeDoor(@PathVariable("deviceId") String deviceId) {
        String identifier = requireOnlineIdentifier(deviceId);
        if (!deviceWebSocketHandler.send(identifier, Map.of(
                "status", 200,
                "msgType", "closeDoor",
                "msgContent", ""))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "关门指令下发失败");
        }
        log.info("jiangyi closeDoor dispatched deviceId={} identifier={}", deviceId, identifier);
    }

    /**
     * 机器重启（V16 §4.3.2.1）：{@code {msgType:"reboot", msgContent:""}}。无回执；
     * 设备重启期间 WS 会断开（我方收到 ws-offline 回执即置离线）。
     */
    @PostMapping("/devices/{deviceId}/reboot")
    public void reboot(@PathVariable("deviceId") String deviceId) {
        String identifier = requireOnlineIdentifier(deviceId);
        if (!deviceWebSocketHandler.send(identifier, Map.of(
                "status", 200,
                "msgType", "reboot",
                "msgContent", ""))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "重启指令下发失败");
        }
        log.info("jiangyi reboot dispatched deviceId={} identifier={}", deviceId, identifier);
    }

    /**
     * 查询门磁实时状态（V16 §4.3.2.12 可选）：{@code {msgType:"getDoorState", msgContent:""}}，
     * 设备随后经 HTTP §4.2.16 deviceInfo/getDoorInfo 回 doorState/lockState。
     */
    @PostMapping("/devices/{deviceId}/door-state")
    public void doorState(@PathVariable("deviceId") String deviceId) {
        String identifier = requireOnlineIdentifier(deviceId);
        if (!deviceWebSocketHandler.send(identifier, Map.of(
                "status", 200,
                "msgType", "getDoorState",
                "msgContent", ""))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "门磁查询指令下发失败");
        }
        log.info("jiangyi getDoorState dispatched deviceId={} identifier={}", deviceId, identifier);
    }

    private String requireOnlineIdentifier(String deviceId) {
        TradeInternalClient.JiangyiDeviceView device = tradeInternalClient.device(deviceId).orElse(null);
        if (device == null || !"BOUND".equals(device.status())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "将邑设备未绑定：" + deviceId);
        }
        String identifier = device.identifier();
        if (!deviceWebSocketHandler.isOnline(identifier)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "设备不在线");
        }
        return identifier;
    }

    /** volume 0=大 1=中 2=小（§4.3.2.17）；switchOn true=开 false=关（§4.3.2.16）。 */
    public record SoundRequest(Boolean switchOn, Integer volume) {}

    /** orderNo=我方 sessionId（§4.3.2.11 拉取视频）。 */
    public record PullVideoRequest(String orderNo) {}
}
