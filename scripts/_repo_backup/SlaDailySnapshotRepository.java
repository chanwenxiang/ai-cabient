package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.SlaDailySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface SlaDailySnapshotRepository extends JpaRepository<SlaDailySnapshot, LocalDate> {
    Optional<SlaDailySnapshot> findFirstByOrderBySnapshotDateDesc();
}
