package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ApprovalNodeDto;
import com.aicabinet.common.dto.UpsertApprovalDefinitionRequest;
import com.aicabinet.trade.domain.ApprovalDefinition;
import com.aicabinet.trade.domain.ApprovalNode;
import com.aicabinet.trade.mapper.ApprovalDefinitionMapper;
import com.aicabinet.trade.mapper.ApprovalInstanceMapper;
import com.aicabinet.trade.mapper.ApprovalNodeMapper;
import com.aicabinet.trade.mapper.ApprovalTaskMapper;
import com.aicabinet.trade.mapper.OpsPermissionMapper;
import com.aicabinet.trade.mapper.OpsUserDepartmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalNodeUpsertTest {

    @Mock private ApprovalDefinitionMapper definitionRepository;
    @Mock private ApprovalNodeMapper nodeRepository;
    @Mock private ApprovalInstanceMapper instanceRepository;
    @Mock private ApprovalTaskMapper taskRepository;
    @Mock private OpsPermissionMapper permissionRepository;
    @Mock private OpsUserDepartmentMapper userDepartmentRepository;
    @Mock private NotificationService notificationService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;

    private ApprovalWorkflowService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalWorkflowService(
                definitionRepository, nodeRepository, instanceRepository, taskRepository,
                permissionRepository, userDepartmentRepository, notificationService,
                permissionService, auditService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void updateDefinition_preservesNodeIdsAndSwapsSeq() {
        ApprovalDefinition def = new ApprovalDefinition();
        def.setDefId(9L);
        def.setBizType("MERCHANT_ONBOARD");
        def.setDefName("入驻审批");
        def.setEnabled(true);

        ApprovalNode n1 = node(101L, 9L, 1, "一审", "PERM", "ops:approval:list");
        ApprovalNode n2 = node(102L, 9L, 2, "二审", "PERM", "ops:approval:config");

        when(definitionRepository.selectById(9L)).thenReturn(def);
        when(nodeRepository.findByDefIdOrderBySeqAsc(9L)).thenReturn(List.of(n1, n2), List.of(n2, n1));

        UpsertApprovalDefinitionRequest req = new UpsertApprovalDefinitionRequest(
                "入驻审批",
                true,
                null,
                List.of(
                        new ApprovalNodeDto(102L, 1, "二审改", "PERM", "ops:approval:config", "ANY"),
                        new ApprovalNodeDto(101L, 2, "一审改", "PERM", "ops:approval:list", "ANY")));

        service.updateDefinition(1L, 9L, req);

        verify(nodeRepository, never()).deleteByDefId(anyLong());
        verify(nodeRepository, never()).deleteById(org.mockito.ArgumentMatchers.<Long>any());
        verify(nodeRepository, never()).insert(any());

        ArgumentCaptor<ApprovalNode> updateCaptor = ArgumentCaptor.forClass(ApprovalNode.class);
        // park (2) + final (2)
        verify(nodeRepository, times(4)).updateById(updateCaptor.capture());
        List<ApprovalNode> allUpdates = updateCaptor.getAllValues();
        assertEquals(4, allUpdates.size());
        // Captor holds mutable refs; assert the last (final) pass only.
        List<ApprovalNode> finals = allUpdates.subList(2, 4);
        ApprovalNode first = finals.stream().filter(n -> n.getSeq() == 1).findFirst().orElseThrow();
        ApprovalNode second = finals.stream().filter(n -> n.getSeq() == 2).findFirst().orElseThrow();
        assertEquals(102L, first.getNodeId());
        assertEquals("二审改", first.getNodeName());
        assertEquals(101L, second.getNodeId());
        assertEquals("一审改", second.getNodeName());
    }

    @Test
    void updateDefinition_deletesRemovedAndInsertsNew() {
        ApprovalDefinition def = new ApprovalDefinition();
        def.setDefId(3L);
        def.setBizType("X");
        def.setDefName("X");
        def.setEnabled(true);

        ApprovalNode keep = node(10L, 3L, 1, "保留", "PERM", "ops:a");
        ApprovalNode drop = node(11L, 3L, 2, "删除", "PERM", "ops:b");

        when(definitionRepository.selectById(3L)).thenReturn(def);
        when(nodeRepository.findByDefIdOrderBySeqAsc(3L)).thenReturn(List.of(keep, drop), List.of(keep));

        UpsertApprovalDefinitionRequest req = new UpsertApprovalDefinitionRequest(
                "X",
                true,
                null,
                List.of(
                        new ApprovalNodeDto(10L, 1, "保留", "PERM", "ops:a", "ANY"),
                        new ApprovalNodeDto(null, 2, "新建", "ROLE", "OPS", "ANY")));

        service.updateDefinition(1L, 3L, req);

        verify(nodeRepository).deleteById(eq(11L));
        ArgumentCaptor<ApprovalNode> insertCaptor = ArgumentCaptor.forClass(ApprovalNode.class);
        verify(nodeRepository).insert(insertCaptor.capture());
        ApprovalNode inserted = insertCaptor.getValue();
        assertEquals(3L, inserted.getDefId());
        assertEquals(2, inserted.getSeq());
        assertEquals("新建", inserted.getNodeName());
        assertEquals("ROLE", inserted.getAssigneeType());
        assertTrue(inserted.getNodeId() == null || inserted.getNodeId() != 11L);
    }

    private static ApprovalNode node(Long id, Long defId, int seq, String name, String type, String value) {
        ApprovalNode n = new ApprovalNode();
        n.setNodeId(id);
        n.setDefId(defId);
        n.setSeq(seq);
        n.setNodeName(name);
        n.setAssigneeType(type);
        n.setAssigneeValue(value);
        n.setPassRule("ANY");
        return n;
    }
}
