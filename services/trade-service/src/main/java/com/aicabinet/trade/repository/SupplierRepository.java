package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierRepository extends JpaRepository<Supplier, String> {
    List<Supplier> findAllByOrderByCreatedAtDesc();
}
