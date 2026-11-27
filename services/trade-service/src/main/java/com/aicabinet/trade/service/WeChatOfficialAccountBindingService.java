package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WeChatOfficialBindTicketDto;
import com.aicabinet.common.dto.WeChatOfficialBindingStatusDto;
import com.aicabinet.trade.config.WeChatOfficialAccountProperties;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.wechat.WeChatOfficialAccountClient;
import com.aicabinet.trade.wechat.MiniAppTarget;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WeChatOfficialAccountBindingService {

    private final WeChatOfficialAccountProperties properties;
    private final WeChatOfficialAccountClient client;
    private final WeChatOfficialBindingTicketStore ticketStore;
    private final AccountService accountService;

    public WeChatOfficialAccountBindingService(WeChatOfficialAccountProperties properties,
                                               WeChatOfficialAccountClient client,
                                               WeChatOfficialBindingTicketStore ticketStore,
                                               AccountService accountService) {
        this.properties = properties;
        this.client = client;
        this.ticketStore = ticketStore;
        this.accountService = accountService;
    }

    public WeChatOfficialBindTicketDto createTicket(Long userId) {
        return createTicket(userId, MiniAppTarget.CONSUMER);
    }

    public WeChatOfficialBindTicketDto createTicket(Long userId, MiniAppTarget target) {
        requireAccount(userId);
        if (!isConfigured(target)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务号通知尚未配置");
        }
        WeChatOfficialBindingTicketStore.Issued issued = ticketStore.issue(userId, properties.appId());
        String authorizeUrl = client.authorizeUrl(issued.ticket(), properties.resolveRedirectUri());
        return new WeChatOfficialBindTicketDto(authorizeUrl, (int) WeChatOfficialBindingTicketStore.TTL.toSeconds());
    }

    public WeChatOfficialBindingStatusDto status(Long userId) {
        return status(userId, MiniAppTarget.CONSUMER);
    }

    public WeChatOfficialBindingStatusDto status(Long userId, MiniAppTarget target) {
        requireAccount(userId);
        UserInfo user = accountService.officialAccountBinding(userId);
        boolean bound = user != null
                && user.getWxOfficialOpenId() != null && !user.getWxOfficialOpenId().isBlank()
                && properties.appId() != null && properties.appId().equals(user.getWxOfficialAppId());
        return new WeChatOfficialBindingStatusDto(isConfigured(target), bound);
    }

    private boolean isConfigured(MiniAppTarget target) {
        if (!properties.isBindingConfigured()) {
            return false;
        }
        return target == MiniAppTarget.MERCHANT
                ? properties.isMerchantDailyReportConfigured() : properties.isConfigured();
    }

    public void complete(String code, String state) {
        WeChatOfficialBindingTicketStore.Issued issued = ticketStore.consume(state)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "绑定凭证无效或已过期"));
        if (!properties.appId().equals(issued.appId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "服务号配置已变更，请重新绑定");
        }
        String openId = client.exchangeCodeForOpenId(code);
        if (openId == null || openId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "微信授权失败，请重试");
        }
        try {
            accountService.bindWxOfficialOpenId(issued.userId(), issued.appId(), openId);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该账号已绑定服务号，请刷新后重试");
        }
    }

    private static void requireAccount(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅正常账号支持服务号通知");
        }
    }
}
