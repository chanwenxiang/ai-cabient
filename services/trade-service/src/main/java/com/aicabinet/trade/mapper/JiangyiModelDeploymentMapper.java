package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.JiangyiModelDeployment;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface JiangyiModelDeploymentMapper extends BaseTradeMapper<JiangyiModelDeployment> {

    /** 某设备是否存在未完成下发（幂等：有 SENT 未决时不允许重复下发）。 */
    default boolean hasPendingSent(String deviceId) {
        return selectCount(Wrappers.<JiangyiModelDeployment>lambdaQuery()
                .eq(JiangyiModelDeployment::getDeviceId, deviceId)
                .eq(JiangyiModelDeployment::getStatus, "SENT")) > 0;
    }

    default List<JiangyiModelDeployment> listByDevice(String deviceId) {
        return selectList(Wrappers.<JiangyiModelDeployment>lambdaQuery()
                .eq(JiangyiModelDeployment::getDeviceId, deviceId)
                .orderByDesc(JiangyiModelDeployment::getSentAt));
    }

    default Optional<JiangyiModelDeployment> byId(long id) {
        return Optional.ofNullable(selectById(id));
    }

    /** 某设备某模型最新一次 SENT（设备回执只报模型名，按此定位待确认行）。 */
    default Optional<JiangyiModelDeployment> findLatestSent(String deviceId, String modelName) {
        return Optional.ofNullable(selectOne(Wrappers.<JiangyiModelDeployment>lambdaQuery()
                .eq(JiangyiModelDeployment::getDeviceId, deviceId)
                .eq(JiangyiModelDeployment::getModelName, modelName)
                .eq(JiangyiModelDeployment::getStatus, "SENT")
                .orderByDesc(JiangyiModelDeployment::getSentAt)
                .last("LIMIT 1")));
    }

    /** 回执确认（仅 SENT → CONFIRMED，防串包/防重放：其他状态 no-op 返回 0）。 */
    default int confirm(long id, String classesVersion, Instant confirmedAt) {
        return update(null, Wrappers.<JiangyiModelDeployment>lambdaUpdate()
                .set(JiangyiModelDeployment::getStatus, "CONFIRMED")
                .set(JiangyiModelDeployment::getClassesVersion, classesVersion)
                .set(JiangyiModelDeployment::getConfirmedAt, confirmedAt)
                .eq(JiangyiModelDeployment::getId, id)
                .eq(JiangyiModelDeployment::getStatus, "SENT"));
    }

    /** 超时置败（仅 SENT → FAILED，watchdog 幂等）。 */
    default int fail(long id, String reason, Instant at) {
        return update(null, Wrappers.<JiangyiModelDeployment>lambdaUpdate()
                .set(JiangyiModelDeployment::getStatus, "FAILED")
                .set(JiangyiModelDeployment::getFailReason, reason)
                .set(JiangyiModelDeployment::getConfirmedAt, at)
                .eq(JiangyiModelDeployment::getId, id)
                .eq(JiangyiModelDeployment::getStatus, "SENT"));
    }
}
