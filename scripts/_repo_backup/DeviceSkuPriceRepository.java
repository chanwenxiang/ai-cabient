package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DeviceSkuPrice;
import com.aicabinet.trade.domain.DeviceSkuPriceId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DeviceSkuPriceRepository extends JpaRepository<DeviceSkuPrice, DeviceSkuPriceId> {

    List<DeviceSkuPrice> findByIdDeviceIdIn(Collection<String> deviceIds);

    List<DeviceSkuPrice> findByIdDeviceId(String deviceId);
}
