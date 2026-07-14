package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.PurchaseOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, Long> {
    List<PurchaseOrderLine> findByPurchaseOrderIdOrderByLineIdAsc(Long purchaseOrderId);
}
