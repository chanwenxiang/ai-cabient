package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.SkuVisionMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkuVisionMappingRepository extends JpaRepository<SkuVisionMapping, String> {

    List<SkuVisionMapping> findByMappingSource(String mappingSource);
}
