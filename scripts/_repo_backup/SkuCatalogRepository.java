package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.SkuCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkuCatalogRepository extends JpaRepository<SkuCatalog, String> {

    java.util.List<SkuCatalog> findAllByOrderBySkuIdAsc();
}
