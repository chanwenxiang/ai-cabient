package com.aicabinet.trade.service;

import com.aicabinet.common.dto.NotifyPrefDto;
import com.aicabinet.trade.domain.UserNotifyPref;
import com.aicabinet.trade.mapper.UserNotifyPrefMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 消费者通知偏好：缺省全部开启，用户可关闭不感兴趣的类别。 */
@Service
public class ConsumerNotifyPrefService {

    private static final Map<String, String> CATEGORY_LABELS = new LinkedHashMap<>();

    static {
        CATEGORY_LABELS.put("ORDER", "订单通知");
        CATEGORY_LABELS.put("RECHARGE", "充值通知");
        CATEGORY_LABELS.put("COUPON", "优惠券提醒");
        CATEGORY_LABELS.put("DISPUTE", "售后通知");
        CATEGORY_LABELS.put("POINTS", "积分提醒");
        CATEGORY_LABELS.put("RECALL", "活动与召回");
    }

    private final UserNotifyPrefMapper prefRepository;
    private final DistributedLockService distributedLockService;

    public ConsumerNotifyPrefService(UserNotifyPrefMapper prefRepository,
                                     DistributedLockService distributedLockService) {
        this.prefRepository = prefRepository;
        this.distributedLockService = distributedLockService;
    }

    @Transactional(readOnly = true)
    public List<NotifyPrefDto> getPrefs(Long userId) {
        Map<String, UserNotifyPref> saved = new LinkedHashMap<>();
        if (userId != null) {
            for (UserNotifyPref p : prefRepository.findByUserId(userId)) {
                saved.put(p.getCategory(), p);
            }
        }
        return CATEGORY_LABELS.entrySet().stream()
                .map(e -> new NotifyPrefDto(
                        e.getKey(),
                        e.getValue(),
                        !saved.containsKey(e.getKey())
                                || Boolean.TRUE.equals(saved.get(e.getKey()).getEnabled())))
                .toList();
    }

    @Transactional
    public NotifyPrefDto update(Long userId, String category, boolean enabled) {
        if (userId == null) {
            throw new IllegalArgumentException("userId required");
        }
        String cat = category == null ? "" : category.trim().toUpperCase();
        if (!CATEGORY_LABELS.containsKey(cat)) {
            throw new IllegalArgumentException("未知通知类别: " + category);
        }
        return runWithPrefLock(userId, cat, () -> doUpdate(userId, cat, enabled));
    }

    private NotifyPrefDto doUpdate(Long userId, String cat, boolean enabled) {
        UserNotifyPref pref = prefRepository.findByUserIdAndCategoryForUpdate(userId, cat)
                .orElseGet(UserNotifyPref::new);
        pref.setUserId(userId);
        pref.setCategory(cat);
        pref.setEnabled(enabled);
        pref.setUpdatedAt(Instant.now());
        if (pref.getId() == null) {
            prefRepository.insert(pref);
        } else {
            prefRepository.updateById(pref);
        }
        return new NotifyPrefDto(cat, CATEGORY_LABELS.get(cat), enabled);
    }

    static String notifyPrefLockKey(Long userId, String category) {
        return "notify:pref:" + userId + ":" + category;
    }

    private <T> T runWithPrefLock(Long userId, String category, java.util.function.Supplier<T> action) {
        String key = notifyPrefLockKey(userId, category);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "通知偏好处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    /** 通知分发前检查：缺省开启。 */
    @Transactional(readOnly = true)
    public boolean isEnabled(Long userId, String category) {
        if (userId == null || category == null || category.isBlank()) {
            return true;
        }
        return prefRepository.findByUserIdAndCategory(userId, category.trim().toUpperCase())
                .map(p -> Boolean.TRUE.equals(p.getEnabled()))
                .orElse(true);
    }
}
