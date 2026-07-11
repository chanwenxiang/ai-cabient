package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.OpsException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.Optional;

public interface OpsExceptionRepository extends JpaRepository<OpsException, String> {
    Optional<OpsException> findFirstByDedupKeyAndStatusIn(String dedupKey, Collection<String> statuses);
    Page<OpsException> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<OpsException> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    Page<OpsException> findByDeviceIdInOrderByCreatedAtDesc(Collection<String> deviceIds, Pageable pageable);
    Page<OpsException> findByDeviceIdInAndStatusOrderByCreatedAtDesc(Collection<String> deviceIds, String status, Pageable pageable);
}
