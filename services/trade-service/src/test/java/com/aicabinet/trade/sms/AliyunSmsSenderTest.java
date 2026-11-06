package com.aicabinet.trade.sms;

import com.aicabinet.trade.config.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ── 回执查询（QuerySendDetails）：签名与响应解析 ──

    /**
     * 签名黄金向量（参数全部写死，nonce/timestamp 不给随机值）。
     * 期望值由两份独立实现（Python hmac 与 Node crypto）按阿里云 RPC 规范算出并互相核对，
     * 不是「把当前代码的输出录下来」——那只能证明代码自洽，不能证明它对得上阿里云。
     */
    @Test
    void rpcBody_fixedParams_shouldMatchKnownVector() throws Exception {
        java.util.TreeMap<String, String> params = new java.util.TreeMap<>();
        params.put("AccessKeyId", "testaccesskey");
        params.put("Action", "QuerySendDetails");
        params.put("CurrentPage", "1");
        params.put("Format", "JSON");
        params.put("PageSize", "50");
        params.put("PhoneNumber", "13900000001");
        params.put("RegionId", "cn-hangzhou");
        params.put("SendDate", "20261106");
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", "fixed-nonce-1");
        params.put("SignatureVersion", "1.0");
        params.put("Timestamp", "2026-11-06T02:00:00Z");
        params.put("Version", "2017-05-25");

        String body = AliyunSmsSender.rpcBody(params, "testsecret");

        assertEquals("Signature=rWonWUMj9o3fTEC5gaZR69Q1OK0%3D"
                + "&AccessKeyId=testaccesskey&Action=QuerySendDetails&CurrentPage=1&Format=JSON"
                + "&PageSize=50&PhoneNumber=13900000001&RegionId=cn-hangzhou&SendDate=20261106"
                + "&SignatureMethod=HMAC-SHA1&SignatureNonce=fixed-nonce-1&SignatureVersion=1.0"
                + "&Timestamp=2026-11-06T02%3A00%3A00Z&Version=2017-05-25", body);
    }

    @Test
    void parseSendDetails_realBody_shouldYieldRowsAndTotal() {
        String body = """
                {"TotalCount":"2","Code":"OK","RequestId":"R-2",
                 "SmsSendDetailDTOs":{"SmsSendDetailDTO":[
                   {"PhoneNum":"13900000001","SendDate":"2026-11-06 10:00:00","ReceiveDate":"2026-11-06 10:00:05",
                    "SendStatus":3,"ErrCode":"DELIVERED","TemplateCode":"SMS_1"},
                   {"PhoneNum":"13900000001","SendDate":"2026-11-06 11:00:00",
                    "SendStatus":2,"ErrCode":"CONTENT_KEYWORD","TemplateCode":"SMS_1"}]}}
                """;

        AliyunSmsSender.ReceiptPage page =
                AliyunSmsSender.parseSendDetails(200, body, new ObjectMapper());

        assertEquals(2, page.total());
        assertEquals(2, page.rows().size());
        assertEquals("13900000001", page.rows().get(0).phoneNum());
        assertEquals(3, page.rows().get(0).sendStatus());
        assertEquals("DELIVERED", page.rows().get(0).errCode());
        assertEquals("2026-11-06 10:00:05", page.rows().get(0).receiveDate());
        assertNull(page.rows().get(1).receiveDate(), "失败行没有送达时间");
        assertEquals("CONTENT_KEYWORD", page.rows().get(1).errCode());
    }

    /** 当天没发过：TotalCount=0 + 空数组是**正常应答**，不能抛（否则「无数据」被读成「查询坏了」）。 */
    @Test
    void parseSendDetails_emptyDay_shouldNotThrow() {
        AliyunSmsSender.ReceiptPage page = AliyunSmsSender.parseSendDetails(200,
                "{\"TotalCount\":\"0\",\"Code\":\"OK\",\"SmsSendDetailDTOs\":{\"SmsSendDetailDTO\":[]}}",
                new ObjectMapper());

        assertEquals(0, page.total());
        assertEquals(0, page.rows().size());
    }

    /**
     * 🔴 查询本身失败（凭据过期/权限）时阿里云回的是 200 + 业务码，且**没有行**。
     * 若按「零行 = 零失败」消费，通道坏掉那天恰好「全绿」⇒ 必须抛，让任务记 FAILED。
     */
    @Test
    void parseSendDetails_businessError_shouldThrowNotEmptyPage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AliyunSmsSender.parseSendDetails(200,
                        "{\"Code\":\"InvalidAccessKeyId.NotFound\",\"Message\":\"指定的AccessKey不存在\"}",
                        new ObjectMapper()));

        assertTrue(ex.getMessage().contains("InvalidAccessKeyId.NotFound"), ex.getMessage());
    }

    @Test
    void parseSendDetails_httpError_shouldThrow() {
        assertThrows(IllegalStateException.class, () -> AliyunSmsSender.parseSendDetails(
                403, "{\"Code\":\"Forbidden\"}", new ObjectMapper()));
    }

    @Test
    void parseSendDetails_nonJsonBody_shouldThrow() {
        assertThrows(IllegalStateException.class, () -> AliyunSmsSender.parseSendDetails(
                200, "<html>slb</html>", new ObjectMapper()));
    }

    /** 参数名是单数 PhoneNumber（复数会被 MissingPhoneNumber 拒）——写错时编译不报错，只能靠这条钉住。 */
    @Test
    void querySendDetails_notConfigured_shouldReturnEmptyWithoutNetwork() {
        AliyunSmsSender unconfigured = new AliyunSmsSender(authPropertiesWith(null, null), new ObjectMapper());

        assertEquals(0, unconfigured.querySendDetails("13900000001", "20261106").size());
    }

    private static AuthProperties authProperties() {
        return authPropertiesWith("ak", "sk");
    }

    private static AuthProperties authPropertiesWith(String accessKeyId, String accessKeySecret) {
        AuthProperties.SmsProperties sms = new AuthProperties.SmsProperties(
                "123456", 300, null, "aliyun", accessKeyId, accessKeySecret, "sign", "SMS_1", "cn-hangzhou");
        return new AuthProperties("jwt-secret", 1800, true, false, 5, 5, sms,
                AuthProperties.DEFAULT_REFRESH_GRACE_SECONDS);
    }
}
