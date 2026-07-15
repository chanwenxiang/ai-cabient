package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DeviceInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DeviceInfoRepository extends JpaRepository<DeviceInfo, String> {

    long countByMerchantId(String merchantId);

    long countByOnlineStatusNot(String onlineStatus);

    List<DeviceInfo> findTop10ByOnlineStatusNotOrderByUpdatedAtAsc(String onlineStatus);

    List<DeviceInfo> findByMerchantIdIn(Collection<String> merchantIds);
}
