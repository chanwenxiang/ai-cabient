package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.dto.GravityDeltaRequest;
import com.aicabinet.common.dto.LiveCartDto;
import com.aicabinet.common.dto.LiveCartUpdateRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.trade.service.SessionOpenService;
import com.aicabinet.trade.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/sessions")
public class SessionInternalController {

    private final SessionService sessionService;
    private final SessionOpenService sessionOpenService;

    public SessionInternalController(SessionService sessionService,
                                     SessionOpenService sessionOpenService) {
        this.sessionService = sessionService;
        this.sessionOpenService = sessionOpenService;
    }

    @PostMapping("/door-event")
    public ApiResponse<SessionDto> doorEvent(@Valid @RequestBody DoorEventRequest request) {
        return ApiResponse.ok(sessionService.handleDoorEvent(request));
    }

    /** 重力柜实时重量变化（负 delta 表示取走） */
    @PostMapping("/gravity-deltas")
    public ApiResponse<SessionDto> gravityDeltas(@Valid @RequestBody GravityDeltaRequest request) {
        return ApiResponse.ok(sessionService.attachGravityDeltas(request));
    }

    /** 第三方识别推送开门中实时购物车（仅展示，不扣款） */
    @PostMapping("/{sessionId}/live-cart")
    public ApiResponse<LiveCartDto> liveCart(
            @PathVariable("sessionId") String sessionId,
            @Valid @RequestBody LiveCartUpdateRequest body) {
        return ApiResponse.ok(sessionService.updateLiveCartFromVision(sessionId, body));
    }

    /**
     * H54：device-service 开门指令 ACK 超时回调——立即把 OPENING 会话置为失败并释放预授权，
     * 不等 90s 兜底清扫。幂等：非终态才迁移（见 SessionOpenService.markOpenDoorFailed）。
     */
    @PostMapping("/{sessionId}/open-failed")
    public ApiResponse<Void> openDoorFailed(@PathVariable("sessionId") String sessionId,
                                            @RequestBody(required = false) OpenDoorFailedRequest body) {
        String reason = body != null && body.reason() != null && !body.reason().isBlank()
                ? body.reason() : "开门指令失败";
        sessionOpenService.markOpenDoorFailed(sessionId, reason,
                body != null ? body.deviceId() : null);
        return ApiResponse.ok(null);
    }

    /** H54 请求体：失败原因（可空）；AU-060 增报报设备（可空=非设备上报来源）。 */
    public record OpenDoorFailedRequest(String reason, String deviceId) {}
}
