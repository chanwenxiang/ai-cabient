package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceHttpCredential;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface DeviceHttpCredentialMapper
        extends BaseTradeMapper<DeviceHttpCredential> {

    /** L2 轮换：显式清撤销标记（updateById 忽略 null 清不掉列，见 check-mybatis-null-clear）。 */
    @Update("""
            UPDATE device_http_credential
            SET secret_sha256 = #{hash},
                status = 'ACTIVE',
                issued_by = #{operatorId},
                rotated_at = #{rotatedAt},
                revoked_at = NULL,
                revoke_reason = NULL
            WHERE device_id = #{deviceId}
            """)
    int clearRevocationAndRotate(@Param("deviceId") String deviceId,
                                 @Param("hash") String hash,
                                 @Param("operatorId") Long operatorId,
                                 @Param("rotatedAt") java.time.Instant rotatedAt);
}
