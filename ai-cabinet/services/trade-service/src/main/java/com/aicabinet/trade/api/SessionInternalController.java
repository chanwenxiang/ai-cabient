package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.dto.GravityDeltaRequest;
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

    /** 重力柜实时重量变化（负 delta 表示取走） */
    @PostMapping("/gravity-deltas")
    public ApiResponse<SessionDto> gravityDeltas(@Valid @RequestBody GravityDeltaRequest request) {
        return ApiResponse.ok(sessionService.attachGravityDeltas(request));
    }
}
