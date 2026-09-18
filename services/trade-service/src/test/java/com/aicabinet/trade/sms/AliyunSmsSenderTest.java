package com.aicabinet.trade.sms;

import com.aicabinet.trade.config.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H14：阿里云短信发送结果判定——业务被拒（流控等）仍返回 HTTP 200，
 * 必须解析响应体 Code，仅 OK 算成功。
 */
class AliyunSmsSenderTest {

    private final AliyunSmsSender sender = new AliyunSmsSender(authProperties(), new ObjectMapper());

    @Test
    void verifyAliyunAccepted_businessLimitCode_shouldThrow() {
        String body = "{\"Message\":\"触发分钟级流控\",\"Code\":\"isv.BUSINESS_LIMIT_CONTROL\"}";

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sender.verifyAliyunAccepted(200, body));

        assertTrue(ex.getMessage().contains("isv.BUSINESS_LIMIT_CONTROL"));
        assertTrue(ex.getMessage().contains("触发分钟级流控"));
    }

    @Test
    void verifyAliyunAccepted_ok_shouldPass() {
        String body = "{\"Message\":\"OK\",\"BizId\":\"10010\",\"Code\":\"OK\",\"RequestId\":\"R-1\"}";

        assertDoesNotThrow(() -> sender.verifyAliyunAccepted(200, body));
    }

    @Test
    void verifyAliyunAccepted_httpError_shouldThrowEvenWithBody() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sender.verifyAliyunAccepted(400, "{\"Code\":\"MissingParameter\"}"));

        assertTrue(ex.getMessage().contains("HTTP 400"));
    }

    @Test
    void verifyAliyunAccepted_nonJsonBody_shouldThrow() {
        assertThrows(IllegalStateException.class, () -> sender.verifyAliyunAccepted(200, "<html>gateway</html>"));
    }

    private static AuthProperties authProperties() {
        AuthProperties.SmsProperties sms = new AuthProperties.SmsProperties(
                "123456", 300, null, "aliyun", "ak", "sk", "sign", "SMS_1", "cn-hangzhou");
        return new AuthProperties("jwt-secret", 1800, true, false, 5, 5, sms);
    }
}
