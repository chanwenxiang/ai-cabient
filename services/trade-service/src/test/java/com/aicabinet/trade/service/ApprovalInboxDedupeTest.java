package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ApprovalInboxDto;
import com.aicabinet.common.dto.NotificationDto;
import com.aicabinet.trade.domain.ApprovalInstance;
import com.aicabinet.trade.domain.ApprovalTask;
import com.aicabinet.trade.mapper.ApprovalDefinitionMapper;
import com.aicabinet.trade.mapper.ApprovalInstanceMapper;
import com.aicabinet.trade.mapper.ApprovalNodeMapper;
import com.aicabinet.trade.mapper.ApprovalTaskMapper;
import com.aicabinet.trade.mapper.OpsPermissionMapper;
import com.aicabinet.trade.mapper.OpsUserDepartmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalInboxDedupeTest {

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
    void bizKey_requiresBothParts() {
        assertEquals("A|1", ApprovalWorkflowService.bizKey("A", "1"));
        assertNull(ApprovalWorkflowService.bizKey(null, "1"));
        assertNull(ApprovalWorkflowService.bizKey("A", " "));
    }

    @Test
    void inbox_excludesMessagesMatchingPendingBiz() {
        Long userId = 10001L;
        ApprovalTask pending = new ApprovalTask();
        pending.setTaskId(7L);
        pending.setInstanceId(70L);
        pending.setNodeName("运营审核");
        pending.setNodeSeq(1);
        pending.setStatus("PENDING");
        pending.setAssigneeUserId(userId);
        pending.setCreatedAt(Instant.parse("2026-08-31T02:00:00Z"));

        ApprovalInstance instance = new ApprovalInstance();
        instance.setInstanceId(70L);
        instance.setBizType("MERCHANT_REPLEN_REQUEST");
        instance.setBizId("40");
        instance.setTitle("商户要货 #40 · CAB-001");

        when(taskRepository.findPendingByAssigneeUserId(eq(userId), anyInt())).thenReturn(List.of(pending));
        when(taskRepository.countPendingByAssigneeUserId(userId)).thenReturn(1L);
        when(instanceRepository.findById(70L)).thenReturn(Optional.of(instance));

        NotificationDto dup = msg(1L, "审批提醒：商户要货 #40 · CAB-001",
                "MERCHANT_REPLEN_REQUEST", "40", false);
        NotificationDto other = msg(2L, "审批提醒：采购单 PO-1",
                "PURCHASE_ORDER", "PO-1", true);
        when(notificationService.opsNotifications(eq(userId), anyInt())).thenReturn(List.of(dup, other));
        when(notificationService.opsUnread(eq(userId), anyInt())).thenReturn(List.of(dup));

        ApprovalInboxDto inbox = service.inbox(userId, 15);

        assertEquals(1L, inbox.pendingTaskCount());
        assertEquals(1, inbox.pendingTasks().size());
        assertEquals(1, inbox.recentMessages().size());
        assertEquals(2L, inbox.recentMessages().get(0).id());
        assertEquals(0L, inbox.unreadMessageCount());
        assertTrue(inbox.pendingTasks().get(0).title().contains("#40"));
    }

    private static NotificationDto msg(Long id, String title, String bizType, String bizId, boolean read) {
        return new NotificationDto(id, title, "节点待处理", "OPS_INBOX", "IN_APP", "OPS",
                bizType, bizId, read, read ? Instant.now() : null, Instant.now());
    }
}
