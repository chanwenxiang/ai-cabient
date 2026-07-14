package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.OpsExceptionService;
import com.aicabinet.trade.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/ops/admin/exceptions")
public class OpsExceptionController {
    private final OpsExceptionService service;
    private final SessionService sessionService;
    public OpsExceptionController(OpsExceptionService service, SessionService sessionService) {
        this.service = service; this.sessionService = sessionService;
    }
    @GetMapping public ApiResponse<PageResult<OpsExceptionDto>> list(HttpServletRequest request,
            @RequestParam(required=false) String status, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(service.list(operator(request), status, page, size));
    }
    @GetMapping("/{id}") public ApiResponse<OpsExceptionDetailDto> detail(HttpServletRequest request,
            @PathVariable String id) {
        return ApiResponse.ok(service.detail(operator(request), id));
    }
    @PostMapping("/{id}/claim") public ApiResponse<OpsExceptionDto> claim(HttpServletRequest request, @PathVariable String id) {
        return ApiResponse.ok(service.claim(operator(request), id));
    }
    @PostMapping("/{id}/transfer") public ApiResponse<OpsExceptionDto> transfer(HttpServletRequest request,
            @PathVariable String id, @Valid @RequestBody TransferOpsExceptionRequest body) {
        return ApiResponse.ok(service.transfer(operator(request), id, body.assigneeUserId(), body.reason()));
    }
    @PostMapping("/{id}/notes") public ApiResponse<OpsExceptionDto> note(HttpServletRequest request,
            @PathVariable String id, @Valid @RequestBody OpsExceptionNoteRequest body) {
        return ApiResponse.ok(service.addNote(operator(request), id, body.note()));
    }
    @PostMapping("/{id}/cancel-session") public ApiResponse<OpsExceptionDto> cancelSession(HttpServletRequest request,
            @PathVariable String id, @Valid @RequestBody OpsExceptionDangerActionRequest body) {
        Long operatorId = operator(request);
        OpsExceptionDetailDto detail = service.detail(operatorId, id);
        String sessionId = detail.exception().sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "该异常未关联购物会话");
        }
        sessionService.forceCancelForOperations(sessionId, body.reason());
        return ApiResponse.ok(service.resolveByAction(operatorId, id, "OPS_EXCEPTION_CANCEL_SESSION",
                body.idempotencyKey(), "已取消会话并释放设备：" + body.reason()));
    }
    @PostMapping("/{id}/retry") public ApiResponse<OpsExceptionDto> retry(HttpServletRequest request,
            @PathVariable String id, @Valid @RequestBody OpsExceptionDangerActionRequest body) {
        Long operatorId = operator(request);
        OpsExceptionDetailDto detail = service.detail(operatorId, id);
        if (alreadyProcessed(detail, body.idempotencyKey())) {
            return ApiResponse.ok(detail.exception());
        }
        String type = detail.exception().exceptionType();
        if (!java.util.Set.of("RECOGNITION_UNAVAILABLE", "RECOGNITION_FAILED", "SETTLEMENT_FAILED")
                .contains(type)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "该异常类型不支持自动重试");
        }
        String sessionId = detail.exception().sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "该异常未关联购物会话");
        }
        service.recordAction(operatorId, id, "OPS_EXCEPTION_RETRY", body.idempotencyKey(), body.reason());
        var session = sessionService.retryForOperations(sessionId);
        if (session.state() == com.aicabinet.common.enums.SessionState.COMPLETED) {
            return ApiResponse.ok(service.resolveByAction(operatorId, id, "OPS_EXCEPTION_RETRY_SUCCESS",
                    body.idempotencyKey(), "重试成功，会话已完成"));
        }
        return ApiResponse.ok(service.detail(operatorId, id).exception());
    }
    @PostMapping("/{id}/manual-resolve") public ApiResponse<OpsExceptionDto> manualResolve(
            HttpServletRequest request, @PathVariable String id,
            @Valid @RequestBody OpsManualResolveRequest body) {
        return ApiResponse.ok(service.manualResolve(operator(request), id, body.resolutionType(),
                body.items(), body.idempotencyKey(), body.reason()));
    }
    private boolean alreadyProcessed(OpsExceptionDetailDto detail, String idempotencyKey) {
        String marker = "idempotencyKey=" + idempotencyKey;
        return detail.actions().stream().anyMatch(action -> action.detail() != null
                && action.detail().contains(marker));
    }
    @PostMapping("/{id}/resolve") public ApiResponse<OpsExceptionDto> resolve(HttpServletRequest request,
            @PathVariable String id, @Valid @RequestBody ResolveOpsExceptionRequest body) {
        return ApiResponse.ok(service.resolve(operator(request), id, body.resolution()));
    }
    private Long operator(HttpServletRequest request) { return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID); }
}
