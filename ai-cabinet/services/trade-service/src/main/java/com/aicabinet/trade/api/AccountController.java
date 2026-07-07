package com.aicabinet.trade.api;



import com.aicabinet.common.dto.ApiResponse;

import com.aicabinet.common.dto.AccountDto;

import com.aicabinet.trade.auth.AuthInterceptor;

import com.aicabinet.trade.config.SecurityProperties;

import com.aicabinet.trade.service.AccountService;

import com.aicabinet.trade.support.ApiMessages;

import jakarta.servlet.http.HttpServletRequest;

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

}


