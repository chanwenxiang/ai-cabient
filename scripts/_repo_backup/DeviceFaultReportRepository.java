package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DeviceFaultReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceFaultReportRepository extends JpaRepository<DeviceFaultReport, Long> {
}
