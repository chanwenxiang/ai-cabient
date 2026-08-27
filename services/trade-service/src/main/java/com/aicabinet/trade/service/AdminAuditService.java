package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.AdminAuditLog;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditService {

    private final AdminAuditLogMapper repository;

    public AdminAuditService(AdminAuditLogMapper repository) {
        this.repository = repository;
    }

    @Transactional
    public void appendLog(Long operatorId, String action, String targetType, String targetId, String detail) {
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
