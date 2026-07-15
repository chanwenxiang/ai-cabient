package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
    List<Announcement> findByStatusOrderByPublishAtDesc(String status);
    List<Announcement> findByTargetScopeAndStatusOrderByPublishAtDesc(String targetScope, String status);
}
