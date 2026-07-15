package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DeviceTemperatureReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DeviceTemperatureReadingRepository extends JpaRepository<DeviceTemperatureReading, Long> {

    @Query("""
            SELECT r FROM DeviceTemperatureReading r
            WHERE r.deviceId = :deviceId AND r.reportedAt >= :since
            ORDER BY r.reportedAt ASC
            """)
    List<DeviceTemperatureReading> findByDeviceIdSince(
            @Param("deviceId") String deviceId,
            @Param("since") Instant since);
}
