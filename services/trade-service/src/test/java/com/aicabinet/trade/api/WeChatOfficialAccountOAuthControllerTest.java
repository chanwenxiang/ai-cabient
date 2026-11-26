package com.aicabinet.trade.api;

import com.aicabinet.trade.service.WeChatOfficialAccountBindingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WeChatOfficialAccountOAuthControllerTest {

    @Mock private WeChatOfficialAccountBindingService bindingService;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new WeChatOfficialAccountOAuthController(bindingService)).build();
    }

    @Test
    void callbackReturnsChineseSuccessWithoutOpenId() throws Exception {
        mvc().perform(get("/api/v2/public/wechat-official-account/callback")
                        .param("code", "code-1").param("state", "state-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("绑定成功")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("openid"))));

        verify(bindingService).complete("code-1", "state-1");
    }

    @Test
    void callbackEscapesFailureMessage() throws Exception {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "<script>alert(1)</script>")).when(bindingService).complete("code-1", "state-1");

        mvc().perform(get("/api/v2/public/wechat-official-account/callback")
                        .param("code", "code-1").param("state", "state-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>"))));
    }
}
