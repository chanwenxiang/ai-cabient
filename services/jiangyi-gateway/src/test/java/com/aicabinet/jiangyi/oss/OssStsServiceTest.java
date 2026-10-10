package com.aicabinet.jiangyi.oss;

import com.aicabinet.jiangyi.config.OssStsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    // ---------- CB-029 读路径（预签名 GET） ----------

    /**
     * 🔴 黄金值回归：签名串形状是「实测定出来的」（见 presignedGetUrl javadoc），
     * 期望值是独立用 HMAC-SHA1 直接算出来的，固定输入+固定 token 才可比对 ——
     * 改签名构造（加/减 x-oss-security-token 头行、是否并入 resource）必须让本用例变红。
     */
    @Test
    void presignedUrlMatchesVerifiedAlgorithm() {
        String url = OssStsService.presignedGetUrl("ai-cabinet-by", "cn-shenzhen", "jiangyi-video/a.mp4",
                "STS.ak", "STS.sk", "TOKEN", 1_791_000_000L);
        assertEquals("https://ai-cabinet-by.oss-cn-shenzhen.aliyuncs.com/jiangyi-video/a.mp4"
                + "?OSSAccessKeyId=STS.ak&Expires=1791000000"
                + "&Signature=bLhbnwcnCI%2Fa5RMA%2F3crueDwqZQ%3D&security-token=TOKEN", url);
    }

    @Test
    void presignUsesReadOnlySessionOnDirPrefix() throws Exception {
        StubStsService service = new StubStsService(props());
        OssStsService.OssPresignUrl presigned = service.presignGet("jiangyi-video/a.mp4");
        assertNotNull(presigned);
        assertTrue(presigned.url().startsWith(
                "https://ai-cabinet-by.oss-cn-shenzhen.aliyuncs.com/jiangyi-video/a.mp4?"), presigned.url());
        assertTrue(presigned.url().contains("OSSAccessKeyId=STS.ak"));
        assertTrue(presigned.url().contains("security-token=STS.token"));
        // 读会话的 inline Policy 必须是 GetObject（与上传会话的 PutObject 对称收窄）
        var policy = MAPPER.readTree(service.lastPolicy);
        assertEquals("oss:GetObject", policy.path("Statement").get(0).path("Action").get(0).asText());
        assertEquals("acs:oss:*:*:ai-cabinet-by/jiangyi-video/*",
                policy.path("Statement").get(0).path("Resource").get(0).asText());
        assertTrue(service.lastSessionName.startsWith("jy-presign-"), service.lastSessionName);
    }

    @Test
    void presignFailsClosedWhenNotConfiguredOrKeyOutOfScope() {
        assertNull(new OssStsService(new OssStsProperties()).presignGet("jiangyi-video/a.mp4"));
        StubStsService service = new StubStsService(props());
        // 不在 dirName 前缀下 → 拒绝签名（否则等于给了「签桶内任意对象」的能力）
        assertNull(service.presignGet("other-dir/a.mp4"));
        assertNull(service.presignGet("../jiangyi-video/a.mp4"));
        assertNull(service.presignGet("   "));
    }

    @Test
    void presignSecondsClampedToTightRange() {
        OssStsProperties p = props();
        p.setPresignSeconds(5);
        assertEquals(60, new OssStsService(p).presignSeconds());
        p.setPresignSeconds(99_999);
        assertEquals(3_600, new OssStsService(p).presignSeconds());
        p.setPresignSeconds(900);
        assertEquals(900, new OssStsService(p).presignSeconds());
    }

    @Test
    void resolveRefClassifiesOwnExternalAndInvalid() {
        OssStsService service = new OssStsService(props());
        // 虚拟主机式
        assertEquals("jiangyi-video/a.mp4",
                service.resolveRef("https://ai-cabinet-by.oss-cn-shenzhen.aliyuncs.com/jiangyi-video/a.mp4")
                        .objectKey());
        // 路径式（设备拿到的 endpoint 就是这种形态）
        assertEquals("jiangyi-video/a.mp4",
                service.resolveRef("http://oss-cn-shenzhen.aliyuncs.com/ai-cabinet-by/jiangyi-video/a.mp4")
                        .objectKey());
        // 裸 objectKey
        assertEquals("jiangyi-video/a.mp4", service.resolveRef("jiangyi-video/a.mp4").objectKey());
        // 别家域名 → EXTERNAL（原样使用，不签名、不算错）
        assertEquals(OssStsService.RefKind.EXTERNAL,
                service.resolveRef("https://cdn.example.com/jiangyi-video/a.mp4").kind());
        // 本桶域名但不在授权前缀下 → INVALID（拒绝签名）
        assertEquals(OssStsService.RefKind.INVALID,
                service.resolveRef("https://ai-cabinet-by.oss-cn-shenzhen.aliyuncs.com/other/a.mp4").kind());
        assertEquals(OssStsService.RefKind.INVALID, service.resolveRef("  ").kind());
        assertEquals(OssStsService.RefKind.INVALID, service.resolveRef(null).kind());
    }
}
