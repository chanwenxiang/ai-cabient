package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DistributedTransaction;
import com.aicabinet.trade.domain.TransactionStep;
import com.aicabinet.trade.repository.DistributedTransactionRepository;
import com.aicabinet.trade.repository.TransactionStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class TccTransactionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(TccTransactionCoordinator.class);
    
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_FAILED = "FAILED";
    
    public static final String STEP_TYPE_TRY = "TRY";
    public static final String STEP_TYPE_CONFIRM = "CONFIRM";
    public static final String STEP_TYPE_CANCEL = "CANCEL";
    
    @Autowired
    private DistributedTransactionRepository txRepository;
    
    @Autowired
    private TransactionStepRepository stepRepository;
    
    @Autowired
    private DistributedLockService lockService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    public String beginTransaction(String txType, Object payload) {
        String txId = UUID.randomUUID().toString().replace("-", "");
        
        try {
            DistributedTransaction tx = new DistributedTransaction();
            tx.setTxId(txId);
            tx.setTxType(txType);
            tx.setStatus(STATUS_PENDING);
            tx.setPayload(objectMapper.writeValueAsString(payload));
            tx.setRetryCount(0);
            tx.setMaxRetry(5);
            txRepository.save(tx);
            
            log.info("Transaction started: txId={}, type={}", txId, txType);
            return txId;
        } catch (Exception e) {
            log.error("Failed to begin transaction", e);
            throw new RuntimeException("Failed to begin transaction", e);
        }
    }
    
    @Transactional
    public boolean executeTryStep(String txId, String stepName, Supplier<Boolean> tryAction) {
        RLock lock = lockService.acquireLock("tx:" + txId, 30);
        if (lock == null) {
            log.warn("Failed to acquire lock for transaction: {}", txId);
            return false;
        }
        
        try {
            TransactionStep step = new TransactionStep();
            step.setTxId(txId);
            step.setStepName(stepName);
            step.setStepType(STEP_TYPE_TRY);
            step.setStatus(STATUS_PENDING);
            stepRepository.save(step);
            
            boolean success = tryAction.get();
            
            step.setStatus(success ? STATUS_CONFIRMED : STATUS_FAILED);
            step.setExecutedAt(Instant.now());
            stepRepository.save(step);
            
            log.info("Try step executed: txId={}, step={}, success={}", txId, stepName, success);
            return success;
        } catch (Exception e) {
            log.error("Try step failed: txId={}, step={}", txId, stepName, e);
            return false;
        } finally {
            lockService.releaseLock(lock);
        }
    }
    
    @Transactional
    public void confirmTransaction(String txId) {
        DistributedTransaction tx = txRepository.findById(txId).orElse(null);
        if (tx == null) {
            log.warn("Transaction not found: {}", txId);
            return;
        }
        
        List<TransactionStep> steps = stepRepository.findByTxIdOrderByStepOrder(txId);
        
        for (TransactionStep step : steps) {
            if (STEP_TYPE_TRY.equals(step.getStepType()) && STATUS_CONFIRMED.equals(step.getStatus())) {
                TransactionStep confirmStep = new TransactionStep();
                confirmStep.setTxId(txId);
                confirmStep.setStepName(step.getStepName() + "_confirm");
                confirmStep.setStepType(STEP_TYPE_CONFIRM);
                confirmStep.setStatus(STATUS_CONFIRMED);
                confirmStep.setExecutedAt(Instant.now());
                stepRepository.save(confirmStep);
            }
        }
        
        tx.setStatus(STATUS_CONFIRMED);
        tx.setCompletedAt(Instant.now());
        txRepository.save(tx);
        
        log.info("Transaction confirmed: txId={}", txId);
    }
    
    @Transactional
    public void cancelTransaction(String txId) {
        DistributedTransaction tx = txRepository.findById(txId).orElse(null);
        if (tx == null) {
            log.warn("Transaction not found: {}", txId);
            return;
        }
        
        List<TransactionStep> steps = stepRepository.findByTxIdOrderByStepOrder(txId);
        
        for (TransactionStep step : steps) {
            if (STEP_TYPE_TRY.equals(step.getStepType()) && STATUS_CONFIRMED.equals(step.getStatus())) {
                TransactionStep cancelStep = new TransactionStep();
                cancelStep.setTxId(txId);
                cancelStep.setStepName(step.getStepName() + "_cancel");
                cancelStep.setStepType(STEP_TYPE_CANCEL);
                cancelStep.setStatus(STATUS_CONFIRMED);
                cancelStep.setExecutedAt(Instant.now());
                stepRepository.save(cancelStep);
            }
        }
        
        tx.setStatus(STATUS_CANCELLED);
        tx.setCompletedAt(Instant.now());
        txRepository.save(tx);
        
        log.info("Transaction cancelled: txId={}", txId);
    }
    
    public List<DistributedTransaction> getPendingTransactions() {
        return txRepository.findByStatus(STATUS_PENDING);
    }
}
