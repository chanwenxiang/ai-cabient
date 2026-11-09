package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.dto.JiangyiGatherDtos.GatherCheckItem;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiModelFile;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** 六步导航数字面板：本地读面即时反映，厂商读面单项降级为 null + vendorError。 */
@ExtendWith(MockitoExtension.class)
class JiangyiFlowSummaryServiceTest {

    @Mock private JiangyiGatherClient client;
    @Mock private JiangyiSkuJiangyiLinkMapper linkMapper;
    @Mock private JiangyiDeviceMapper deviceMapper;
    @Mock private JiangyiClassMappingMapper mappingMapper;

    @Test
    void aggregatesLocalAndVendorCounts() throws Exception {
        when(linkMapper.listBoundAll()).thenReturn(List.of(new JiangyiSkuJiangyiLink(), new JiangyiSkuJiangyiLink()));
        JiangyiDevice verified = new JiangyiDevice();
        verified.setClassesVersion("v7");
        verified.setMappingVerifiedVersion("v7");
        JiangyiDevice unverified = new JiangyiDevice();
        unverified.setClassesVersion("v7");
        when(deviceMapper.listBound()).thenReturn(List.of(verified, unverified));
        when(mappingMapper.listActiveAll()).thenReturn(List.of(new com.aicabinet.trade.domain.JiangyiClassMapping()));
        when(client.gatherCheck(null)).thenReturn(List.of(
                new GatherCheckItem(1, 41175L, "wait", null, "康师傅冰红茶", null),
                new GatherCheckItem(2, 41176L, "reject", "糊了", "雪碧", null),
                new GatherCheckItem(3, 41177L, "pass", null, "可乐", null)));
        when(client.trainingProducts()).thenReturn(new ObjectMapper().readTree(
                "[{\"name\":\"康师傅冰红茶\",\"status\":\"pass\"}]"));
        when(client.modelFiles()).thenReturn(List.of(
                new JiangyiModelFile(235, "aicabinet-v1", null, "http://m.rknn", "88", "http://c.txt", 15)));

        JiangyiFlowSummaryService.FlowSummaryView view =
                new JiangyiFlowSummaryService(client, linkMapper, deviceMapper, mappingMapper).view();

        assertEquals(2, view.linkedSkus());
        assertEquals(2, view.boundCabinets());
        assertEquals(1, view.verifiedCabinets());
        assertEquals(1, view.activeMappings());
        assertEquals(0, view.pendingGather());
        assertEquals(1, view.auditWait());
        assertEquals(1, view.auditReject());
        assertEquals(1, view.studying());
        assertEquals(1, view.models());
        assertNull(view.vendorError());
    }

    @Test
    void vendorReadFailureDegradesItemToNullWithErrorNote() {
        when(linkMapper.listBoundAll()).thenReturn(List.of());
        when(deviceMapper.listBound()).thenReturn(List.of());
        when(mappingMapper.listActiveAll()).thenReturn(List.of());
        when(client.gatherCheck(null)).thenThrow(new IllegalStateException("厂商 503"));

        JiangyiFlowSummaryService.FlowSummaryView view =
                new JiangyiFlowSummaryService(client, linkMapper, deviceMapper, mappingMapper).view();

        assertNull(view.auditWait());
        assertNull(view.auditReject());
        assertTrue(view.vendorError() != null && view.vendorError().contains("采集审核"));
    }
}
