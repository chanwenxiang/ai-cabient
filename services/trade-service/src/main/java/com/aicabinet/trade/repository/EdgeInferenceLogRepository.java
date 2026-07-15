package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.EdgeInferenceLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface EdgeInferenceLogRepository extends JpaRepository<EdgeInferenceLog, Long> {
    List<EdgeInferenceLog> findByDeviceId(String deviceId);
    
    List<EdgeInferenceLog> findBySessionId(String sessionId);
    
    @Query("SELECT AVG(l.inferenceTimeMs) FROM EdgeInferenceLog l WHERE l.deviceId = :deviceId AND l.inferenceAt >= :start")
    Double getAverageInferenceTime(String deviceId, Instant start);
    
    @Query("SELECT COUNT(l) FROM EdgeInferenceLog l WHERE l.deviceId = :deviceId AND l.status = 'SUCCESS' AND l.inferenceAt >= :start")
    Long countSuccessInferences(String deviceId, Instant start);
}
