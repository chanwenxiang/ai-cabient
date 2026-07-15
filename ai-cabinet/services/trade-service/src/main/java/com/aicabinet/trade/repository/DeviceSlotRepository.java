package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DeviceSlot;
import com.aicabinet.trade.domain.DeviceSlotId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceSlotRepository extends JpaRepository<DeviceSlot, DeviceSlotId> {

    List<DeviceSlot> findByIdDeviceIdOrderByRowNoAscColNoAsc(String deviceId);

    long countByIdDeviceIdAndEnabledTrue(String deviceId);

    List<DeviceSlot> findByEnabledTrueAndLastPhysicalQtyIsNotNull();

    List<DeviceSlot> findByIdDeviceId(String deviceId);
}
