package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.DeviceInfoMapper;
import java.security.SecureRandom;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 柜机业务编号：系统随机分配 12 位纯数字（无序、不可手填）。
 * 历史 CAB-* 或旧版 6–10 位递增编号仍保留。
 */
@Service
public class DeviceIdService {

    public static final int DEVICE_ID_DIGITS = 12;
    /** 新编号首位 1–9，避免全零与过短展示歧义。 */
    private static final int FIRST_DIGIT_MIN = 1;
    private static final int FIRST_DIGIT_MAX = 9;
    private static final Pattern STANDARD_DEVICE_ID = Pattern.compile("^[0-9]{12}$");
    private static final Pattern LEGACY_NUMERIC_DEVICE_ID = Pattern.compile("^[0-9]{6,10}$");
    private static final String ALLOC_LOCK = "device:id:allocate";
    private static final int MAX_ALLOC_ATTEMPTS = 48;

    private final SecureRandom secureRandom = new SecureRandom();
    private final DeviceInfoMapper deviceRepository;
    private final DistributedLockService distributedLockService;

    public DeviceIdService(DeviceInfoMapper deviceRepository, DistributedLockService distributedLockService) {
        this.deviceRepository = deviceRepository;
        this.distributedLockService = distributedLockService;
    }

    public static boolean isStandardDeviceId(String deviceId) {
        return deviceId != null && STANDARD_DEVICE_ID.matcher(deviceId.trim()).matches();
    }

    public static boolean isLegacyNumericDeviceId(String deviceId) {
        return deviceId != null && LEGACY_NUMERIC_DEVICE_ID.matcher(deviceId.trim()).matches();
    }

    /** 创建时仅允许系统分配，拒绝运营手填。 */
    public String resolveForCreate(String raw) {
        if (raw != null && !raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备编号由系统自动生成，不可指定");
        }
        return allocateRandomDeviceId();
    }

    public String allocateRandomDeviceId() {
        if (!distributedLockService.tryLock(ALLOC_LOCK, 30, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备编号分配中，请稍后重试");
        }
        try {
            for (int attempt = 0; attempt < MAX_ALLOC_ATTEMPTS; attempt++) {
                String candidate = randomDeviceId();
                if (deviceRepository.selectById(candidate) == null) {
                    return candidate;
                }
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备编号分配失败，请稍后重试");
        } finally {
            distributedLockService.unlock(ALLOC_LOCK);
        }
    }

    private String randomDeviceId() {
        char[] digits = new char[DEVICE_ID_DIGITS];
        digits[0] = (char) ('0' + FIRST_DIGIT_MIN + secureRandom.nextInt(FIRST_DIGIT_MAX - FIRST_DIGIT_MIN + 1));
        for (int i = 1; i < DEVICE_ID_DIGITS; i++) {
            digits[i] = (char) ('0' + secureRandom.nextInt(10));
        }
        return new String(digits);
    }
}
