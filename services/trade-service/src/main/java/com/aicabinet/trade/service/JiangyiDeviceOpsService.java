package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatewayClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 将邑设备控制面（V363，图2 对照，2026-10-15）：
 * ① 音量设置（§4.3.2.16/17 sendDeviceSoundSwitch/sendDeviceSoundVolume）
 * ② 实时拉取设备信息（§4.3.2.13 getDeviceInfo → §4.2.15 getDeviceSimInfo 回执落
 *    device_info.signal_strength，gateway DeviceReportController → trade signal 转发）
 * ③ 信号强度视图（0-4 档）。
 *
 * <p>协议没有的能力（网络延迟/拍照设置/断电检测）不做——2026-10-15 用户拍板：
 * 将邑文档没有的就不做。鉴权在 AdminJiangyiController（ops:device:list / ops:device:edit）。</p>
 */
@Service
public class JiangyiDeviceOpsService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiDeviceOpsService.class);

    private final JiangyiGatewayClient gatewayClient;
    private final JiangyiDeviceDirectory jiangyiDeviceDirectory;
    private final DeviceInfoMapper deviceRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final AdminAuditService auditService;

    public JiangyiDeviceOpsService(JiangyiGatewayClient gatewayClient,
                                   JiangyiDeviceDirectory jiangyiDeviceDirectory,
                                   DeviceInfoMapper deviceRepository,
                                   ShoppingSessionMapper sessionRepository,
                                   AdminAuditService auditService) {
        this.gatewayClient = gatewayClient;
        this.jiangyiDeviceDirectory = jiangyiDeviceDirectory;
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.auditService = auditService;
    }

    /** 音量设置：switchOn 与 volume 至少一项（volume 0=大/1=中/2=小）。 */
    public void setSound(Long operatorId, String deviceId, Boolean switchOn, Integer volume) {
        if (switchOn == null && volume == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "音量开关与音量大小至少填一项");
        }
        if (volume != null && (volume < 0 || volume > 2)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "音量大小取值 0=大/1=中/2=小");
        }
        requireJiangyi(deviceId);
        try {
            gatewayClient.soundSet(deviceId, switchOn, volume);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("jiangyi sound dispatch failed device={}: {}", deviceId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "音量设置下发失败（网关不可达或设备离线）");
        }
        auditService.appendLog(operatorId, "JIANGYI_SOUND", "DEVICE", deviceId,
                "音量设置：switchOn=" + switchOn + "，volume=" + (volume == null ? "-" : volume));
    }

    /** 实时拉取设备信息（signalStrength），回执异步落库后由 signal 接口读出。 */
    public void requestDeviceInfo(Long operatorId, String deviceId) {
        requireJiangyi(deviceId);
        try {
            gatewayClient.requestDeviceInfo(deviceId);
        } catch (Exception e) {
            log.warn("jiangyi getDeviceInfo dispatch failed device={}: {}", deviceId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "设备信息拉取失败（网关不可达或设备离线）");
        }
        auditService.appendLog(operatorId, "JIANGYI_DEVICE_INFO", "DEVICE", deviceId, "下发设备信息拉取（getDeviceInfo）");
    }

    /** 关门/回锁（§4.3.2.6 可选指令 closeDoor）：文档未定义回执，是否生效看设备行为。 */
    public void closeDoor(Long operatorId, String deviceId) {
        requireJiangyi(deviceId);
        try {
            gatewayClient.closeDoor(deviceId);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("jiangyi closeDoor dispatch failed device={}: {}", deviceId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "关门指令下发失败（网关不可达或设备离线）");
        }
        auditService.appendLog(operatorId, "JIANGYI_CLOSE_DOOR", "DEVICE", deviceId, "下发关门指令（closeDoor）");
    }

    /** 机器重启（§4.3.2.1 reboot）：无回执；设备重启后 WS 会短暂断开。 */
    public void reboot(Long operatorId, String deviceId) {
        requireJiangyi(deviceId);
        try {
            gatewayClient.reboot(deviceId);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("jiangyi reboot dispatch failed device={}: {}", deviceId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "重启指令下发失败（网关不可达或设备离线）");
        }
        auditService.appendLog(operatorId, "JIANGYI_REBOOT", "DEVICE", deviceId, "下发重启指令（reboot）");
    }

    /**
     * 查询门磁实时状态（§4.3.2.12 getDoorState）：设备收到后经 HTTP §4.2.16
     * deviceInfo/getDoorInfo 回 {doorState, lockState}，异步落 device_info，
     * 由 {@link #doorState} 读出。设备没回/固件未实现则保持旧值（时间戳可辨）。
     */
    public void requestDoorState(Long operatorId, String deviceId) {
        requireJiangyi(deviceId);
        try {
            gatewayClient.requestDoorState(deviceId);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("jiangyi getDoorState dispatch failed device={}: {}", deviceId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "门磁查询下发失败（网关不可达或设备离线）");
        }
        auditService.appendLog(operatorId, "JIANGYI_DOOR_STATE", "DEVICE", deviceId, "下发门磁查询（getDoorState）");
    }

    /**
     * 门磁回执落库（gateway §4.2.16 转发）：无法识别的取值丢弃，不覆盖既有快照。
     * <p>{@code unknown} 是固件真实会发的值（LockSys 读 GPIO 抛 IOException/RuntimeException/
     * LinkageError 时两个字段保持初值 "unknown" 照发，DealSocketMsg.uploadDoorState），
     * 必须收下并盖时间戳——否则设备明明回了，后台仍显示「未上报」，运营无法区分
     * 「指令没到」与「到了但锁控读不出来」。</p>
     */
    @Transactional
    public void recordDoorState(String deviceId, String doorState, String lockState) {
        String door = normalizeSwitchState(doorState);
        String lock = normalizeSwitchState(lockState);
        if (door == null && lock == null) {
            log.warn("jiangyi door-state IGNORED device={} doorState={} lockState={}（取值无法识别）",
                    deviceId, doorState, lockState);
            return;
        }
        int updated = deviceRepository.updateDoorState(deviceId, door, lock,
                java.time.Instant.now());
        if (updated == 0) {
            // 设备先于我方建档上报：静默丢会让人以为链路没通。
            log.warn("jiangyi door-state DROPPED device={} door={} lock={}（device_info 无此设备）",
                    deviceId, door, lock);
        }
    }

    /**
     * 文档口径 open/close（V16 §4.2.16），另收固件实发的 unknown（锁控读取失败）。
     * 大小写与空白宽容（固件口径不可信，铁律）。
     */
    private static String normalizeSwitchState(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return "open".equals(v) || "close".equals(v) || "unknown".equals(v) ? v : null;
    }

    /** 门磁快照视图；未查询过返回全 null。 */
    @Transactional(readOnly = true)
    public DoorStateView doorState(String deviceId) {
        DeviceInfo device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found");
        }
        return new DoorStateView(device.getDoorState(), device.getLockState(), device.getDoorReportedAt());
    }

    /**
     * 按单拉取柜内视频（§4.3.2.11）：sessionId=将邑上报口径的 orderNo（免映射，V16 §4.3.2.7）。
     * 视频留在柜内 64G 循环覆盖——本指令只是「趁文件还在时取回」，无回执通道，
     * 结果以 uploadVideoUrl 落库后前端刷新为准；设备离线/文件已清则拿不到。
     */
    public void pullOrderVideo(Long operatorId, String sessionId) {
        ShoppingSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getDeviceId() == null || session.getDeviceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或无关联设备：" + sessionId);
        }
        String deviceId = session.getDeviceId();
        requireJiangyi(deviceId);
        try {
            gatewayClient.pullVideo(deviceId, sessionId);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("jiangyi pullVideo dispatch failed device={} orderNo={}: {}", deviceId, sessionId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "拉取视频下发失败（网关不可达或设备离线）");
        }
        auditService.appendLog(operatorId, "JIANGYI_PULL_VIDEO", "DEVICE", deviceId,
                "下发按单拉取视频（sendUploadErrorOrderVideos）orderNo=" + sessionId);
    }

    /** 信号强度视图；非将邑柜/未上报返回 signalStrength=null。 */
    @Transactional(readOnly = true)
    public SignalView signal(String deviceId) {
        DeviceInfo device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found");
        }
        return new SignalView(device.getSignalStrength(), device.getSignalReportedAt());
    }

    private void requireJiangyi(String deviceId) {
        if (!jiangyiDeviceDirectory.isJiangyi(deviceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非将邑柜机，不支持该操作");
        }
    }

    public record SignalView(Integer signalStrength, java.time.Instant reportedAt) {}

    public record DoorStateView(String doorState, String lockState, java.time.Instant reportedAt) {}
}
