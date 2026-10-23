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

    /** 激活开关（审计 A1/P0-3）：enabled=false 时调用方须随后 bumpTokenVersion 吊销存量 token。 */
    default int setAuthEnabled(String deviceId, boolean enabled, Instant at) {
        return update(null, Wrappers.<JiangyiDevice>lambdaUpdate()
                .set(JiangyiDevice::getAuthEnabled, enabled)
                .set(JiangyiDevice::getUpdatedAt, at)
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

    /**
     * 认领柜机当前模型版本（V400）：仅在 classes_version 未知（历史厂商直推、我方无下发记录）时写入，
     * 已有版本不覆盖——已知版本与审阅版本不符必须走人工确认，不能让一次重算顺手改掉版本。
     */
    default int adoptClassesVersionIfAbsent(String deviceId, String classesVersion, Instant at) {
        return update(null, Wrappers.<JiangyiDevice>lambdaUpdate()
                .set(JiangyiDevice::getClassesVersion, classesVersion)
                .set(JiangyiDevice::getUpdatedAt, at)
                .eq(JiangyiDevice::getDeviceId, deviceId)
                .and(w -> w.isNull(JiangyiDevice::getClassesVersion)
                        .or().eq(JiangyiDevice::getClassesVersion, "")));
    }

    /**
     * 映射复核通过（V400）：记录「审阅的是哪个 classes 版本」。
     * <p>🔴 只写戳，不写 classes_version —— 版本是柜机事实，复核是对该事实的确认；两者混写会让
     * 「复核」能把未知模型直接认证成审阅版本。</p>
     */
    default int stampMappingVerified(String deviceId, String classesVersion, Instant at) {
        return update(null, Wrappers.<JiangyiDevice>lambdaUpdate()
                .set(JiangyiDevice::getMappingVerifiedVersion, classesVersion)
                .set(JiangyiDevice::getMappingVerifiedAt, at)
                .set(JiangyiDevice::getUpdatedAt, at)
                .eq(JiangyiDevice::getDeviceId, deviceId));
    }

    /** 撤销复核戳（未知模型到达/下发超时：无法认定柜机到底在跑哪版 ⇒ 结算 fail-closed）。 */
    default int clearMappingVerification(String deviceId, Instant at) {
        return update(null, Wrappers.<JiangyiDevice>lambdaUpdate()
                .set(JiangyiDevice::getMappingVerifiedVersion, null)
                .set(JiangyiDevice::getMappingVerifiedAt, null)
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
