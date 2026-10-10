package com.aicabinet.jiangyi.oss;

import com.aicabinet.jiangyi.config.OssStsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STS 签发单元测试（不出网：callSts 覆写为假响应）。
 * 覆盖：未配置 fail-closed / Policy 收窄 / 会话名消毒 / envelope 解析 / 异常响应。
 */
class OssStsServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static OssStsProperties props() {
        OssStsProperties p = new OssStsProperties();
        p.setAccessKeyId("ak-test");
        p.setAccessKeySecret("sk-test");
        p.setRoleArn("acs:ram::123:role/jiangyi-upload");
        p.setRegion("cn-shenzhen");
        p.setBucket("ai-cabinet-by");
        p.setDirName("jiangyi-video/");
        return p;
    }

    /** 记录最后一次 callSts 参数并返回固定假响应的测试服务。 */
    static class StubStsService extends OssStsService {
        String lastPolicy;
        String lastRoleArn;
        String lastSessionName;
        String responseBody = """
                {"RequestId":"r1","AssumeRoleUser":{"Arn":"arn"},"Credentials":{
                  "AccessKeyId":"STS.ak","AccessKeySecret":"STS.sk","SecurityToken":"STS.token",
                  "Expiration":"2026-10-10T09:00:00Z"}}
                """;

        StubStsService(OssStsProperties properties) {
            super(properties);
        }

        @Override
        String callSts(String roleArn, String sessionName, long durationSeconds, String policy) {
            this.lastRoleArn = roleArn;
            this.lastSessionName = sessionName;
            this.lastPolicy = policy;
            return responseBody;
        }
    }

    @Test
    void notConfiguredFailsClosed() {
        // 任一密钥/角色缺失 → 不出网直接拒绝（不给半可用凭证）
        OssStsService service = new OssStsService(new OssStsProperties());
        assertNull(service.issueUploadToken("100000000001"));
    }

    @Test
    void issueReturnsDocumentAlignedEnvelope() throws Exception {
        StubStsService service = new StubStsService(props());
        OssStsService.OssUploadToken token = service.issueUploadToken("100000000001");
        assertEquals("STS.ak", token.accessKeyId());
        assertEquals("STS.sk", token.accessKeySecret());
        assertEquals("STS.token", token.securityToken());
        // endpoint/bucket/dirName 与 §4.2.6 data 字段对齐（表格 17）
        assertEquals("https://oss-cn-shenzhen.aliyuncs.com", token.endpoint());
        assertEquals("ai-cabinet-by", token.bucketName());
        assertEquals("jiangyi-video/", token.dirName());
        assertEquals("2026-10-10T09:00:00Z", token.expiration());
        // AssumeRole 参数
        assertEquals("acs:ram::123:role/jiangyi-upload", service.lastRoleArn);
        assertTrue(service.lastSessionName.startsWith("jy-100000000001-"));
    }

    @Test
    void policyNarrowsToPutObjectOnDirPrefix() throws Exception {
        StubStsService service = new StubStsService(props());
        service.issueUploadToken("100000000001");
        var policy = MAPPER.readTree(service.lastPolicy);
        assertEquals("1", policy.path("Version").asText());
        assertEquals("oss:PutObject", policy.path("Statement").get(0).path("Action").get(0).asText());
        assertEquals("acs:oss:*:*:ai-cabinet-by/jiangyi-video/*",
                policy.path("Statement").get(0).path("Resource").get(0).asText());
    }

    @Test
    void sessionNameSanitizesIllegalCharacters() {
        String name = OssStsService.sessionName("dev/abc XY@1");
        assertTrue(name.matches("[a-zA-Z0-9._@-]+"), name);
        assertTrue(name.length() <= 64);
    }

    @Test
    void sessionSecondsClampedToLegalRange() {
        OssStsProperties p = props();
        p.setSessionSeconds(10);
        assertEquals(900, new OssStsService(p).sessionSeconds());
        p.setSessionSeconds(99_999);
        assertEquals(43_200, new OssStsService(p).sessionSeconds());
    }

    @Test
    void missingCredentialsInResponseReturnsNull() {
        StubStsService service = new StubStsService(props());
        service.responseBody = "{\"Message\":\"InvalidParameter.RoleArn\"}";
        assertNull(service.issueUploadToken("100000000001"));
    }

    @Test
    void garbageResponseReturnsNull() {
        StubStsService service = new StubStsService(props());
        service.responseBody = "not-json";
        assertNull(service.issueUploadToken("100000000001"));
    }

    @Test
    void expirationFallbackComputedLocallyWhenAbsent() throws Exception {
        StubStsService service = new StubStsService(props());
        service.responseBody = """
                {"Credentials":{"AccessKeyId":"a","AccessKeySecret":"b","SecurityToken":"c"}}
                """;
        OssStsService.OssUploadToken token = service.issueUploadToken("d1");
        Instant expiration = Instant.parse(token.expiration());
        assertTrue(expiration.isAfter(Instant.now()));
    }
}
