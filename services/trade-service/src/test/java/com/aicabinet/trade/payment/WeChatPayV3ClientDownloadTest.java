package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.WeChatPayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class WeChatPayV3ClientDownloadTest {

    private MockRestServiceServer server;
    private WeChatPayV3Client client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        WeChatPayProperties properties = new WeChatPayProperties(
                true, "app", "mch", "https://example.test/notify", "key", "serial", "private", null, false);
        WeChatPayV3Signer signer = mock(WeChatPayV3Signer.class);
        when(signer.sign(anyString(), anyString())).thenReturn("signature");
        client = new WeChatPayV3Client(properties, signer,
                mock(WeChatPlatformCertificateStore.class), new ObjectMapper(), builder);
    }

    @Test
    void downloadSignsRequestAndVerifiesHash() throws Exception {
        byte[] payload = "csv-data".getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(payload));
        server.expect(requestTo("https://api.mch.weixin.qq.com/v3/billdownload/file?token=t"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", containsString("WECHATPAY2-SHA256-RSA2048")))
                .andRespond(withSuccess(payload, MediaType.TEXT_PLAIN));

        assertArrayEquals(payload, client.download(
                "https://api.mch.weixin.qq.com/v3/billdownload/file?token=t", "SHA1", hash));
        server.verify();
    }

    @Test
    void downloadRejectsHashMismatch() {
        server.expect(requestTo("https://api.mch.weixin.qq.com/v3/billdownload/file?token=t"))
                .andRespond(withSuccess("csv-data", MediaType.TEXT_PLAIN));

        assertThrows(IllegalStateException.class, () -> client.download(
                "https://api.mch.weixin.qq.com/v3/billdownload/file?token=t", "SHA1", "bad"));
        server.verify();
    }
}
