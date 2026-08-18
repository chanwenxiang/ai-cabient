package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.ReplyFeedbackRequest;
import com.aicabinet.common.dto.UserFeedbackDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.UserFeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/feedback")
public class UserFeedbackController {

    private final UserFeedbackService feedbackService;

    public UserFeedbackController(UserFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @RequiresPermissions(value = {"ops:feedback", "ops:feedback:reply"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<List<UserFeedbackDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(feedbackService.list(operatorId(request), status));
    }

    @RequiresPermissions("ops:feedback:reply")
    @PostMapping("/{feedbackId}/reply")
    public ApiResponse<UserFeedbackDto> reply(
            HttpServletRequest request,
            @PathVariable Long feedbackId,
            @Valid @RequestBody ReplyFeedbackRequest body) {
        return ApiResponse.ok(feedbackService.reply(operatorId(request), feedbackId, body));
    }

    @RequiresPermissions(value = {"ops:feedback", "ops:feedback:reply"}, logical = RequiresPermissions.Logical.OR)
    @DeleteMapping("/{feedbackId}")
    public ApiResponse<Void> delete(
            HttpServletRequest request,
            @PathVariable Long feedbackId) {
        feedbackService.delete(operatorId(request), feedbackId);
        return ApiResponse.ok(null);
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
