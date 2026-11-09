package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 识别链路六步总览的数字面板（flow stepper 数据源）：
 * 挂接 SKU → 柜前采集 → 厂商审核 → 提交学习 → 映射下发 → 上架售卖。
 *
 * <p>只读本地库（挂接/柜机/映射）。旧厂商读面（审核/学习中/模型）随 _AI-OK 旧柜
 * 学习/采集链退场，相应指标恒 null（前端显示 ？），不再外呼。</p>
 */
@Service
public class JiangyiFlowSummaryService {

    private final JiangyiSkuJiangyiLinkMapper linkMapper;
    private final JiangyiDeviceMapper deviceMapper;
    private final JiangyiClassMappingMapper mappingMapper;

    public JiangyiFlowSummaryService(JiangyiSkuJiangyiLinkMapper linkMapper,
                                     JiangyiDeviceMapper deviceMapper,
                                     JiangyiClassMappingMapper mappingMapper) {
        this.linkMapper = linkMapper;
        this.deviceMapper = deviceMapper;
        this.mappingMapper = mappingMapper;
    }

    public FlowSummaryView view() {
        int linkedSkus = linkMapper.listBoundAll().size();
        List<com.aicabinet.trade.domain.JiangyiDevice> bound = deviceMapper.listBound();
        int verified = (int) bound.stream().filter(JiangyiLearningLibraryService::isMappingVerified).count();
        int activeMappings = mappingMapper.listActiveAll().size();

        // 旧 selfhelpGatherProduct/waitGatherProduct 已停用；待采集改读我方挂接快照
        // （NEED_PHONE/REJECTED/REVIEWING），不再打旧接口。
        Integer pendingGather = (int) linkMapper.listBoundAll().stream()
                .filter(l -> l.getJiangyiState() != null
                        && java.util.Set.of("NEED_PHONE", "REJECTED", "REVIEWING").contains(l.getJiangyiState()))
                .count();
        return new FlowSummaryView(linkedSkus, bound.size(), verified, activeMappings,
                pendingGather, null, null, null, null, null);
    }

    /** 六步数字面板（旧厂商读面项恒 null，前端显示 ？）。 */
    public record FlowSummaryView(int linkedSkus, int boundCabinets, int verifiedCabinets,
                                  int activeMappings, Integer pendingGather, Integer auditWait,
                                  Integer auditReject, Integer studying, Integer models,
                                  String vendorError) {}
}
