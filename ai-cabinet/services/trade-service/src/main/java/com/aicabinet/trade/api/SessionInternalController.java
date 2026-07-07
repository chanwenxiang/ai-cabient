package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.dto.VideoAttachRequest;
import com.aicabinet.trade.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/sessions")
public class SessionInternalController {

    private final SessionService sessionService;

    public SessionInternalController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/door-event")
    public ApiResponse<SessionDto> doorEvent(@Valid @RequestBody DoorEventRequest request) {
        return ApiResponse.ok(sessionService.handleDoorEvent(request));
    }

    @PostMapping("/video")
    public ApiResponse<SessionDto> attachVideo(@Valid @RequestBody VideoAttachRequest request) {
        return ApiResponse.ok(sessionService.attachVideo(request));
    }
}
