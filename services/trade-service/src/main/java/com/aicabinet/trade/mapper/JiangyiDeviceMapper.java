package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.JiangyiDevice;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;

@Mapper
public interface JiangyiDeviceMapper extends BaseTradeMapper<JiangyiDevice> {

    /** SN 点查（入驻幂等判定、token 签发按 SN 查）。 */
    default JiangyiDevice findByDeviceSn(String deviceSn) {
        return selectOne(Wrappers.<JiangyiDevice>lambdaQuery()
                .eq(JiangyiDevice::getDeviceSn, deviceSn));
    }

    /** identifier 点查（设备 WS 握手路径标识反查）。 */
    default JiangyiDevice findByIdentifier(String identifier) {
        return selectOne(Wrappers.<JiangyiDevice>lambdaQuery()
                .eq(JiangyiDevice::getIdentifier, identifier));
    }

    /**
     * token 签发回执：只记签发时间（方案 §3：吊销才 token_version+1，签发不 bump）。
     * gateway 经内部接口调用，本表不落 token 本体。
     */
    default int markTokenIssued(String deviceId, Instant issuedAt) {
        return update(null, Wrappers.<JiangyiDevice>lambdaUpdate()
                .set(JiangyiDevice::getTokenIssuedAt, issuedAt)
                .set(JiangyiDevice::getUpdatedAt, Instant.now())
                .eq(JiangyiDevice::getDeviceId, deviceId));
    }

    /** 吊销：token_version+1，所有旧 token（claims.ver 落后于表值）即刻失效。 */
    default int bumpTokenVersion(String deviceId, Instant revokedAt) {
        return update(null, Wrappers.<JiangyiDevice>lambdaUpdate()
                .setSql("token_version = COALESCE(token_version, 0) + 1")
                .set(JiangyiDevice::getUpdatedAt, revokedAt)
                .eq(JiangyiDevice::getDeviceId, deviceId));
    }

    /** WS 在线心跳回执（gateway 维护）。 */
    default int markWsOnline(String deviceId, Instant onlineAt) {
        return update(null, Wrappers.<JiangyiDevice>lambdaUpdate()
                .set(JiangyiDevice::getLastWsOnlineAt, onlineAt)
                .set(JiangyiDevice::getUpdatedAt, Instant.now())
                .eq(JiangyiDevice::getDeviceId, deviceId));
    }

    // ---------- 模型同步面（CB-023） ----------

    /** 模型下发回执回填：model_name/classes_version（classes_version=我方计算的 classes.txt sha256 前 12 位）。 */
    default int updateModelInfo(String deviceId, String modelName, String classesVersion, Instant at) {
        return update(null, Wrappers.<JiangyiDevice>lambdaUpdate()
                .set(JiangyiDevice::getModelName, modelName)
                .set(JiangyiDevice::getClassesVersion, classesVersion)
                .set(JiangyiDevice::getUpdatedAt, at)
                .eq(JiangyiDevice::getDeviceId, deviceId));
    }

    /** 登记工控机机型（将邑原值 "76"/"88"，admin 录入/采集链路回填）。 */
    default int updateIndustrialControlModel(String deviceId, String industrialControlModel, Instant at) {
        return update(null, Wrappers.<JiangyiDevice>lambdaUpdate()
                .set(JiangyiDevice::getIndustrialControlModel, industrialControlModel)
                .set(JiangyiDevice::getUpdatedAt, at)
                .eq(JiangyiDevice::getDeviceId, deviceId));
    }

    /** 进入采集模式（gather_locked_at=at，营业开门 409）。 */
    default int markGatherLocked(String deviceId, Instant at) {
        return update(null, Wrappers.<JiangyiDevice>lambdaUpdate()
                .set(JiangyiDevice::getGatherLockedAt, at)
                .set(JiangyiDevice::getUpdatedAt, at)
                .eq(JiangyiDevice::getDeviceId, deviceId));
    }

    /** 退出采集模式（清锁）。 */
    default int markGatherUnlocked(String deviceId, Instant at) {
        return update(null, Wrappers.<JiangyiDevice>lambdaUpdate()
                .set(JiangyiDevice::getGatherLockedAt, null)
                .set(JiangyiDevice::getUpdatedAt, at)
                .eq(JiangyiDevice::getDeviceId, deviceId));
    }
}
