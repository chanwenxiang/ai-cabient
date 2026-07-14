package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    Page<AdminAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<AdminAuditLog> findByOperatorIdOrderByCreatedAtDesc(Long operatorId, Pageable pageable);

    List<AdminAuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtAsc(String targetType, String targetId);
}
