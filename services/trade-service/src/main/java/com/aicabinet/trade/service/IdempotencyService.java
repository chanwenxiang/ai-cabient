package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.IdempotencyKey;
import com.aicabinet.trade.mapper.IdempotencyKeyMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 通用幂等表（{@code idempotency_key}）辅助服务。
 * <p>
 * <b>资金相关路径的幂等真源是业务表唯一键</b>：
 * {@code shopping_session.idempotency_key}、
 * {@code recharge_order.idempotency_key}、
 * {@code payment_operation.idempotency_key}。
 * 不要再并行写入本表，以免与业务表不一致（BE-002）。
 * <p>
 * 开门 / 充值 / 扣款请优先使用上述业务键。本服务仅保留给缺少专用唯一列的运营类接口，
 * 新代码不应再注入本类。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    @Autowired
    private IdempotencyKeyMapper repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DistributedLockService distributedLockService;

    private static final int DEFAULT_EXPIRE_HOURS = 24;

    @Transactional(readOnly = true)
    public <T> Optional<T> checkIdempotency(String idempotencyKey, Class<T> responseType) {
        Optional<IdempotencyKey> key = repository.findById(idempotencyKey);

        if (key.isEmpty()) {
            return Optional.empty();
        }

        IdempotencyKey entity = key.get();

        if (entity.getExpireAt().isBefore(Instant.now())) {
            log.info("幂等键已过期 key={}", idempotencyKey);
            return Optional.empty();
        }

        if (entity.getResponseData() != null && responseType != Void.class) {
            try {
                T response = objectMapper.readValue(
                        entity.getResponseData().toString(),
                        responseType
                );
                log.info("幂等命中 key={}", idempotencyKey);
                return Optional.of(response);
            } catch (Exception e) {
                log.error("幂等响应反序列化失败 key={}", idempotencyKey, e);
                return Optional.empty();
            }
        }

        log.info("幂等键存在但无响应体 key={}", idempotencyKey);
        return Optional.empty();
    }

    @Transactional
    public void saveIdempotency(String idempotencyKey, String businessType, String businessId, Object response) {
        runWithIdempotencyLock(idempotencyKey, () -> {
            doSaveIdempotency(idempotencyKey, businessType, businessId, response);
            return null;
        });
    }

    private void doSaveIdempotency(String idempotencyKey, String businessType, String businessId, Object response) {
        if (repository.findByIdForUpdate(idempotencyKey).isPresent()) {
            log.info("幂等键已存在，跳过重复写入 key={}", idempotencyKey);
            return;
        }
        IdempotencyKey entity = new IdempotencyKey();
        entity.setIdempotencyKey(idempotencyKey);
        entity.setBusinessType(businessType);
        entity.setBusinessId(businessId);
        entity.setExpireAt(Instant.now().plus(DEFAULT_EXPIRE_HOURS, ChronoUnit.HOURS));

        if (response != null) {
            try {
                entity.setResponseData(objectMapper.valueToTree(response));
            } catch (Exception e) {
                log.error("幂等响应序列化失败 key={}", idempotencyKey, e);
            }
        }

        repository.save(entity);
        log.info("幂等键已保存 key={} businessType={} businessId={}",
                idempotencyKey, businessType, businessId);
    }

    @Transactional
    public void saveIdempotency(String idempotencyKey, String businessType, String businessId) {
        saveIdempotency(idempotencyKey, businessType, businessId, null);
    }

    @Transactional
    public void deleteIdempotency(String idempotencyKey) {
        runWithIdempotencyLock(idempotencyKey, () -> {
            repository.deleteById(idempotencyKey);
            log.info("幂等键已删除 key={}", idempotencyKey);
            return null;
        });
    }

    static String idempotencyLockKey(String idempotencyKey) {
        return "idem:" + idempotencyKey.trim();
    }

    private <T> T runWithIdempotencyLock(String idempotencyKey, java.util.function.Supplier<T> action) {
        String key = idempotencyLockKey(idempotencyKey);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "幂等处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }
}
