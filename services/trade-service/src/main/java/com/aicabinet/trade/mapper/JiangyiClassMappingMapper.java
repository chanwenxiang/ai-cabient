package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface JiangyiClassMappingMapper extends BaseTradeMapper<JiangyiClassMapping> {

    /** ACTIVE 映射点查（gateway 识别上报解析链路用；未命中 = fail-closed 转 DISPUTED）。 */
    default Optional<JiangyiClassMapping> findActive(String deviceId, Integer classId) {
        return Optional.ofNullable(selectOne(Wrappers.<JiangyiClassMapping>lambdaQuery()
                .eq(JiangyiClassMapping::getDeviceId, deviceId)
                .eq(JiangyiClassMapping::getClassId, classId)
                .eq(JiangyiClassMapping::getStatus, "ACTIVE")));
    }

    /** 不限状态点查（upsert 判存 / 回读）。 */
    default Optional<JiangyiClassMapping> findOne(String deviceId, Integer classId) {
        return Optional.ofNullable(selectOne(Wrappers.<JiangyiClassMapping>lambdaQuery()
                .eq(JiangyiClassMapping::getDeviceId, deviceId)
                .eq(JiangyiClassMapping::getClassId, classId)));
    }

    default List<JiangyiClassMapping> listByDevice(String deviceId) {
        return selectList(Wrappers.<JiangyiClassMapping>lambdaQuery()
                .eq(JiangyiClassMapping::getDeviceId, deviceId)
                .orderByAsc(JiangyiClassMapping::getClassId));
    }

    /** 幂等 upsert：UNIQUE(device_id, class_id) 判存，存在则更新映射内容，否则插入。 */
    default void upsert(JiangyiClassMapping row) {
        row.setUpdatedAt(Instant.now());
        JiangyiClassMapping existing = selectOne(Wrappers.<JiangyiClassMapping>lambdaQuery()
                .eq(JiangyiClassMapping::getDeviceId, row.getDeviceId())
                .eq(JiangyiClassMapping::getClassId, row.getClassId()));
        if (existing == null) {
            row.setCreatedAt(Instant.now());
            insert(row);
        } else {
            row.setId(existing.getId());
            row.setCreatedAt(existing.getCreatedAt());
            // 来源是溯源信息而非可编辑字段：人工给 MODEL_SYNC 行挂 SKU 后仍须能被
            // 「批量激活预生成行」命中，不能因为一次编辑就变成 MANUAL。
            row.setSource(existing.getSource());
            update(row, Wrappers.<JiangyiClassMapping>lambdaQuery()
                    .eq(JiangyiClassMapping::getDeviceId, row.getDeviceId())
                    .eq(JiangyiClassMapping::getClassId, row.getClassId()));
        }
    }

    /** 激活/停用（MODEL_SYNC 预生成行人工确认后激活）。 */
    default int setStatus(String deviceId, Integer classId, boolean active) {
        return update(null, Wrappers.<JiangyiClassMapping>lambdaUpdate()
                .set(JiangyiClassMapping::getStatus, active ? "ACTIVE" : "DISABLED")
                .set(JiangyiClassMapping::getUpdatedAt, Instant.now())
                .eq(JiangyiClassMapping::getDeviceId, deviceId)
                .eq(JiangyiClassMapping::getClassId, classId));
    }

    /**
     * 批量激活某设备某模型的全部预生成行（CB-023：人工确认后一次性生效）。
     *
     * <p>只激活已挂 SKU 的行：未挂 SKU 的预生成行激活后识别仍会 fail-closed 转
     * DISPUTED（映射不可用却显示「已生效」），必须留给人工逐条补 SKU。</p>
     */
    default int activatePregenerated(String deviceId, String modelName) {
        return update(null, Wrappers.<JiangyiClassMapping>lambdaUpdate()
                .set(JiangyiClassMapping::getStatus, "ACTIVE")
                .set(JiangyiClassMapping::getUpdatedAt, Instant.now())
                .eq(JiangyiClassMapping::getDeviceId, deviceId)
                .eq(JiangyiClassMapping::getModelName, modelName)
                .eq(JiangyiClassMapping::getSource, "MODEL_SYNC")
                .eq(JiangyiClassMapping::getStatus, "DISABLED")
                .isNotNull(JiangyiClassMapping::getSkuId)
                .ne(JiangyiClassMapping::getSkuId, ""));
    }
}
