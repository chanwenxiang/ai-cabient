package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceSkuInventoryRepository extends JpaRepository<DeviceSkuInventory, DeviceSkuInventoryId> {
    List<DeviceSkuInventory> findByIdDeviceId(String deviceId);
}
