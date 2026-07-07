package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DisputeTicketDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.DisputeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2/disputes")
public class ConsumerDisputeController {

    private final DisputeService disputeService;

    public ConsumerDisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @GetMapping("/mine")
    public ApiResponse<List<DisputeTicketDto>> mine(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(disputeService.listMyTickets(userId));
    }
}
