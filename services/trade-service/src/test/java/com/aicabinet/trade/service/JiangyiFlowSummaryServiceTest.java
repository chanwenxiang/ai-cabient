package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/** 六步导航数字面板：只读本地库；旧厂商读面指标随旧链退场恒 null。 */
@ExtendWith(MockitoExtension.class)
class JiangyiFlowSummaryServiceTest {

    @Mock private JiangyiSkuJiangyiLinkMapper linkMapper;
    @Mock private JiangyiDeviceMapper deviceMapper;
    @Mock private JiangyiClassMappingMapper mappingMapper;

    @Test
    void aggregatesLocalCountsAndLeavesRetiredVendorMetricsNull() {
        when(linkMapper.listBoundAll()).thenReturn(List.of(new JiangyiSkuJiangyiLink(), new JiangyiSkuJiangyiLink()));
        JiangyiDevice verified = new JiangyiDevice();
        verified.setClassesVersion("v7");
        verified.setMappingVerifiedVersion("v7");
        JiangyiDevice unverified = new JiangyiDevice();
        unverified.setClassesVersion("v7");
        when(deviceMapper.listBound()).thenReturn(List.of(verified, unverified));
        when(mappingMapper.listActiveAll()).thenReturn(List.of(new com.aicabinet.trade.domain.JiangyiClassMapping()));

        JiangyiFlowSummaryService.FlowSummaryView view =
                new JiangyiFlowSummaryService(linkMapper, deviceMapper, mappingMapper).view();

        assertEquals(2, view.linkedSkus());
        assertEquals(2, view.boundCabinets());
        assertEquals(1, view.verifiedCabinets());
        assertEquals(1, view.activeMappings());
        assertEquals(0, view.pendingGather());
        assertNull(view.auditWait());
        assertNull(view.auditReject());
        assertNull(view.studying());
        assertNull(view.models());
        assertNull(view.vendorError());
    }

    /** 待采集读我方挂接快照（NEED_PHONE/REJECTED/REVIEWING），不再打旧厂商接口。 */
    @Test
    void pendingGatherCountsLinkSnapshotStates() {
        JiangyiSkuJiangyiLink needPhone = new JiangyiSkuJiangyiLink();
        needPhone.setJiangyiState("NEED_PHONE");
        JiangyiSkuJiangyiLink rejected = new JiangyiSkuJiangyiLink();
        rejected.setJiangyiState("REJECTED");
        JiangyiSkuJiangyiLink reviewing = new JiangyiSkuJiangyiLink();
        reviewing.setJiangyiState("REVIEWING");
        JiangyiSkuJiangyiLink done = new JiangyiSkuJiangyiLink();
        done.setJiangyiState("DONE");
        when(linkMapper.listBoundAll()).thenReturn(List.of(needPhone, rejected, reviewing, done));
        when(deviceMapper.listBound()).thenReturn(List.of());
        when(mappingMapper.listActiveAll()).thenReturn(List.of());

        JiangyiFlowSummaryService.FlowSummaryView view =
                new JiangyiFlowSummaryService(linkMapper, deviceMapper, mappingMapper).view();

        assertEquals(4, view.linkedSkus());
        assertEquals(3, view.pendingGather());
        assertNull(view.vendorError());
    }
}
