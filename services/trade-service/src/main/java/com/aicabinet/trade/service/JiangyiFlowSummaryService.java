package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 识别链路六步总览的数字面板（flow stepper 数据源）：
 * 挂接 SKU → 柜前采集 → 厂商审核 → 提交学习 → 映射下发 → 上架售卖。
 *
 * <p>本地读面（挂接/柜机/映射）失败即整体失败；厂商读面（待采集/审核/学习中/模型）
 * 单项降级为 null + vendorError 汇总——照 {@link JiangyiLearningLibraryService} 的口径，
 * 数字缺一角不该把整条导航条打挂。</p>
 */
@Service
public class JiangyiFlowSummaryService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiFlowSummaryService.class);

    private final JiangyiGatherClient jiangyiGatherClient;
    private final JiangyiSkuJiangyiLinkMapper linkMapper;
    private final JiangyiDeviceMapper deviceMapper;
    private final JiangyiClassMappingMapper mappingMapper;

    public JiangyiFlowSummaryService(JiangyiGatherClient jiangyiGatherClient,
                                     JiangyiSkuJiangyiLinkMapper linkMapper,
                                     JiangyiDeviceMapper deviceMapper,
                                     JiangyiClassMappingMapper mappingMapper) {
        this.jiangyiGatherClient = jiangyiGatherClient;
        this.linkMapper = linkMapper;
        this.deviceMapper = deviceMapper;
        this.mappingMapper = mappingMapper;
    }

    public FlowSummaryView view() {
        int linkedSkus = linkMapper.listBoundAll().size();
        List<com.aicabinet.trade.domain.JiangyiDevice> bound = deviceMapper.listBound();
        int verified = (int) bound.stream().filter(JiangyiLearningLibraryService::isMappingVerified).count();
        int activeMappings = mappingMapper.listActiveAll().size();

        Integer pendingGather = null;
        Integer auditWait = null;
        Integer auditReject = null;
        Integer studying = null;
        Integer models = null;
        java.util.List<String> errors = new java.util.ArrayList<>();
        // 旧 selfhelpGatherProduct/waitGatherProduct 已停用；待采集改读我方挂接快照
        // （NEED_PHONE/REJECTED/REVIEWING），不再打旧接口。
        pendingGather = (int) linkMapper.listBoundAll().stream()
                .filter(l -> l.getJiangyiState() != null
                        && java.util.Set.of("NEED_PHONE", "REJECTED", "REVIEWING").contains(l.getJiangyiState()))
                .count();
        try {
            List<com.aicabinet.trade.dto.JiangyiGatherDtos.GatherCheckItem> checks = jiangyiGatherClient.gatherCheck(null);
            auditWait = (int) checks.stream().filter(c -> "wait".equalsIgnoreCase(c.status())).count();
            auditReject = (int) checks.stream().filter(c -> "reject".equalsIgnoreCase(c.status())).count();
        } catch (RuntimeException e) {
            errors.add("采集审核：" + rootMessage(e));
        }
        try {
            studying = arraySize(jiangyiGatherClient.trainingProducts());
        } catch (RuntimeException e) {
            errors.add("学习中列表：" + rootMessage(e));
        }
        try {
            models = jiangyiGatherClient.modelFiles().size();
        } catch (RuntimeException e) {
            errors.add("模型列表：" + rootMessage(e));
        }
        String vendorError = errors.isEmpty() ? null : String.join("；", errors);
        if (vendorError != null) {
            log.warn("jiangyi flow-summary degraded: {}", vendorError);
        }
        return new FlowSummaryView(linkedSkus, bound.size(), verified, activeMappings,
                pendingGather, auditWait, auditReject, studying, models, vendorError);
    }

    private static int arraySize(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    /** 六步数字面板（厂商侧缺失的项为 null，前端显示 ？）。 */
    public record FlowSummaryView(int linkedSkus, int boundCabinets, int verifiedCabinets,
                                  int activeMappings, Integer pendingGather, Integer auditWait,
                                  Integer auditReject, Integer studying, Integer models,
                                  String vendorError) {}
}
