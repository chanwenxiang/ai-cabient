package com.aicabinet.trade.api;

import com.aicabinet.common.dto.AccountDto;
import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.SetPayPreferredChannelRequest;
import com.aicabinet.common.dto.VerifyIdentityRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.service.AccountService;
import com.aicabinet.trade.support.ApiMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v2/account")
public class AccountController {

    private final AccountService accountService;
    private final SecurityProperties securityProperties;

    public AccountController(AccountService accountService, SecurityProperties securityProperties) {
        this.accountService = accountService;
        this.securityProperties = securityProperties;
    }

    @GetMapping
    public ApiResponse<AccountDto> getAccount(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(accountService.getAccount(userId));
    }

    @GetMapping("/transactions")
    public ApiResponse<com.aicabinet.common.dto.PageResult<com.aicabinet.common.dto.BalanceTransactionDto>> transactions(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(accountService.transactions(userId, page, size));
    }

    @PostMapping("/verify")
    public ApiResponse<AccountDto> verifyIdentity(
            HttpServletRequest request,
            @Valid @RequestBody VerifyIdentityRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(accountService.verifyIdentity(userId, body));
    }

    /** 设置结算优先支付方式：BALANCE / WECHAT / ALIPAY */
    @PutMapping("/pay-preferred")
    public ApiResponse<AccountDto> setPayPreferred(
            HttpServletRequest request,
            @Valid @RequestBody SetPayPreferredChannelRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(accountService.setPayPreferredChannel(userId, body.channel()));
    }

    /** 仅开发环境：生产环境应通过微信 code2session 绑定 openId */
    @PostMapping("/bind-openid")
    public ApiResponse<Void> bindOpenId(HttpServletRequest request, @RequestParam("openId") String openId) {
        if (!securityProperties.mockEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.BIND_OPENID_DISABLED);
        }
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        accountService.bindWxOpenId(userId, openId);
        return ApiResponse.ok(null);
    }

    /** 开通微信支付分免密（mock / 生产对接微信 API） */
    @PostMapping("/payscore/sign")
    public ApiResponse<com.aicabinet.common.dto.PayContractDto> signPayScore(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(accountService.signWeChatPayScore(userId));
    }

    /** 开通支付宝免密代扣 */
    @PostMapping("/alipay-agreement/sign")
    public ApiResponse<com.aicabinet.common.dto.PayContractDto> signAlipayAgreement(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(accountService.signAlipayAgreement(userId));
    }
}
