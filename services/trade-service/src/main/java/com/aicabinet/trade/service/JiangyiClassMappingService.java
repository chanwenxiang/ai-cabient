package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 将邑 class_id → SKU 映射服务（CB-022）。
 * <p>gateway 收识别上报后经内部接口调用 {@link #resolveActive}：命中 → 以映射 SKU
 * 生成 items（金额我方算，分）；未命中 → gateway 对该会话触发 recognize-timeout，
 * fail-closed 转 DISPUTED（宁可人工介入，不算错账）。</p>
 *
 * <p>低频查询（每会话识别一次）直接查库，不设缓存：映射状态在管理侧随时变更，
 * 缓存一致性复杂度高于查询成本。</p>
 */
@Service
public class JiangyiClassMappingService {

    private final JiangyiClassMappingMapper jiangyiClassMappingMapper;
    private final AdminAuditService auditService;

    public JiangyiClassMappingService(JiangyiClassMappingMapper jiangyiClassMappingMapper,
                                      AdminAuditService auditService) {
        this.jiangyiClassMappingMapper = jiangyiClassMappingMapper;
        this.auditService = auditService;
    }

    /** ACTIVE 映射点查；empty = 未命中（调用方 fail-closed）。 */
    public Optional<JiangyiClassMapping> resolveActive(String deviceId, Integer classId) {
        if (deviceId == null || deviceId.isBlank() || classId == null) {
            return Optional.empty();
        }
        return jiangyiClassMappingMapper.findActive(deviceId, classId);
    }

    public List<JiangyiClassMapping> listByDevice(String deviceId) {
        return jiangyiClassMappingMapper.listByDevice(deviceId);
    }

    /** 幂等 upsert（admin 人工建立 / 后续模型同步预生成共用入口）。 */
    public JiangyiClassMapping upsert(Long operatorId, JiangyiClassMapping mapping) {
        jiangyiClassMappingMapper.upsert(mapping);
        JiangyiClassMapping saved = jiangyiClassMappingMapper
                .findOne(mapping.getDeviceId(), mapping.getClassId())
                .orElse(mapping);
        auditService.appendLog(operatorId, "JIANGYI_CLASS_MAPPING_UPSERT", "JIANGYI_CLASS_MAPPING",
                mapping.getDeviceId() + ":" + mapping.getClassId(),
                "modelName=" + mapping.getModelName() + ",textName=" + mapping.getTextName()
                        + ",skuId=" + mapping.getSkuId() + ",status=" + mapping.getStatus()
                        + ",source=" + mapping.getSource());
        return saved;
    }

    /** MODEL_SYNC 预生成行人工确认后激活；停用即时生效（下次解析即 fail-closed）。 */
    public int setStatus(Long operatorId, String deviceId, Integer classId, boolean active) {
        int rows = jiangyiClassMappingMapper.setStatus(deviceId, classId, active);
        auditService.appendLog(operatorId, "JIANGYI_CLASS_MAPPING_STATUS", "JIANGYI_CLASS_MAPPING",
                deviceId + ":" + classId, "active=" + active + ",rows=" + rows);
        return rows;
    }
}
