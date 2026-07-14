package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.dto.GravityDeltaRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.dto.VideoAttachRequest;
import com.aicabinet.common.dto.VideoUploadPresignRequest;
import com.aicabinet.common.dto.VideoUploadPresignResponse;
import com.aicabinet.trade.service.SessionService;
import com.aicabinet.trade.storage.MinioVideoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/sessions")
public class SessionInternalController {

    private final SessionService sessionService;
    private final MinioVideoService minioVideoService;

    public SessionInternalController(SessionService sessionService, MinioVideoService minioVideoService) {
        this.sessionService = sessionService;
        this.minioVideoService = minioVideoService;
    }

    @PostMapping("/door-event")
    public ApiResponse<SessionDto> doorEvent(@Valid @RequestBody DoorEventRequest request) {
        return ApiResponse.ok(sessionService.handleDoorEvent(request));
    }

    @PostMapping("/video")
    public ApiResponse<SessionDto> attachVideo(@Valid @RequestBody VideoAttachRequest request) {
        return ApiResponse.ok(sessionService.attachVideo(request));
    }

    /** 柜机端获取 MinIO 预签名上传地址（对象键由服务端统一生成）。 */
    @PostMapping("/video-upload-url")
    public ApiResponse<VideoUploadPresignResponse> presignVideoUpload(
            @Valid @RequestBody VideoUploadPresignRequest request) {
        return minioVideoService.presignVideoUpload(
                        request.deviceId(),
                        request.userId(),
                        request.sessionId(),
                        request.camera(),
                        request.extension(),
                        Boolean.TRUE.equals(request.sim()))
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "无法生成上传地址，请检查 MinIO 配置"));
    }

    /** 重力柜实时重量变化（负 delta 表示取走） */
    @PostMapping("/gravity-deltas")
    public ApiResponse<SessionDto> gravityDeltas(@Valid @RequestBody GravityDeltaRequest request) {
        return ApiResponse.ok(sessionService.attachGravityDeltas(request));
    }
}
