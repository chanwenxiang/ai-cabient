package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceStatusDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.repository.DeviceInfoRepository;
import com.aicabinet.trade.repository.ShoppingSessionRepository;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class DeviceValidationService {

    private static final List<SessionState> ACTIVE_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING, SessionState.RECOGNIZING
    );

    private final DeviceInfoRepository deviceInfoRepository;
    private final ShoppingSessionRepository sessionRepository;

    public DeviceValidationService(DeviceInfoRepository deviceInfoRepository,
                                   ShoppingSessionRepository sessionRepository) {
        this.deviceInfoRepository = deviceInfoRepository;
        this.sessionRepository = sessionRepository;
    }

    public DeviceInfo requireDevice(String deviceId) {
        return deviceInfoRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
    }

    public DeviceStatusDto getDeviceStatus(String deviceId) {
        DeviceInfo device = requireDevice(deviceId);
        var active = sessionRepository.findByDeviceIdAndStateIn(deviceId, ACTIVE_STATES);
        String activeSessionId = active.isEmpty() ? null : active.get(0).getSessionId();
        boolean online = "ONLINE".equalsIgnoreCase(device.getOnlineStatus());
        return new DeviceStatusDto(
                device.getDeviceId(),
                device.getDeviceName(),
                device.getOnlineStatus(),
                online,
                active.isEmpty(),
                activeSessionId
        );
    }

    /** 参考旧系统：门已开/开门中时不允许再次开门 */
    public void ensureDeviceAvailable(String deviceId) {
        var active = sessionRepository.findByDeviceIdAndStateIn(deviceId, ACTIVE_STATES);
        if (!active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.DEVICE_BUSY);
        }
    }
}
