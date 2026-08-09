package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrgNodeDto;
import com.aicabinet.common.dto.UpsertOrgNodeRequest;
import com.aicabinet.trade.domain.OpsDeviceOrg;
import com.aicabinet.trade.domain.OpsOrgNode;
import com.aicabinet.trade.mapper.OpsDeviceOrgMapper;
import com.aicabinet.trade.mapper.OpsOrgNodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgServiceTest {

    private static final long OPERATOR_ID = 1900000001L;

    @Mock private OpsOrgNodeMapper nodeRepository;
    @Mock private OpsDeviceOrgMapper deviceOrgRepository;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;

    private OrgService service;

    @BeforeEach
    void setUp() {
        service = new OrgService(nodeRepository, deviceOrgRepository, permissionService, auditService);
    }

    private static OpsOrgNode node(Long id, Long parent, String name) {
        OpsOrgNode n = new OpsOrgNode();
        n.setNodeId(id);
        n.setParentId(parent);
        n.setName(name);
        n.setNodeType("BRANCH");
        return n;
    }

    @Test
    void tree_shouldBuildHierarchyWithDeviceIds() {
        when(nodeRepository.findAllOrderBySort()).thenReturn(List.of(
                node(1L, null, "总部"), node(2L, 1L, "华南区"), node(3L, 2L, "深圳分公司")));
        OpsDeviceOrg m = new OpsDeviceOrg();
        m.setNodeId(3L);
        m.setDeviceId("CAB-001");
        when(deviceOrgRepository.findAll()).thenReturn(List.of(m));

        List<OrgNodeDto> roots = service.tree(OPERATOR_ID);

        assertEquals(1, roots.size());
        assertEquals("总部", roots.get(0).name());
        assertEquals("华南区", roots.get(0).children().get(0).name());
        OrgNodeDto leaf = roots.get(0).children().get(0).children().get(0);
        assertEquals("深圳分公司", leaf.name());
        assertEquals(List.of("CAB-001"), leaf.deviceIds());
    }

    @Test
    void assignDevices_shouldRebuildNodeMapping() {
        OpsOrgNode n = node(2L, null, "华南区");
        when(nodeRepository.findById(2L)).thenReturn(Optional.of(n));
        when(deviceOrgRepository.findByNodeId(2L)).thenReturn(List.of());

        OrgNodeDto dto = service.assignDevices(OPERATOR_ID, 2L, List.of("cab-001", "CAB-002"));

        verify(deviceOrgRepository).deleteByDeviceIds(anyList());
        verify(deviceOrgRepository).deleteByNodeId(2L);
        verify(deviceOrgRepository, org.mockito.Mockito.times(2)).insert(any(OpsDeviceOrg.class));
        assertTrue(dto.deviceIds().isEmpty());
    }

    @Test
    void upsertNode_shouldCreateAndAudit() {
        when(nodeRepository.insert(any())).thenAnswer(inv -> {
            OpsOrgNode n = inv.getArgument(0);
            n.setNodeId(9L);
            return 1;
        });

        OrgNodeDto dto = service.upsertNode(OPERATOR_ID,
                new UpsertOrgNodeRequest(null, null, "西南区", "REGION", 2));

        assertEquals("西南区", dto.name());
        assertEquals("REGION", dto.nodeType());
        verify(auditService).record(anyLong(), any(), any(), any(), any());
    }
}
