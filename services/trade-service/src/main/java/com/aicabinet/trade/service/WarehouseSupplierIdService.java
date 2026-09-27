package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.SupplierMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import java.security.SecureRandom;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 仓库 / 供应商业务编号：与柜机、商户一致，系统随机 12 位纯数字，禁止手填新号。
 * 历史 {@code WH-*} / {@code SUP-*} 字符串编号仍可读。
 */
@Service
public class WarehouseSupplierIdService {

    public static final int ID_DIGITS = 12;
    private static final int FIRST_DIGIT_MIN = 1;
    private static final int FIRST_DIGIT_MAX = 9;
    private static final Pattern STANDARD_ID = Pattern.compile("^[0-9]{12}$");
    private static final String WH_LOCK = "warehouse:id:allocate";
    private static final String SUP_LOCK = "supplier:id:allocate";
    private static final int MAX_ALLOC_ATTEMPTS = 48;

    private final SecureRandom secureRandom = new SecureRandom();
    private final WarehouseMapper warehouseRepository;
    private final SupplierMapper supplierRepository;
    private final DistributedLockService distributedLockService;

    public WarehouseSupplierIdService(WarehouseMapper warehouseRepository,
                                      SupplierMapper supplierRepository,
                                      DistributedLockService distributedLockService) {
        this.warehouseRepository = warehouseRepository;
        this.supplierRepository = supplierRepository;
        this.distributedLockService = distributedLockService;
    }

    public static boolean isStandardId(String id) {
        return id != null && STANDARD_ID.matcher(id.trim()).matches();
    }

    public String resolveWarehouseIdForCreate(String raw) {
        if (raw != null && !raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库编号由系统自动生成，不可指定");
        }
        return allocateWarehouseId();
    }

    public String resolveSupplierIdForCreate(String raw) {
        if (raw != null && !raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "供应商编号由系统自动生成，不可指定");
        }
        return allocateSupplierId();
    }

    public String allocateWarehouseId() {
        return allocate(WH_LOCK, "仓库", id -> warehouseRepository.selectById(id) == null);
    }

    public String allocateSupplierId() {
        return allocate(SUP_LOCK, "供应商", id -> supplierRepository.selectById(id) == null);
    }

    private String allocate(String lockKey, String label, java.util.function.Predicate<String> free) {
        if (!distributedLockService.tryLock(lockKey, 30, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, label + "编号分配中，请稍后重试");
        }
        try {
            for (int attempt = 0; attempt < MAX_ALLOC_ATTEMPTS; attempt++) {
                String candidate = randomId();
                if (free.test(candidate)) {
                    return candidate;
                }
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, label + "编号分配失败，请稍后重试");
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    private String randomId() {
        char[] digits = new char[ID_DIGITS];
        digits[0] = (char) ('0' + FIRST_DIGIT_MIN + secureRandom.nextInt(FIRST_DIGIT_MAX - FIRST_DIGIT_MIN + 1));
        for (int i = 1; i < ID_DIGITS; i++) {
            digits[i] = (char) ('0' + secureRandom.nextInt(10));
        }
        return new String(digits);
    }
}
