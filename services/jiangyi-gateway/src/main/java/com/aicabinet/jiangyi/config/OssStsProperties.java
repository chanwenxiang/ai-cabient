package com.aicabinet.jiangyi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 将邑视频链路 OSS STS 配置（CB-024，V16 §4.2.6）。
 *
 * <p>注册走 JiangyiGatewayApplication 的 {@code @EnableConfigurationProperties}
 * （本类不再标 @Configuration——双注册会导致 OssStsService 构造期 two-beans 冲突，
 * 2026-10-10 实证）。全部走环境变量（infra/.env，gitignore，铁律 #27 密钥不入库）：</p>
 * <ul>
 *   <li>{@code JIANGYI_OSS_ACCESS_KEY_ID} / {@code JIANGYI_OSS_ACCESS_KEY_SECRET}：
 *       RAM 子账号 AK，只授 {@code sts:AssumeRole}（**禁用主账号 AK**）；</li>
 *   <li>{@code JIANGYI_OSS_ROLE_ARN}：直传角色 ARN（acs:ram::&lt;uid&gt;:role/xxx，
 *       角色信任 STS，授权仅限桶内 {@code dirName*} 的 PutObject）；</li>
 *   <li>{@code JIANGYI_OSS_REGION}（默认 cn-shenzhen）、{@code JIANGYI_OSS_BUCKET}
 *       （ai-cabinet-by）、{@code JIANGYI_OSS_DIR_NAME}（默认 jiangyi-video/）、
 *       {@code JIANGYI_OSS_SESSION_SECONDS}（默认 900，15 分钟）。</li>
 * </ul>
 *
 * <p>enabled 由 accessKeyId/roleArn/bucket 三者齐备推导——任一缺失即未配置，
 * getTempUploadToken fail-closed 返回非 200（设备侧重试，不产生半可用凭证）。</p>
 */
@ConfigurationProperties(prefix = "aicabinet.jiangyi.oss")
public class OssStsProperties {

    private String accessKeyId;
    private String accessKeySecret;
    private String roleArn;
    private String region = "cn-shenzhen";
    private String bucket = "ai-cabinet-by";
    private String dirName = "jiangyi-video/";
    private long sessionSeconds = 900;
    /** 预签名 GET URL 有效期（秒，CB-029 读路径）；夹在 [60, 3600]。 */
    private long presignSeconds = 900;

    public boolean isConfigured() {
        return accessKeyId != null && !accessKeyId.isBlank()
                && accessKeySecret != null && !accessKeySecret.isBlank()
                && roleArn != null && !roleArn.isBlank()
                && bucket != null && !bucket.isBlank();
    }

    /** STS 服务端点（AssumeRole 调用目标，与桶 endpoint 无关）。 */
    public String stsEndpoint() {
        return "https://sts." + region + ".aliyuncs.com";
    }

    /** 设备直传用的 OSS endpoint（文档 dirName/endpoint 字段语义）。 */
    public String publicEndpoint() {
        return "https://oss-" + region + ".aliyuncs.com";
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getAccessKeySecret() {
        return accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
        this.accessKeySecret = accessKeySecret;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getDirName() {
        return dirName;
    }

    public void setDirName(String dirName) {
        this.dirName = dirName;
    }

    public long getSessionSeconds() {
        return sessionSeconds;
    }

    public void setSessionSeconds(long sessionSeconds) {
        this.sessionSeconds = sessionSeconds;
    }

    public long getPresignSeconds() {
        return presignSeconds;
    }

    public void setPresignSeconds(long presignSeconds) {
        this.presignSeconds = presignSeconds;
    }
}
