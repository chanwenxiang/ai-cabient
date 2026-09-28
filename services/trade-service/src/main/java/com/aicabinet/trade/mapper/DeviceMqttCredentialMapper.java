package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceMqttCredential;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface DeviceMqttCredentialMapper
        extends com.aicabinet.trade.mapper.BaseTradeMapper<DeviceMqttCredential> {

    @Select("""
            SELECT * FROM device_mqtt_credential
            WHERE status = #{status}
            ORDER BY device_id
            """)
    List<DeviceMqttCredential> findByStatusOrderByDeviceId(@org.apache.ibatis.annotations.Param("status") String status);

    /** L2 轮换：显式清撤销标记（updateById 忽略 null 清不掉列，见 check-mybatis-null-clear）。 */
    @Update("""
            UPDATE device_mqtt_credential
            SET mqtt_username = #{username},
                mqtt_secret = #{secret},
                secret_sha256 = #{hash},
                status = 'ACTIVE',
                issued_by = #{operatorId},
                rotated_at = #{rotatedAt},
                revoked_at = NULL,
                revoke_reason = NULL
            WHERE device_id = #{deviceId}
            """)
    int clearRevocationAndRotate(@org.apache.ibatis.annotations.Param("deviceId") String deviceId,
                                 @org.apache.ibatis.annotations.Param("username") String username,
                                 @org.apache.ibatis.annotations.Param("secret") String secret,
                                 @org.apache.ibatis.annotations.Param("hash") String hash,
                                 @org.apache.ibatis.annotations.Param("operatorId") Long operatorId,
                                 @org.apache.ibatis.annotations.Param("rotatedAt") java.time.Instant rotatedAt);
}
