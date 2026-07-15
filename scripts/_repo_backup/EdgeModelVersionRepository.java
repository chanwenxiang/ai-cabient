package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.EdgeModelVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface EdgeModelVersionRepository extends JpaRepository<EdgeModelVersion, Long> {
    Optional<EdgeModelVersion> findByModelNameAndVersion(String modelName, String version);
    
    List<EdgeModelVersion> findByModelNameOrderByCreatedAtDesc(String modelName);
    
    Optional<EdgeModelVersion> findFirstByModelNameAndStatusOrderByCreatedAtDesc(String modelName, String status);
}
