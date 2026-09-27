package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.MerchantMapper;
import java.security.SecureRandom;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 商户业务编号：系统随机分配 12 位纯数字（无序、不可手填）。
 * 历史 {@code MCH-*} 字符串编号仍保留可读。
 */
@Service
public class MerchantIdService {

    public static final int MERCHANT_ID_DIGITS = 12;
    private static final int FIRST_DIGIT_MIN = 1;
    private static final int FIRST_DIGIT_MAX = 9;
    private static final Pattern STANDARD_MERCHANT_ID = Pattern.compile("^[0-9]{12}$");
    private static final String ALLOC_LOCK = "merchant:id:allocate";
    private static final int MAX_ALLOC_ATTEMPTS = 48;

    private final SecureRandom secureRandom = new SecureRandom();
    private final MerchantMapper merchantRepository;
    private final DistributedLockService distributedLockService;

    public MerchantIdService(MerchantMapper merchantRepository,
                             DistributedLockService distributedLockService) {
        this.merchantRepository = merchantRepository;
        this.distributedLockService = distributedLockService;
    }

    public static boolean isStandardMerchantId(String merchantId) {
        return merchantId != null && STANDARD_MERCHANT_ID.matcher(merchantId.trim()).matches();
    }

    /** 创建时仅允许系统分配，拒绝运营手填。 */
    public String resolveForCreate(String raw) {
        if (raw != null && !raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商户编号由系统自动生成，不可指定");
        }
        return allocateRandomMerchantId();
    }

    public String allocateRandomMerchantId() {
        if (!distributedLockService.tryLock(ALLOC_LOCK, 30, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商户编号分配中，请稍后重试");
        }
        try {
            for (int attempt = 0; attempt < MAX_ALLOC_ATTEMPTS; attempt++) {
                String candidate = randomMerchantId();
                if (merchantRepository.selectById(candidate) == null) {
                    return candidate;
                }
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商户编号分配失败，请稍后重试");
        } finally {
            distributedLockService.unlock(ALLOC_LOCK);
        }
    }

    private String randomMerchantId() {
        char[] digits = new char[MERCHANT_ID_DIGITS];
        digits[0] = (char) ('0' + FIRST_DIGIT_MIN + secureRandom.nextInt(FIRST_DIGIT_MAX - FIRST_DIGIT_MIN + 1));
        for (int i = 1; i < MERCHANT_ID_DIGITS; i++) {
            digits[i] = (char) ('0' + secureRandom.nextInt(10));
        }
        return new String(digits);
    }
}
