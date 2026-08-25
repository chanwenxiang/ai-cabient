package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.ApplyBalanceRefundRequest;
import com.aicabinet.common.dto.BalanceRefundRequestDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.BalanceRefundService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/account/balance-refunds")
public class ConsumerBalanceRefundController {

    private final BalanceRefundService balanceRefundService;

    public ConsumerBalanceRefundController(BalanceRefundService balanceRefundService) {
        this.balanceRefundService = balanceRefundService;
    }

    @GetMapping
    public ApiResponse<List<BalanceRefundRequestDto>> listMine(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(balanceRefundService.listMine(userId));
    }

    @PostMapping
    public ApiResponse<BalanceRefundRequestDto> apply(
            HttpServletRequest request,
            @Valid @RequestBody ApplyBalanceRefundRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(balanceRefundService.apply(userId, body.amountCents(), body.reason()));
    }
}
