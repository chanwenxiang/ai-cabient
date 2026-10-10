package com.aicabinet.jiangyi.oss;

import com.aicabinet.jiangyi.config.OssStsProperties;
import com.aliyuncs.CommonRequest;
import com.aliyuncs.CommonResponse;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * OSS STS 临时凭证签发（CB-024，V16 §4.2.6 getTempUploadToken 的实现核心）。
 *
 * <p><b>为什么用 CommonRequest 而不是 aliyun-java-sdk-sts</b>：本地离线仓库只有
 * aliyun-java-sdk-core 4.5.17（easygo SMS 引入同款），无 sts 包；STS AssumeRole 是
 * 标准 RPC 动作（product=Sts / version=2015-04-01），CommonRequest 全覆盖，
 * 不为省 40 行配置引入需联网下载的新依赖（离线构建铁律）。</p>
 *
 * <p><b>安全设计</b>：AssumeRole 时附带 inline Policy，把会话权限从角色全量收窄到
 * 「仅 {@code oss:PutObject}、仅本桶 {@code dirName*} 前缀」——即使凭证在设备端泄露，
 * 15 分钟内也只能往视频前缀写对象，读不到、删不了、出不了桶。</p>
 *
 * <p><b>可测性</b>：真实的阿里云 HTTP 调用隔离在 {@link #callSts}（包级可见可覆写），
 * 单测覆写该发一个假响应即可，不出网。</p>
 */
@Service
public class OssStsService {

    private static final Logger log = LoggerFactory.getLogger(OssStsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OssStsProperties properties;

    public OssStsService(OssStsProperties properties) {
        this.properties = properties;
    }

    /** §4.2.6 data 对象：字段名与文档表格 17 逐字对齐。 */
    public record OssUploadToken(String accessKeyId, String accessKeySecret, String securityToken,
                                 String expiration, String endpoint, String bucketName,
                                 String dirName) {}

    /**
     * 签发直传凭证；未配置或调用失败返回 null（调用方 fail-closed 返回非 200 envelope，
     * 设备侧重试即可——绝不返回半可用凭证）。
     */
    public OssUploadToken issueUploadToken(String deviceId) {
        if (!properties.isConfigured()) {
            log.warn("jiangyi oss sts not configured (JIANGYI_OSS_* env missing), reject upload token");
            return null;
        }
        String policy = uploadPolicyJson(properties.getBucket(), properties.getDirName());
        String sessionName = sessionName(deviceId);
        String body = callSts(properties.getRoleArn(), sessionName,
                properties.getSessionSeconds(), policy);
        if (body == null) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode cred = root.path("Credentials");
            String ak = cred.path("AccessKeyId").asText(null);
            String sk = cred.path("AccessKeySecret").asText(null);
            String token = cred.path("SecurityToken").asText(null);
            if (ak == null || sk == null || token == null) {
                log.error("jiangyi sts assume-role response missing credentials: {}",
                        root.path("Message").asText(body));
                return null;
            }
            String expiration = parseExpiration(cred, sessionSeconds());
            return new OssUploadToken(ak, sk, token, expiration,
                    properties.publicEndpoint(), properties.getBucket(), properties.getDirName());
        } catch (Exception e) {
            log.error("jiangyi sts assume-role parse failed: {}", e.getMessage());
            return null;
        }
    }

    long sessionSeconds() {
        // STS DurationSeconds 合法区间 [900, 43200]；配置越界一律夹回
        return Math.min(43_200, Math.max(900, properties.getSessionSeconds()));
    }

    /** 会话名约束 ^[a-zA-Z0-9.@_-]+$ 且 ≤64 字符；deviceId 异常字符一律替换（'-' 必须放字符类末尾，否则成区间操作符）。 */
    static String sessionName(String deviceId) {
        String safe = (deviceId == null ? "unknown" : deviceId)
                .replaceAll("[^a-zA-Z0-9._@-]", "-");
        String name = "jy-" + safe + "-" + System.currentTimeMillis();
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    /** inline Policy：会话只许 PutObject 到本桶 dirName 前缀。 */
    static String uploadPolicyJson(String bucket, String dirName) {
        return "{\"Version\":\"1\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":[\"oss:PutObject\"],"
                + "\"Resource\":[\"acs:oss:*:*:" + bucket + "/" + dirName + "*\"]}]}";
    }

    private static String parseExpiration(JsonNode cred, long fallbackSeconds) {
        String raw = cred.path("Expiration").asText(null);
        if (raw != null) {
            try {
                return Instant.parse(raw).toString();
            } catch (DateTimeParseException ignored) {
                // 非 ISO 格式（阿里云一般返回 ISO8601）退回本地计算
            }
        }
        return Instant.now().plusSeconds(fallbackSeconds).toString();
    }

    /**
     * 真正的 STS HTTP 调用（每次新建 client：签发频率=设备异常订单级，极低频，
     * 无连接复用收益；也避免共享 IAcsClient 的可变状态）。
     *
     * @return 响应体 JSON 字符串；任何失败返回 null（不抛——fail-closed 由调用方统一处理）
     */
    String callSts(String roleArn, String sessionName, long durationSeconds, String policy) {
        try {
            DefaultProfile profile = DefaultProfile.getProfile(
                    properties.getRegion(), properties.getAccessKeyId(), properties.getAccessKeySecret());
            DefaultAcsClient client = new DefaultAcsClient(profile);
            CommonRequest request = new CommonRequest();
            request.setSysMethod(MethodType.POST);
            request.setSysDomain("sts." + properties.getRegion() + ".aliyuncs.com");
            request.setSysVersion("2015-04-01");
            request.setSysAction("AssumeRole");
            request.putQueryParameter("RoleArn", roleArn);
            request.putQueryParameter("RoleSessionName", sessionName);
            request.putQueryParameter("DurationSeconds", String.valueOf(durationSeconds));
            request.putQueryParameter("Policy", policy);
            CommonResponse response = client.getCommonResponse(request);
            if (response == null || response.getData() == null || response.getData().isBlank()) {
                log.error("jiangyi sts assume-role empty response");
                return null;
            }
            return response.getData();
        } catch (Exception e) {
            log.error("jiangyi sts assume-role call failed: {}", e.getMessage());
            return null;
        }
    }
}
