package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.WarehouseInventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WarehouseInventoryRepository extends JpaRepository<WarehouseInventory, Long> {

    List<WarehouseInventory> findByWarehouseIdAndQuantityGreaterThanOrderByExpiryDateAsc(String warehouseId, int quantity);

    List<WarehouseInventory> findByWarehouseIdOrderByExpiryDateAsc(String warehouseId);

    Optional<WarehouseInventory> findByWarehouseIdAndSkuIdAndBatchNo(String warehouseId, String skuId, String batchNo);

    List<WarehouseInventory> findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(String warehouseId, String skuId);
}
