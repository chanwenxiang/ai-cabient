package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.AdminAuditLog;
import com.aicabinet.trade.repository.AdminAuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditService {

    private final AdminAuditLogRepository repository;

    public AdminAuditService(AdminAuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(Long operatorId, String action, String targetType, String targetId, String detail) {
        AdminAuditLog log = new AdminAuditLog();
        log.setOperatorId(operatorId);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        if (detail != null && detail.length() > 512) {
            detail = detail.substring(0, 512);
        }
        log.setDetail(detail);
        repository.save(log);
    }
}
