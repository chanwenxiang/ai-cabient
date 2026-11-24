package com.aicabinet.trade.wechat;

import com.aicabinet.trade.config.WeChatOfficialAccountProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeChatOfficialAccountClientTest {

    private static final Instant NOW = Instant.parse("2026-11-24T04:00:00Z");
    private static final WeChatOfficialAccountProperties CONFIGURED =
            new WeChatOfficialAccountProperties(true, "gh_test", "secret", "template-1", "https://example.test/report");

    private MutableClock clock;
    private MockRestServiceServer server;
    private WeChatOfficialAccountClient client;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WeChatOfficialAccountClient(CONFIGURED, new ObjectMapper(), clock, builder);
    }

    @Test
    void disabledOrIncompleteConfigurationNeverCallsWechat() {
        WeChatOfficialAccountClient disabled = new WeChatOfficialAccountClient(
                new WeChatOfficialAccountProperties(false, "gh_test", "secret", "template-1", ""),
                new ObjectMapper(), clock, RestClient.builder());

        assertThat(disabled.sendTemplateMessage("openid", Map.of("amount1", "1.00"))).isFalse();
        server.verify();
    }

    @Test
    void blankOpenidOrDataNeverCallsWechat() {
        assertThat(client.sendTemplateMessage(" ", Map.of("amount1", "1.00"))).isFalse();
        assertThat(client.sendTemplateMessage("openid", Map.of())).isFalse();
        server.verify();
    }

    @Test
    void configuredSendBuildsTemplateBodyAndReturnsTrue() {
        server.expect(requestTo(containsString("/cgi-bin/token")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"access_token\":\"token-1\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/cgi-bin/message/template/send")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "touser": "openid-1",
                          "template_id": "template-1",
                          "data": {
                            "amount1": {"value": "12.50"},
                            "number2": {"value": "3"}
                          },
                          "url": "https://example.test/report"
                        }
                        """))
                .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", MediaType.APPLICATION_JSON));

        Map<String, String> data = new LinkedHashMap<>();
        data.put("amount1", "12.50");
        data.put("number2", "3");
        assertThat(client.sendTemplateMessage("openid-1", data)).isTrue();
        server.verify();
    }

    @Test
    void accessTokenIsCachedUntilSafetyWindow() {
        server.expect(requestTo(containsString("/cgi-bin/token")))
                .andRespond(withSuccess("{\"access_token\":\"token-1\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));

        assertThat(client.accessToken()).isEqualTo("token-1");
        clock.advanceSeconds(6_000);
        assertThat(client.accessToken()).isEqualTo("token-1");
        server.verify();
    }

    @Test
    void accessTokenRefreshesAfterExpirySafetyWindow() {
        server.expect(requestTo(containsString("/cgi-bin/token")))
                .andRespond(withSuccess("{\"access_token\":\"token-1\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/cgi-bin/token")))
                .andRespond(withSuccess("{\"access_token\":\"token-2\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));

        assertThat(client.accessToken()).isEqualTo("token-1");
        clock.advanceSeconds(7_200);
        assertThat(client.accessToken()).isEqualTo("token-2");
        server.verify();
    }

    @Test
    void wechatErrorReturnsFalse() {
        server.expect(requestTo(containsString("/cgi-bin/token")))
                .andRespond(withSuccess("{\"access_token\":\"token-1\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/cgi-bin/message/template/send")))
                .andRespond(withSuccess("{\"errcode\":43004,\"errmsg\":\"require subscribe\"}", MediaType.APPLICATION_JSON));

        assertThat(client.sendTemplateMessage("openid-1", Map.of("amount1", "12.50"))).isFalse();
        server.verify();
    }

    @Test
    void missingSuccessCodeReturnsFalse() {
        server.expect(requestTo(containsString("/cgi-bin/token")))
                .andRespond(withSuccess("{\"access_token\":\"token-1\",\"expires_in\":7200}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/cgi-bin/message/template/send")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.sendTemplateMessage("openid-1", Map.of("amount1", "12.50"))).isFalse();
        server.verify();
    }

    @Test
    void networkFailureReturnsFalse() {
        server.expect(requestTo(containsString("/cgi-bin/token")))
                .andRespond(withServerError());

        assertThat(client.sendTemplateMessage("openid-1", Map.of("amount1", "12.50"))).isFalse();
        server.verify();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
