package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.SubmitFeedbackRequest;
import com.aicabinet.common.dto.UserFeedbackDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.UserFeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2/feedback")
public class ConsumerFeedbackController {

    private final UserFeedbackService feedbackService;

    public ConsumerFeedbackController(UserFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    public ApiResponse<UserFeedbackDto> submit(
            HttpServletRequest request,
            @Valid @RequestBody SubmitFeedbackRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(feedbackService.submit(userId, body));
    }

    @GetMapping("/mine")
    public ApiResponse<List<UserFeedbackDto>> mine(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(feedbackService.listMine(userId));
    }
}
