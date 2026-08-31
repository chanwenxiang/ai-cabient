package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.DeviceInfoMapper;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 柜机业务编号：新设备默认系统分配 6–10 位纯数字（与机身贴码一致）。
 * 演示/历史 CAB-* 编号仍保留，不参与自动递增序列。
 */
@Service
public class DeviceIdService {

    public static final long MIN_NUMERIC_DEVICE_ID = 100_001L;
    public static final int MIN_DIGITS = 6;
    public static final int MAX_DIGITS = 10;
    private static final Pattern NUMERIC_DEVICE_ID = Pattern.compile("^[0-9]{6,10}$");
    private static final String ALLOC_LOCK = "device:id:allocate";

    private final DeviceInfoMapper deviceRepository;
    private final DistributedLockService distributedLockService;

    public DeviceIdService(DeviceInfoMapper deviceRepository, DistributedLockService distributedLockService) {
        this.deviceRepository = deviceRepository;
        this.distributedLockService = distributedLockService;
    }

    public static boolean isNumericDeviceId(String deviceId) {
        return deviceId != null && NUMERIC_DEVICE_ID.matcher(deviceId.trim()).matches();
    }

    /** 预览下一个建议编号（不占用）。 */
    public String peekNextNumericDeviceId() {
        return String.valueOf(nextNumericValue());
    }

    /** 创建时解析：空则系统分配，非空则校验为纯数字。 */
    public String resolveForCreate(String raw) {
        if (raw == null || raw.isBlank()) {
            return allocateNextNumericDeviceId();
        }
        String normalized = raw.trim();
        if (!isNumericDeviceId(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备编号须为 6–10 位数字");
        }
        return normalized;
    }

    public String allocateNextNumericDeviceId() {
        if (!distributedLockService.tryLock(ALLOC_LOCK, 30, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备编号分配中，请稍后重试");
        }
        try {
            long candidate = nextNumericValue();
            while (deviceRepository.selectById(String.valueOf(candidate)) != null) {
                candidate++;
                assertWithinDigitLimit(candidate);
            }
            return String.valueOf(candidate);
        } finally {
            distributedLockService.unlock(ALLOC_LOCK);
        }
    }

    private long nextNumericValue() {
        Long max = deviceRepository.maxNumericDeviceIdRaw();
        long base = max == null ? 0L : max;
        long next = Math.max(MIN_NUMERIC_DEVICE_ID, base + 1);
        assertWithinDigitLimit(next);
        return next;
    }

    private static void assertWithinDigitLimit(long value) {
        if (String.valueOf(value).length() > MAX_DIGITS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "数字设备编号已用尽");
        }
    }
}
