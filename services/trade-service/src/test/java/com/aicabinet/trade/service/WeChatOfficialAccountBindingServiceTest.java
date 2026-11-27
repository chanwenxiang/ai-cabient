package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WeChatOfficialBindTicketDto;
import com.aicabinet.common.dto.WeChatOfficialBindingStatusDto;
import com.aicabinet.trade.config.WeChatOfficialAccountProperties;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.wechat.WeChatOfficialAccountClient;
import com.aicabinet.trade.wechat.MiniAppTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeChatOfficialAccountBindingServiceTest {

    private static final WeChatOfficialAccountProperties PROPS =
            new WeChatOfficialAccountProperties(true, "gh_test", "secret", "consumer-tpl",
                    "", "https://example.test/callback", "consumer-app", "pages/messages/messages",
                    "merchant-app", "pages/home/home", "merchant-report-tpl", "refund-tpl",
                    "withdraw-tpl", "withdraw-failed-tpl", "dispute-tpl", "测试品牌");

    @Mock private WeChatOfficialAccountClient client;
    @Mock private WeChatOfficialBindingTicketStore ticketStore;
    @Mock private AccountService accountService;
    @Mock private NotifyChannelSwitchService channelSwitch;

    private WeChatOfficialAccountBindingService service() {
        return new WeChatOfficialAccountBindingService(PROPS, client, ticketStore, accountService, channelSwitch);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(channelSwitch.wechatEnabled()).thenReturn(true);
    }

    @Test
    void createTicketReturnsAuthorizeUrlWithoutExposingOpenId() {
        WeChatOfficialBindingTicketStore.Issued issued =
                new WeChatOfficialBindingTicketStore.Issued("raw-ticket", 100L, "gh_test", Instant.now().plusSeconds(300));
        when(ticketStore.issue(100L, "gh_test")).thenReturn(issued);
        when(client.authorizeUrl("raw-ticket", "https://example.test/callback"))
                .thenReturn("https://open.weixin.qq.com/authorize");

        WeChatOfficialBindTicketDto dto = service().createTicket(100L);

        assertEquals("https://open.weixin.qq.com/authorize", dto.authorizeUrl());
        assertEquals(300, dto.expiresInSeconds());
    }

    @Test
    void statusOnlyReportsBoundForCurrentAppId() {
        UserInfo user = new UserInfo();
        user.setWxOfficialOpenId("official-openid");
        user.setWxOfficialAppId("gh_test");
        when(accountService.officialAccountBinding(100L)).thenReturn(user);

        WeChatOfficialBindingStatusDto dto = service().status(100L);

        assertTrue(dto.configured());
        assertTrue(dto.bound());
    }

    @Test
    void consumerBindingUnavailableWhenOnlyRefundTemplateConfigured() {
        WeChatOfficialAccountProperties props = new WeChatOfficialAccountProperties(
                true, "gh_test", "secret", "", "", "https://example.test/callback",
                "consumer-app", "pages/messages/messages", "merchant-app", "pages/home/home",
                "", "refund-tpl", "", "", "", "");
        WeChatOfficialAccountBindingService service =
                new WeChatOfficialAccountBindingService(props, client, ticketStore, accountService, channelSwitch);

        assertFalse(service.status(100L).configured());
    }

    @Test
    void merchantBindingUnavailableWhenOnlyWithdrawTemplateConfigured() {
        WeChatOfficialAccountProperties props = new WeChatOfficialAccountProperties(
                true, "gh_test", "secret", "", "", "https://example.test/callback",
                "consumer-app", "pages/messages/messages", "merchant-app", "pages/home/home",
                "", "", "withdraw-tpl", "", "", "");
        WeChatOfficialAccountBindingService service =
                new WeChatOfficialAccountBindingService(props, client, ticketStore, accountService, channelSwitch);

        assertFalse(service.status(100L, com.aicabinet.trade.wechat.MiniAppTarget.MERCHANT).configured());
    }

    @Test
    void merchantBindingAvailableWhenAllMerchantTemplatesConfigured() {
        assertTrue(service().status(100L, com.aicabinet.trade.wechat.MiniAppTarget.MERCHANT).configured());
    }

    @Test
    void globalWechatSwitchOff_hidesBindingCapability() {
        when(channelSwitch.wechatEnabled()).thenReturn(false);

        assertFalse(service().status(100L).configured());
    }

    @Test
    void completeRejectsReplayedTicketBeforeCallingWechat() {
        when(ticketStore.consume("state")).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().complete("code", "state"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(client, never()).exchangeCodeForOpenId(any());
    }

    @Test
    void completeRejectsAppIdChange() {
        when(ticketStore.consume("state")).thenReturn(Optional.of(
                new WeChatOfficialBindingTicketStore.Issued("state", 100L, "gh_old", Instant.now().plusSeconds(300))));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().complete("code", "state"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(client, never()).exchangeCodeForOpenId(any());
    }

    @Test
    void completeBindsOfficialOpenId() {
        when(ticketStore.consume("state")).thenReturn(Optional.of(
                new WeChatOfficialBindingTicketStore.Issued("state", 100L, "gh_test", Instant.now().plusSeconds(300))));
        when(client.exchangeCodeForOpenId("code")).thenReturn("official-openid");

        service().complete("code", "state");

        verify(accountService).bindWxOfficialOpenId(100L, "gh_test", "official-openid");
    }

    @Test
    void completeMapsUniqueConflict() {
        when(ticketStore.consume("state")).thenReturn(Optional.of(
                new WeChatOfficialBindingTicketStore.Issued("state", 100L, "gh_test", Instant.now().plusSeconds(300))));
        when(client.exchangeCodeForOpenId("code")).thenReturn("official-openid");
        doThrow(new DuplicateKeyException("duplicate")).when(accountService)
                .bindWxOfficialOpenId(100L, "gh_test", "official-openid");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().complete("code", "state"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void merchantBindingRejectsMissingRedirectUri() {
        WeChatOfficialAccountProperties noRedirect = new WeChatOfficialAccountProperties(
                true, "gh_test", "secret", "consumer-tpl", "", "", "consumer-app", "pages/messages/messages",
                "merchant-app", "pages/home/home", "merchant-report-tpl", "refund-tpl",
                "withdraw-tpl", "withdraw-failed-tpl", "dispute-tpl", "测试品牌");
        WeChatOfficialAccountBindingService service =
                new WeChatOfficialAccountBindingService(noRedirect, client, ticketStore, accountService, channelSwitch);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.createTicket(100L, MiniAppTarget.MERCHANT));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        verify(ticketStore, never()).issue(anyLong(), any());
    }

    @Test
    void operatorCanCreateTicketForMerchantReport() {
        WeChatOfficialBindingTicketStore.Issued issued = new WeChatOfficialBindingTicketStore.Issued(
                "raw-ticket", 100000006L, "gh_test", Instant.now().plusSeconds(300));
        when(ticketStore.issue(100000006L, "gh_test")).thenReturn(issued);
        when(client.authorizeUrl("raw-ticket", "https://example.test/callback"))
                .thenReturn("https://open.weixin.qq.com/authorize");

        WeChatOfficialBindTicketDto dto = service().createTicket(100000006L);

        assertEquals("https://open.weixin.qq.com/authorize", dto.authorizeUrl());
        verify(ticketStore).issue(100000006L, "gh_test");
    }
}
