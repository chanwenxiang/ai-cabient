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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgServiceTest {

    private static final long OPERATOR_ID = 1900000001L;

    @Mock private OpsOrgNodeMapper nodeRepository;
    @Mock private OpsDeviceOrgMapper deviceOrgRepository;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private SysDictService sysDictService;

    private OrgService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        service = new OrgService(nodeRepository, deviceOrgRepository, permissionService,
                auditService, distributedLockService, sysDictService);
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
        when(nodeRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(n));
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
        // 字典已初始化且 REGION 为 ACTIVE 项
        when(sysDictService.hasActiveItems(SysDictService.ORG_NODE_TYPE)).thenReturn(true);
        when(sysDictService.isActiveDictValue(SysDictService.ORG_NODE_TYPE, "REGION")).thenReturn(true);

        OrgNodeDto dto = service.upsertNode(OPERATOR_ID,
                new UpsertOrgNodeRequest(null, null, "西南区", "REGION", 2));

        assertEquals("西南区", dto.name());
        assertEquals("REGION", dto.nodeType());
        verify(auditService).appendLog(anyLong(), any(), any(), any(), any());
    }

    /** 负向：字典已初始化时，字典外的类型必须被拒（否则字典白名单形同虚设）。 */
    @Test
    void upsertNode_shouldRejectNodeTypeOutsideDict() {
        when(sysDictService.hasActiveItems(SysDictService.ORG_NODE_TYPE)).thenReturn(true);
        when(sysDictService.isActiveDictValue(SysDictService.ORG_NODE_TYPE, "GALAXY")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertNode(OPERATOR_ID,
                        new UpsertOrgNodeRequest(null, null, "乱写区", "GALAXY", 0)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    /** 字典尚未 seed（无任何 ACTIVE 项）时降级放行，避免配置缺失让组织功能整体不可用。 */
    @Test
    void upsertNode_shouldPassThroughWhenDictNotSeeded() {
        when(nodeRepository.insert(any())).thenAnswer(inv -> {
            OpsOrgNode n = inv.getArgument(0);
            n.setNodeId(10L);
            return 1;
        });
        when(sysDictService.hasActiveItems(SysDictService.ORG_NODE_TYPE)).thenReturn(false);

        OrgNodeDto dto = service.upsertNode(OPERATOR_ID,
                new UpsertOrgNodeRequest(null, null, "西南区", "REGION", 2));

        assertEquals("REGION", dto.nodeType());
    }

    /** 编辑时未改类型：历史脏值不阻断改名保存。 */
    @Test
    void upsertNode_shouldKeepLegacyNodeTypeWhenUnchanged() {
        OpsOrgNode legacy = node(2L, null, "老组织");
        legacy.setNodeType("LEGACY_TYPE");
        when(nodeRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(legacy));
        when(sysDictService.hasActiveItems(SysDictService.ORG_NODE_TYPE)).thenReturn(true);
        when(sysDictService.isActiveDictValue(SysDictService.ORG_NODE_TYPE, "LEGACY_TYPE")).thenReturn(false);

        OrgNodeDto dto = service.upsertNode(OPERATOR_ID,
                new UpsertOrgNodeRequest(2L, null, "老组织改名", "LEGACY_TYPE", 0));

        assertEquals("LEGACY_TYPE", dto.nodeType());
        assertEquals("老组织改名", dto.name());
    }
}
