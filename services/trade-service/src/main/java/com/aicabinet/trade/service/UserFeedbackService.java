package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplyFeedbackRequest;
import com.aicabinet.common.dto.SubmitFeedbackRequest;
import com.aicabinet.common.dto.UserFeedbackDto;
import com.aicabinet.trade.domain.UserFeedback;
import com.aicabinet.trade.mapper.UserFeedbackMapper;
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

    private static final Set<String> FEEDBACK_TYPES = Set.of("COMPLAINT", "SUGGESTION", "BUG", "PRAISE");

    private final UserFeedbackMapper repository;
    private final PermissionService permissionService;

    public UserFeedbackService(UserFeedbackMapper repository, PermissionService permissionService) {
        this.repository = repository;
        this.permissionService = permissionService;
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
        row.setContactInfo(blankToNull(body.contactInfo()));
        row.setDeviceId(blankToNull(body.deviceId()));
        row.setSessionId(blankToNull(body.sessionId()));
        row.setRating(body.rating());
        row.setStatus("PENDING");
        row.setCreatedAt(Instant.now());
        return toDto(repository.save(row));
    }

    @Transactional(readOnly = true)
    public List<UserFeedbackDto> listMine(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<UserFeedbackDto> list(Long operatorId, String status) {
        permissionService.requireAnyPermission(operatorId, "ops:feedback", "ops:feedback:reply");
        List<UserFeedback> rows = status == null || status.isBlank()
                ? repository.findAllOrderByCreatedAtDesc()
                : repository.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase());
        return rows.stream().map(this::toDto).toList();
    }

    @Transactional
    public UserFeedbackDto reply(Long operatorId, Long feedbackId, ReplyFeedbackRequest body) {
        permissionService.requirePermission(operatorId, "ops:feedback:reply");
        if (feedbackId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "feedbackId required");
        }
        String reply = body == null || body.reply() == null ? "" : body.reply().trim();
        if (reply.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回复内容不能为空");
        }
        UserFeedback item = repository.findById(feedbackId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "反馈不存在"));
        item.setReply(reply);
        item.setHandlerId(operatorId);
        item.setStatus("HANDLED");
        item.setHandledAt(Instant.now());
        return toDto(repository.save(item));
    }

    private UserFeedbackDto toDto(UserFeedback f) {
        return new UserFeedbackDto(
                f.getFeedbackId(),
                f.getUserId(),
                f.getFeedbackType(),
                f.getContent(),
                f.getContactInfo(),
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
}
