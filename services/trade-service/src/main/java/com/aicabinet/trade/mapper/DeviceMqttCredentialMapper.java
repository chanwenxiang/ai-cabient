package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceMqttCredential;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface DeviceMqttCredentialMapper
        extends com.aicabinet.trade.mapper.BaseTradeMapper<DeviceMqttCredential> {

    @Select("""
            SELECT * FROM device_mqtt_credential
            WHERE status = #{status}
            ORDER BY device_id
            """)
    List<DeviceMqttCredential> findByStatusOrderByDeviceId(@org.apache.ibatis.annotations.Param("status") String status);
}
