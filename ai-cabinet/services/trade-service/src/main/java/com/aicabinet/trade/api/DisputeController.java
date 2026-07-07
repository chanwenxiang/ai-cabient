package com.aicabinet.trade.api;



import com.aicabinet.common.dto.ApiResponse;

import com.aicabinet.common.dto.DisputeTicketDto;

import com.aicabinet.common.dto.OrderDto;

import com.aicabinet.common.dto.ResolveDisputeRequest;

import com.aicabinet.trade.auth.AuthInterceptor;

import com.aicabinet.trade.service.DisputeService;

import jakarta.servlet.http.HttpServletRequest;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;



import java.util.List;



@RestController

@RequestMapping("/api/v2/ops/disputes")

public class DisputeController {



    private final DisputeService disputeService;



    public DisputeController(DisputeService disputeService) {

        this.disputeService = disputeService;

    }



    @GetMapping

    public ApiResponse<List<DisputeTicketDto>> listOpen(HttpServletRequest request) {

        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);

        return ApiResponse.ok(disputeService.listOpenTickets(operatorId));

    }



    @PostMapping("/{ticketId}/resolve")

    public ApiResponse<OrderDto> resolve(

            HttpServletRequest request,

            @PathVariable("ticketId") String ticketId,

            @Valid @RequestBody ResolveDisputeRequest body) {

        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);

        return ApiResponse.ok(disputeService.resolveTicket(operatorId, ticketId, body));

    }

}


