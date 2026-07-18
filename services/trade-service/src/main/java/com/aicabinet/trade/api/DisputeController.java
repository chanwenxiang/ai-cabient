package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.CloseDisputeRequest;
import com.aicabinet.common.dto.DisputeTicketDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.ReopenDisputeRequest;
import com.aicabinet.common.dto.ResolveDisputeRequest;
import com.aicabinet.common.dto.ResolveDisputeResultDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.DisputeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/ops/disputes")
public class DisputeController {

    private final DisputeService disputeService;

    public DisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @RequiresPermissions("ops:dispute")
    @GetMapping
    public ApiResponse<PageResult<DisputeTicketDto>> list(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(disputeService.listTickets(operatorId, page, size, status, sessionId, deviceId));
    }

    @RequiresPermissions("ops:dispute:resolve")
    @PostMapping("/{ticketId}/resolve")
    public ApiResponse<ResolveDisputeResultDto> resolve(
            HttpServletRequest request,
            @PathVariable("ticketId") String ticketId,
            @Valid @RequestBody ResolveDisputeRequest body) {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(disputeService.resolveTicket(operatorId, ticketId, body));
    }

    @RequiresPermissions("ops:dispute:resolve")
    @PostMapping("/{ticketId}/close")
    public ApiResponse<DisputeTicketDto> close(
            HttpServletRequest request,
            @PathVariable("ticketId") String ticketId,
            @Valid @RequestBody(required = false) CloseDisputeRequest body) {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(disputeService.closeTicket(operatorId, ticketId, body));
    }

    @RequiresPermissions("ops:dispute:resolve")
    @PostMapping("/{ticketId}/reopen")
    public ApiResponse<DisputeTicketDto> reopen(
            HttpServletRequest request,
            @PathVariable("ticketId") String ticketId,
            @Valid @RequestBody(required = false) ReopenDisputeRequest body) {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(disputeService.reopenTicket(operatorId, ticketId, body));
    }
}
