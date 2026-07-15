package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.MerchantNotifyLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface MerchantNotifyLogRepository extends JpaRepository<MerchantNotifyLog, Long> {

    Optional<MerchantNotifyLog> findFirstByUserIdAndDigestAndSentAtAfter(
            Long userId, String digest, Instant since);
}
