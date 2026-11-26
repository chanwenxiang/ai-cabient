package com.aicabinet.trade.api;

import com.aicabinet.common.dto.WeChatOfficialBindTicketDto;
import com.aicabinet.common.dto.WeChatOfficialBindingStatusDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.ConsumerNotifyPrefService;
import com.aicabinet.trade.service.NotificationService;
import com.aicabinet.trade.service.WeChatOfficialAccountBindingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ConsumerNotificationControllerOfficialAccountTest {

    @Mock private NotificationService notificationService;
    @Mock private ConsumerNotifyPrefService notifyPrefService;
    @Mock private WeChatOfficialAccountBindingService bindingService;
    @Mock private AdminAuditService auditService;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new ConsumerNotificationController(
                notificationService, notifyPrefService, bindingService, auditService)).build();
    }

    @Test
    void bindTicketUsesLoggedInUserAndWritesAudit() throws Exception {
        when(bindingService.createTicket(100L)).thenReturn(new WeChatOfficialBindTicketDto(
                "https://open.weixin.qq.com/authorize", 300));

        mvc().perform(post("/api/v2/member/notifications/official-account/bind-ticket")
                        .requestAttr(AuthInterceptor.ATTR_USER_ID, 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300));

        verify(auditService).appendLog(100L, "WECHAT_OFFICIAL_BIND_TICKET", "USER", "100",
                "申请服务号通知授权票据");
    }

    @Test
    void statusDoesNotExposeOpenId() throws Exception {
        when(bindingService.status(100L)).thenReturn(new WeChatOfficialBindingStatusDto(true, true));

        mvc().perform(get("/api/v2/member/notifications/official-account/status")
                        .requestAttr(AuthInterceptor.ATTR_USER_ID, 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.bound").value(true))
                .andExpect(jsonPath("$.data.openId").doesNotExist());
    }
}
