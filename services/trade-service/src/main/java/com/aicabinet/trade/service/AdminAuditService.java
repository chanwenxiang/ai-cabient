package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.AdminAuditLog;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    private final AdminAuditLogMapper repository;

    public AdminAuditService(AdminAuditLogMapper repository) {
        this.repository = repository;
    }

    /**
     * 写审计行。
     *
     * <p>🔴 {@code operator_id} 是 NOT NULL + FK(user_info)：系统链路（定时任务、支付回调重试）本就没有登录人，
     * 传 null 会让 <b>整个事务</b>在提交时抛约束错回滚——2026-11-06 服务器实测：选品复核每天一行都没落库，
     * 欠租锁柜「动作生效但审计丢」，提现备付拦截直接抛错。这里单点归一到
     * {@link SystemConfigService#SYSTEM_OPERATOR_ID}（0 = 系统），并留 WARN 便于发现漏传的调用点。</p>
     */
    @Transactional
    public void appendLog(Long operatorId, String action, String targetType, String targetId, String detail) {
        Long actor = operatorId;
        if (actor == null) {
            log.warn("admin audit without operator, falling back to SYSTEM_OPERATOR_ID action={} target={}",
                    action, targetType);
            actor = SystemConfigService.SYSTEM_OPERATOR_ID;
        }
        AdminAuditLog entry = new AdminAuditLog();
        entry.setOperatorId(actor);
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        if (detail != null && detail.length() > 512) {
            detail = detail.substring(0, 512);
        }
        entry.setDetail(detail);
        repository.save(entry);
    }
}
