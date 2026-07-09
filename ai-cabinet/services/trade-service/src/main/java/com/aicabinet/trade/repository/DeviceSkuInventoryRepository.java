package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DeviceSkuInventoryRepository extends JpaRepository<DeviceSkuInventory, DeviceSkuInventoryId> {
    List<DeviceSkuInventory> findByIdDeviceId(String deviceId);

    @Query("SELECT COUNT(i) FROM DeviceSkuInventory i WHERE i.quantity <= i.lowThreshold")
    long countLowStock();

    @Query("SELECT i FROM DeviceSkuInventory i WHERE i.quantity <= i.lowThreshold ORDER BY i.id.deviceId, i.id.skuId")
    List<DeviceSkuInventory> findLowStock();
}
