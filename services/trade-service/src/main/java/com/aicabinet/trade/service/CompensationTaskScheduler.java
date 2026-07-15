package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CompensationTask;
import com.aicabinet.trade.domain.DistributedTransaction;
import com.aicabinet.trade.repository.CompensationTaskRepository;
import com.aicabinet.trade.repository.DistributedTransactionRepository;
import org.redisson.api.RLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class CompensationTaskScheduler {
    private static final Logger log = LoggerFactory.getLogger(CompensationTaskScheduler.class);
    
    @Autowired
    private CompensationTaskRepository taskRepository;
    
    @Autowired
    private DistributedTransactionRepository txRepository;
    
    @Autowired
    private DistributedLockService lockService;
    
    @Autowired
    private TccTransactionCoordinator txCoordinator;
    
    @Scheduled(fixedDelay = 30000)
    public void processCompensationTasks() {
        RLock lock = lockService.acquireLock("compensation:scheduler", 60);
        if (lock == null) {
            log.debug("Another instance is processing compensation tasks");
            return;
        }
        
        try {
            List<CompensationTask> tasks = taskRepository.findExecutableTasks(Instant.now());
            log.info("Found {} compensation tasks to process", tasks.size());
            
            for (CompensationTask task : tasks) {
                try {
                    processTask(task);
                } catch (Exception e) {
                    log.error("Failed to process compensation task: {}", task.getTaskId(), e);
                }
            }
        } finally {
            lockService.releaseLock(lock);
        }
    }
    
    @Transactional
    public void processTask(CompensationTask task) {
        task.setStatus("PROCESSING");
        taskRepository.save(task);
        
        try {
            DistributedTransaction tx = txRepository.findById(task.getTxId()).orElse(null);
            if (tx == null) {
                task.setStatus("FAILED");
                task.setResult("Transaction not found");
                taskRepository.save(task);
                return;
            }
            
            boolean success = executeCompensation(tx);
            
            task.setStatus(success ? "COMPLETED" : "FAILED");
            task.setResult(success ? "Compensation executed successfully" : "Compensation failed");
            task.setExecutedAt(Instant.now());
            taskRepository.save(task);
            
            log.info("Compensation task completed: taskId={}, success={}", task.getTaskId(), success);
        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setResult("Error: " + e.getMessage());
            taskRepository.save(task);
            log.error("Compensation task error: taskId={}", task.getTaskId(), e);
        }
    }
    
    private boolean executeCompensation(DistributedTransaction tx) {
        if ("CANCEL".equals(tx.getCompensationSql())) {
            txCoordinator.cancelTransaction(tx.getTxId());
            return true;
        }
        
        return true;
    }
    
    public void scheduleCompensation(String txId, String taskType, int delaySeconds) {
        CompensationTask task = new CompensationTask();
        task.setTxId(txId);
        task.setTaskType(taskType);
        task.setScheduledAt(Instant.now().plusSeconds(delaySeconds));
        task.setPriority(0);
        task.setStatus("PENDING");
        taskRepository.save(task);
        
        log.info("Compensation scheduled: txId={}, type={}, delay={}s", txId, taskType, delaySeconds);
    }
    
    @Scheduled(fixedDelay = 60000)
    public void retryFailedTransactions() {
        RLock lock = lockService.acquireLock("tx:retry:scheduler", 60);
        if (lock == null) {
            return;
        }
        
        try {
            List<DistributedTransaction> retryable = txRepository.findRetryableTransactions();
            log.info("Found {} transactions to retry", retryable.size());
            
            for (DistributedTransaction tx : retryable) {
                try {
                    tx.setRetryCount(tx.getRetryCount() + 1);
                    txRepository.save(tx);
                    
                    log.info("Retrying transaction: txId={}, attempt={}", tx.getTxId(), tx.getRetryCount());
                } catch (Exception e) {
                    log.error("Failed to retry transaction: {}", tx.getTxId(), e);
                }
            }
        } finally {
            lockService.releaseLock(lock);
        }
    }
}
