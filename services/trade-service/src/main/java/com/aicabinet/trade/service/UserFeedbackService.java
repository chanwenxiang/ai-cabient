package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.ReplyFeedbackRequest;
import com.aicabinet.common.dto.SubmitFeedbackRequest;
import com.aicabinet.common.dto.UserFeedbackDto;
import com.aicabinet.trade.domain.UserFeedback;
import com.aicabinet.trade.mapper.UserFeedbackMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class UserFeedbackService {
    private static final String PERM_OPS_FEEDBACK_REPLY = "ops:feedback:reply";
    private static final String PERM_OPS_FEEDBACK = "ops:feedback";


    private static final Set<String> FEEDBACK_TYPES = Set.of("COMPLAINT", "SUGGESTION", "BUG", "PRAISE");

    private final UserFeedbackMapper repository;
    private final PermissionService permissionService;
    private final DistributedLockService distributedLockService;

    public UserFeedbackService(UserFeedbackMapper repository,
                               PermissionService permissionService,
                               DistributedLockService distributedLockService) {
        this.repository = repository;
        this.permissionService = permissionService;
        this.distributedLockService = distributedLockService;
    }

    @Transactional
    public UserFeedbackDto submit(Long userId, SubmitFeedbackRequest body) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        String type = body.feedbackType() == null ? "" : body.feedbackType().trim().toUpperCase(Locale.ROOT);
        if (!FEEDBACK_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "反馈类型无效");
        }
        String content = body.content() == null ? "" : body.content().trim();
        if (content.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "反馈内容不能为空");
        }
        UserFeedback row = new UserFeedback();
        row.setUserId(userId);
        row.setFeedbackType(type);
        row.setContent(content);
        row.setContactInfo(sanitizeContactInfo(body.contactInfo()));
        row.setDeviceId(blankToNull(body.deviceId()));
        row.setSessionId(blankToNull(body.sessionId()));
        row.setRating(body.rating());
        row.setStatus("PENDING");
        row.setCreatedAt(Instant.now());
        return toDto(repository.save(row), true);
    }

    @Transactional(readOnly = true)
    public List<UserFeedbackDto> listMine(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(f -> toDto(f, true))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserFeedbackDto> list(Long operatorId, String status) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_FEEDBACK, PERM_OPS_FEEDBACK_REPLY);
        List<UserFeedback> rows = status == null || status.isBlank()
                ? repository.findAllOrderByCreatedAtDesc()
                : repository.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase());
        // 运营列表不回传 contactInfo，避免历史 XSS 样例进入浏览器
        return rows.stream().map(f -> toDto(f, false)).toList();
    }

    @Transactional(readOnly = true)
    public PageResult<UserFeedbackDto> listPage(Long operatorId, String status, int page, int size) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_FEEDBACK, PERM_OPS_FEEDBACK_REPLY);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        String statusFilter = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        Page<UserFeedback> result = repository.search(statusFilter, p, s);
        List<UserFeedbackDto> items = result.getRecords().stream().map(f -> toDto(f, false)).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional
    public UserFeedbackDto reply(Long operatorId, Long feedbackId, ReplyFeedbackRequest body) {
        permissionService.requirePermission(operatorId, PERM_OPS_FEEDBACK_REPLY);
        if (feedbackId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "feedbackId required");
        }
        return runWithFeedbackLock(feedbackId, () -> doReply(operatorId, feedbackId, body));
    }

    private UserFeedbackDto doReply(Long operatorId, Long feedbackId, ReplyFeedbackRequest body) {
        String reply = body == null || body.reply() == null ? "" : body.reply().trim();
        if (reply.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回复内容不能为空");
        }
        UserFeedback item = repository.findByIdForUpdate(feedbackId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "反馈不存在"));
        item.setReply(reply);
        item.setHandlerId(operatorId);
        item.setStatus("HANDLED");
        item.setHandledAt(Instant.now());
        return toDto(repository.save(item), false);
    }

    @Transactional
    public void delete(Long operatorId, Long feedbackId) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_FEEDBACK, PERM_OPS_FEEDBACK_REPLY);
        if (feedbackId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "feedbackId required");
        }
        runWithFeedbackLock(feedbackId, () -> {
            if (repository.findByIdForUpdate(feedbackId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "反馈不存在");
            }
            repository.deleteById(feedbackId);
            return null;
        });
    }

    static String feedbackLockKey(Long feedbackId) {
        return "feedback:" + feedbackId;
    }

    private <T> T runWithFeedbackLock(Long feedbackId, java.util.function.Supplier<T> action) {
        String key = feedbackLockKey(feedbackId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户反馈处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private UserFeedbackDto toDto(UserFeedback f, boolean includeContact) {
        return new UserFeedbackDto(
                f.getFeedbackId(),
                f.getUserId(),
                f.getFeedbackType(),
                f.getContent(),
                includeContact ? sanitizeContactInfo(f.getContactInfo()) : null,
                f.getDeviceId(),
                f.getSessionId(),
                f.getRating(),
                f.getStatus(),
                f.getHandlerId(),
                f.getReply(),
                f.getHandledAt(),
                f.getCreatedAt()
        );
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 拒绝明显脚本/事件载荷，避免入库与回传（OBS-020）。 */
    private static String sanitizeContactInfo(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.contains("<") || lower.contains(">")
                || lower.contains("javascript:")
                || lower.contains("onerror")
                || lower.contains("onload")
                || lower.contains("<script")) {
            return null;
        }
        return v;
    }
}
