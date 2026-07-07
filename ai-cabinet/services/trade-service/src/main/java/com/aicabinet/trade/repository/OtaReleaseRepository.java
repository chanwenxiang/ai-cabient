package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.OtaRelease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OtaReleaseRepository extends JpaRepository<OtaRelease, Long> {
    List<OtaRelease> findByStatusOrderByPublishedAtDesc(String status);
    Optional<OtaRelease> findFirstByChannelAndStatusOrderByPublishedAtDesc(String channel, String status);
}
