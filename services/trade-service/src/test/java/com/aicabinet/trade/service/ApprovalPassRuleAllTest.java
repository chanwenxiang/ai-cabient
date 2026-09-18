package com.aicabinet.trade.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.aicabinet.trade.domain.ApprovalInstance;
import com.aicabinet.trade.domain.ApprovalNode;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H34：passRule=ALL（会签）节点必须全部处理人通过后才推进；
 * ANY（或签）保持一人通过即推进的旧行为。
 * M01：重启流程时 remark/finishedAt 必须能清空（wrapper 显式 set null）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalPassRuleAllTest {

    static {
        // M01：LambdaUpdateWrapper 需要 TableInfo 缓存，纯 Mockito 环境需手动初始化
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ApprovalInstance.class);
    }


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
        service = new ApprovalWorkflowService(definitionRepository, nodeRepository,
                instanceRepository, taskRepository, permissionRepository, userDepartmentRepository,
                notificationService, permissionService, auditService, null);
    }

    private ApprovalInstance instance(long id) {
        ApprovalInstance instance = new ApprovalInstance();
        instance.setInstanceId(id);
        instance.setDefId(1L);
        instance.setBizType("MERCHANT_WITHDRAW");
        instance.setBizId("77");
        instance.setStatus("PENDING");
        instance.setCurrentNodeSeq(1);
        return instance;
    }

    private ApprovalNode node(int seq, String passRule) {
        ApprovalNode node = new ApprovalNode();
        node.setDefId(1L);
        node.setSeq(seq);
        node.setNodeName("节点" + seq);
        node.setAssigneeType("PERM");
        node.setAssigneeValue("OPS_APPROVAL");
        node.setPassRule(passRule);
        return node;
    }

    private ApprovalTask task(long taskId, long assigneeId) {
        ApprovalTask task = new ApprovalTask();
        task.setTaskId(taskId);
        task.setInstanceId(10L);
        task.setNodeSeq(1);
        task.setNodeName("节点1");
        task.setAssigneeUserId(assigneeId);
        task.setStatus("PENDING");
        return task;
    }

    private void stubPendingInstance(ApprovalInstance instance) {
        when(instanceRepository.findPendingByBizTypeAndBizId("MERCHANT_WITHDRAW", "77"))
                .thenReturn(Optional.of(instance));
        when(instanceRepository.findByIdForUpdate(instance.getInstanceId()))
                .thenReturn(Optional.of(instance));
    }

    @Test
    void passRuleAll_firstApproval_waitsForRemainingAssignees() {
        ApprovalInstance instance = instance(10L);
        stubPendingInstance(instance);
        when(nodeRepository.findByDefIdOrderBySeqAsc(1L)).thenReturn(List.of(node(1, "ALL"), node(2, "ANY")));
        ApprovalTask mine = task(1L, 11L);
        ApprovalTask others = task(2L, 22L);
        when(taskRepository.findByInstanceIdAndNodeSeq(10L, 1)).thenReturn(List.of(mine, others));

        service.completeApproved(11L, "MERCHANT_WITHDRAW", "77", "ok");

        assertEquals("APPROVED", mine.getStatus());
        assertEquals("PENDING", others.getStatus(), "会签节点：他人待办不得被 SKIP");
        assertEquals("PENDING", instance.getStatus(), "会签节点：未全部通过不得推进");
        verify(instanceRepository, never()).save(any());
    }

    @Test
    void passRuleAll_allApproved_thenAdvancesToNextNode() {
        ApprovalInstance instance = instance(10L);
        stubPendingInstance(instance);
        ApprovalNode next = node(2, "ANY");
        when(nodeRepository.findByDefIdOrderBySeqAsc(1L)).thenReturn(List.of(node(1, "ALL"), next));
        ApprovalTask mine = task(1L, 11L);
        mine.setStatus("APPROVED");
        ApprovalTask last = task(2L, 22L);
        when(taskRepository.findByInstanceIdAndNodeSeq(10L, 1)).thenReturn(List.of(mine, last));
        when(permissionRepository.findUserIdsByPermCode("OPS_APPROVAL")).thenReturn(List.of(33L));
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.completeApproved(22L, "MERCHANT_WITHDRAW", "77", "ok");

        assertEquals("APPROVED", last.getStatus());
        assertEquals(2, instance.getCurrentNodeSeq());
        verify(taskRepository, atLeastOnce()).save(any());
        verify(notificationService).notifyOpsInApp(eq(33L), anyString(), anyString(),
                eq("MERCHANT_WITHDRAW"), eq("77"));
    }

    @Test
    void passRuleAny_oneApproval_skipsOthersAndAdvances() {
        ApprovalInstance instance = instance(10L);
        stubPendingInstance(instance);
        when(nodeRepository.findByDefIdOrderBySeqAsc(1L)).thenReturn(List.of(node(1, "ANY")));
        ApprovalTask mine = task(1L, 11L);
        ApprovalTask others = task(2L, 22L);
        when(taskRepository.findByInstanceIdAndNodeSeq(10L, 1)).thenReturn(List.of(mine, others));
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.completeApproved(11L, "MERCHANT_WITHDRAW", "77", "ok");

        assertEquals("APPROVED", mine.getStatus());
        assertEquals("SKIPPED", others.getStatus(), "或签：一人通过后其余待办 SKIP");
        assertEquals("APPROVED", instance.getStatus(), "末节点通过即完结");
    }

    /** M01：REJECTED 实例重新发起后，remark/finishedAt 会被 wrapper 显式置空。 */
    @Test
    void restartRejectedInstance_clearsRemarkAndFinishedAtExplicitly() {
        com.aicabinet.trade.domain.ApprovalDefinition def = new com.aicabinet.trade.domain.ApprovalDefinition();
        def.setDefId(1L);
        def.setBizType("MERCHANT_WITHDRAW");
        def.setEnabled(true);
        when(definitionRepository.findByBizType("MERCHANT_WITHDRAW")).thenReturn(Optional.of(def));
        when(nodeRepository.findByDefIdOrderBySeqAsc(1L)).thenReturn(List.of(node(1, "ANY")));
        ApprovalInstance old = instance(10L);
        old.setStatus("REJECTED");
        old.setRemark("旧备注");
        old.setFinishedAt(java.time.Instant.now());
        when(instanceRepository.findByBizTypeAndBizId("MERCHANT_WITHDRAW", "77")).thenReturn(Optional.of(old));
        when(instanceRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(old));
        when(permissionRepository.findUserIdsByPermCode("OPS_APPROVAL")).thenReturn(List.of(11L));

        service.start("MERCHANT_WITHDRAW", "77", 11L, "重提");

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(instanceRepository);
        inOrder.verify(instanceRepository).save(old);
        // updateById 清不掉 null 字段：updateById 之后必须再以 wrapper 显式 set(null)
        inOrder.verify(instanceRepository).update(eq(null),
                any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
}
