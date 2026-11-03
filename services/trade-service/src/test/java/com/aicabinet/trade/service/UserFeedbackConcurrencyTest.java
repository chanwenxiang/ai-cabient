package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplyFeedbackRequest;
import com.aicabinet.trade.mapper.UserFeedbackMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserFeedbackConcurrencyTest {

    @Mock private UserFeedbackMapper repository;
    @Mock private PermissionService permissionService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private AdminAuditService auditService;

    private UserFeedbackService service;

    @BeforeEach
    void setUp() {
        doNothing().when(permissionService).requirePermission(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
        service = new UserFeedbackService(repository, permissionService, distributedLockService, auditService);
    }

    @Test
    void reply_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                UserFeedbackService.feedbackLockKey(5L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.reply(1L, 5L, new ReplyFeedbackRequest("thanks")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void delete_whenNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                UserFeedbackService.feedbackLockKey(6L), 60L, 5L))
                .thenReturn(true);
        doNothing().when(permissionService).requireAnyPermission(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        when(repository.findByIdForUpdate(6L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.delete(1L, 6L));

        verify(distributedLockService).unlock(UserFeedbackService.feedbackLockKey(6L));
    }

    /**
     * MA-111 钉测：硬删用户原文必须①只认回复码（不能被漂回查看码 {@code ops:feedback}）、
     * ②留下非 PII 摘要审计。原来「持查看码即可物理删且全类零留痕」。
     */
    @Test
    void delete_requiresReplyPerm_andWritesPiiFreeAudit() {
        com.aicabinet.trade.domain.UserFeedback feedback = new com.aicabinet.trade.domain.UserFeedback();
        feedback.setFeedbackId(9L);
        feedback.setFeedbackType("COMPLAINT");
        feedback.setStatus("PENDING");
        feedback.setContent("缺货联系电话13800000000");
        when(distributedLockService.tryLock(
                UserFeedbackService.feedbackLockKey(9L), 60L, 5L)).thenReturn(true);
        when(repository.findByIdForUpdate(9L)).thenReturn(java.util.Optional.of(feedback));

        service.delete(1L, 9L);

        verify(permissionService).requirePermission(1L, "ops:feedback:reply");
        verify(repository).deleteById(9L);
        org.mockito.ArgumentCaptor<String> detail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService).appendLog(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("USER_FEEDBACK_DELETE"),
                org.mockito.ArgumentMatchers.eq("USER_FEEDBACK"),
                org.mockito.ArgumentMatchers.eq("9"), detail.capture());
        String written = detail.getValue();
        org.junit.jupiter.api.Assertions.assertTrue(
                written.contains("type=COMPLAINT") && written.contains("status=PENDING"), written);
        org.junit.jupiter.api.Assertions.assertEquals(
                "type=COMPLAINT,status=PENDING,replied=false,contentLength=17", written);
        // 审计表全员可读 ⇒ 正文与联系方式一律不落
        org.junit.jupiter.api.Assertions.assertFalse(written.contains("13800000000"), written);
    }

    /**
     * 同上第二半：权限收口发生在控制器注解上（403 由切面抛），注解字面量漂回查看码
     * {@code ops:feedback} 就等于把「持查看权即可物理删用户原文」放回来，所以直接钉注解。
     */
    @Test
    void deleteEndpoint_annotationRequiresReplyCodeOnly() throws Exception {
        var method = com.aicabinet.trade.api.UserFeedbackController.class
                .getMethod("delete", jakarta.servlet.http.HttpServletRequest.class, Long.class);
        var ann = org.springframework.core.annotation.AnnotatedElementUtils
                .findMergedAnnotation(method, com.aicabinet.trade.auth.RequiresPermissions.class);
        org.junit.jupiter.api.Assertions.assertNotNull(ann, "delete 端点丢了权限注解");
        org.junit.jupiter.api.Assertions.assertArrayEquals(new String[]{"ops:feedback:reply"}, ann.value());
    }
}
