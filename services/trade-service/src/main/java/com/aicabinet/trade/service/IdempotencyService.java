package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.IdempotencyKey;
import com.aicabinet.trade.repository.IdempotencyKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
public class IdempotencyService {
    
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    
    @Autowired
    private IdempotencyKeyRepository repository;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    private static final int DEFAULT_EXPIRE_HOURS = 24;
    
    @Transactional(readOnly = true)
    public <T> Optional<T> checkIdempotency(String idempotencyKey, Class<T> responseType) {
        Optional<IdempotencyKey> key = repository.findById(idempotencyKey);
        
        if (key.isEmpty()) {
            return Optional.empty();
        }
        
        IdempotencyKey entity = key.get();
        
        if (entity.getExpireAt().isBefore(Instant.now())) {
            log.info("楠炲倻鐡戦柨顔煎嚒鏉╁洦婀?key={}", idempotencyKey);
            return Optional.empty();
        }
        
        if (entity.getResponseData() != null && responseType != Void.class) {
            try {
                T response = objectMapper.readValue(
                    entity.getResponseData().toString(), 
                    responseType
                );
                log.info("楠炲倻鐡戦柨顔兼嚒娑?key={}", idempotencyKey);
                return Optional.of(response);
            } catch (Exception e) {
                log.error("鐟欙絾鐎界紓鎾崇摠閸濆秴绨叉径杈Е key={}", idempotencyKey, e);
                return Optional.empty();
            }
        }
        
        log.info("楠炲倻鐡戦柨顔兼嚒娑擃叏绱濋弮鐘电处鐎涙ê鎼锋惔?key={}", idempotencyKey);
        return Optional.empty();
    }
    
    @Transactional
    public void saveIdempotency(String idempotencyKey, String businessType, String businessId, Object response) {
        IdempotencyKey entity = new IdempotencyKey();
        entity.setIdempotencyKey(idempotencyKey);
        entity.setBusinessType(businessType);
        entity.setBusinessId(businessId);
        entity.setExpireAt(Instant.now().plus(DEFAULT_EXPIRE_HOURS, ChronoUnit.HOURS));
        
        if (response != null) {
            try {
                entity.setResponseData(objectMapper.valueToTree(response));
            } catch (Exception e) {
                log.error("鎼村繐鍨崠鏍ф惙鎼存柨銇戠拹?key={}", idempotencyKey, e);
            }
        }
        
        repository.save(entity);
        log.info("娣囨繂鐡ㄩ獮鍌滅搼闁?key={} businessType={} businessId={}", idempotencyKey, businessType, businessId);
    }
    
    @Transactional
    public void saveIdempotency(String idempotencyKey, String businessType, String businessId) {
        saveIdempotency(idempotencyKey, businessType, businessId, null);
    }
    
    @Transactional
    public void deleteIdempotency(String idempotencyKey) {
        repository.deleteById(idempotencyKey);
        log.info("閸掔娀娅庨獮鍌滅搼闁?key={}", idempotencyKey);
    }
}