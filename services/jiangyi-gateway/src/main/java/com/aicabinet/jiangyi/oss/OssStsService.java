package com.aicabinet.jiangyi.oss;

import com.aicabinet.jiangyi.config.OssStsProperties;
import com.aliyuncs.CommonRequest;
import com.aliyuncs.CommonResponse;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.http.ProtocolType;
import com.aliyuncs.profile.DefaultProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;

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

    /** 预签名 GET URL 结果（CB-029）；expiration 为 URL 失效时刻的 ISO8601 串。 */
    public record OssPresignUrl(String url, String expiration) {}

    /**
     * 生成对象短期预签名 GET URL（CB-029：运营侧订单视频复核的读路径）。
     *
     * <p><b>为什么要服务端签发而不是放开桶读取</b>：桶是私有的、直传角色只有 PutObject
     * （2026-10-10 实测匿名 GET/LIST 403、HEAD/DELETE 403）。给客户端补读权限等于把整个
     * 前缀的读权发到每台设备；正确做法是服务端持读会话、只发短时效 URL。</p>
     *
     * <p><b>读写隔离</b>：读会话同样 AssumeRole + inline Policy 收窄到「仅 {@code oss:GetObject}、
     * 仅本桶 {@code dirName*}」——与上传会话对称，设备端拿到的凭证读不了、运营端拿到的写不了。</p>
     *
     * @return 未配置 / key 越界 / STS 失败一律 null（调用方 fail-closed，绝不返回半可用链接）
     */
    public OssPresignUrl presignGet(String objectKey) {
        if (!properties.isConfigured()) {
            log.warn("jiangyi oss sts not configured, reject presign");
            return null;
        }
        String key = normalizeObjectKey(objectKey);
        if (key == null) {
            log.warn("jiangyi oss presign rejected: key out of scope or invalid");
            return null;
        }
        long ttl = presignSeconds();
        String policy = readPolicyJson(properties.getBucket(), properties.getDirName());
        String body = callSts(properties.getRoleArn(), sessionName("presign"),
                Math.max(sessionSeconds(), ttl), policy);
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
                log.error("jiangyi sts read session missing credentials: {}",
                        root.path("Message").asText(body));
                return null;
            }
            long expires = Instant.now().getEpochSecond() + ttl;
            String url = presignedGetUrl(properties.getBucket(), properties.getRegion(), key,
                    ak, sk, token, expires);
            return new OssPresignUrl(url, Instant.ofEpochSecond(expires).toString());
        } catch (Exception e) {
            log.error("jiangyi sts read session parse failed: {}", e.getMessage());
            return null;
        }
    }

    /** 引用（设备上报的完整 URL 或裸 objectKey）相对本桶的归属判定。 */
    public enum RefKind { OWN, EXTERNAL, INVALID }

    /** {@code OWN} 时 objectKey 非空；{@code EXTERNAL} = 不是本桶地址（调用方原样使用）；{@code INVALID} = 无法解析。 */
    public record RefResolution(RefKind kind, String objectKey) {}

    /**
     * 判定设备上报的引用是否落在本桶（决定「要不要签名」）。
     *
     * <p>协议 §4.2.14 明示 {@code videoUrls} 是<b>完整 HTTP URL 数组</b>，但没承诺域名形态，
     * 所以两种 OSS 寻址都要认：虚拟主机式 {@code {bucket}.oss-{region}.aliyuncs.com/{key}}
     * 与路径式 {@code oss-{region}.aliyuncs.com/{bucket}/{key}}（设备拿到的 endpoint 是后者）。
     * 其它域名（自定义域名/CDN/别家存储）判 EXTERNAL —— 不签，原样返回。</p>
     */
    public RefResolution resolveRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return new RefResolution(RefKind.INVALID, null);
        }
        String s = ref.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            String key = normalizeObjectKey(s);
            return key == null ? new RefResolution(RefKind.INVALID, null)
                    : new RefResolution(RefKind.OWN, key);
        }
        URI uri;
        try {
            uri = URI.create(s);
        } catch (IllegalArgumentException e) {
            return new RefResolution(RefKind.INVALID, null);
        }
        String host = uri.getHost();
        if (host == null) {
            return new RefResolution(RefKind.INVALID, null);
        }
        String bucket = properties.getBucket();
        String region = properties.getRegion();
        String path = uri.getPath() == null ? "" : uri.getPath();
        boolean virtualHost = host.equalsIgnoreCase(bucket + ".oss-" + region + ".aliyuncs.com");
        boolean pathStyleHost = host.equalsIgnoreCase("oss-" + region + ".aliyuncs.com");
        if (!virtualHost && !pathStyleHost) {
            // 别家域名（自定义域名 / CDN / 其它存储）：不签，原样给调用方使用
            return new RefResolution(RefKind.EXTERNAL, null);
        }
        String rawKey;
        if (virtualHost) {
            rawKey = path;
        } else if (path.startsWith("/" + bucket + "/")) {
            rawKey = path.substring(bucket.length() + 1);
        } else {
            // 路径式端点但指向别的桶 —— 我们的凭证签不动，也当外部地址处理
            return new RefResolution(RefKind.EXTERNAL, null);
        }
        // 🔴 是本桶域名却在授权前缀之外 ⇒ INVALID（拒绝签名）而不是 EXTERNAL：
        // 私有桶里没签名的 URL 必然 403，透传出去等于给一个坏链接。
        String key = normalizeObjectKey(rawKey);
        return key == null ? new RefResolution(RefKind.INVALID, null)
                : new RefResolution(RefKind.OWN, key);
    }

    /** URL 有效期独立于会话时长：默认 900s，夹在 [60, 3600]（点击即看，不宜过长，CB-029 隐私约束）。 */
    long presignSeconds() {
        return Math.min(3_600, Math.max(60, properties.getPresignSeconds()));
    }

    /** 只允许签配置前缀下的对象（防把「签桶内任意 key」的能力暴露出去）；越界返回 null。 */
    String normalizeObjectKey(String objectKey) {
        if (objectKey == null) {
            return null;
        }
        String key = objectKey.trim();
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        if (key.isEmpty() || key.contains("..")) {
            return null;
        }
        String dir = properties.getDirName() == null ? "" : properties.getDirName();
        if (!dir.isEmpty() && !key.startsWith(dir)) {
            return null;
        }
        return key;
    }

    /** inline Policy：会话只许 GetObject 本桶 dirName 前缀（与 {@link #uploadPolicyJson} 对称）。 */
    static String readPolicyJson(String bucket, String dirName) {
        return "{\"Version\":\"1\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":[\"oss:GetObject\"],"
                + "\"Resource\":[\"acs:oss:*:*:" + bucket + "/" + dirName + "*\"]}]}";
    }

    /**
     * OSS V1 预签名 GET URL（HMAC-SHA1 + Base64）。
     *
     * <p>🔴 签名串形状是<b>实测定出来的</b>，别凭直觉改（2026-10-10 用 OSS 错误响应里回显的
     * {@code StringToSign} 逐字对照确认）：</p>
     * <pre>GET\n \n \n {Expires}\n /{bucket}/{key}?security-token={token}</pre>
     * <p>即 —— <b>不</b>加 {@code x-oss-security-token} 头那一行，但必须把 {@code security-token}
     * 并进 CanonicalizedResource（用 STS 临时凭证时的特有要求；漏了会 SignatureDoesNotMatch，
     * 加了头行也会 SignatureDoesNotMatch）。</p>
     */
    static String presignedGetUrl(String bucket, String region, String key,
                                  String accessKeyId, String accessKeySecret,
                                  String securityToken, long expiresSeconds) {
        String resource = "/" + bucket + "/" + key + "?security-token=" + securityToken;
        String stringToSign = "GET\n\n\n" + expiresSeconds + "\n" + resource;
        String signature = hmacSha1Base64(accessKeySecret, stringToSign);
        return "https://" + bucket + ".oss-" + region + ".aliyuncs.com/" + encodePath(key)
                + "?OSSAccessKeyId=" + percentEncode(accessKeyId)
                + "&Expires=" + expiresSeconds
                + "&Signature=" + percentEncode(signature)
                + "&security-token=" + percentEncode(securityToken);
    }

    static String hmacSha1Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    /** 查询参数百分号编码：URLEncoder 把空格编成 '+'，改写为 %20（base64 的 '+' 编成 %2B 不受影响）。 */
    static String percentEncode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 路径逐段编码（保留 '/'）。 */
    static String encodePath(String key) {
        return Arrays.stream(key.split("/", -1))
                .map(OssStsService::percentEncode)
                .collect(Collectors.joining("/"));
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
            // 🔴 必须显式指定 HTTPS：CommonRequest 不指定协议时走 HTTP，STS 会直接拒绝
            // （InvalidProtocol.NeedSsl: Your request is denied as lack of ssl protect）。
            // 2026-10-10 实测暴露——之前只验到「端点需鉴权」，Java 这条 AssumeRole 实际是 500。
            request.setSysProtocol(ProtocolType.HTTPS);
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
