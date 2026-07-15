package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import java.time.Instant;

@TableName(value = "ota_release", autoResultMap = true)
public class OtaRelease {

    @TableId(type = IdType.AUTO)
    private Long releaseId;

    private String appVersion;

    private String channel = "stable";

    private String downloadUrl;

    private String checksumSha256;

    private String releaseNotes;

    private boolean mandatory;

    private String minVersion;

    private String status = "DRAFT";

    private Instant publishedAt;

    private String objectStorageUri;

    private int grayPercent = 100;

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String deviceAllowlist;

    private int presignTtlSeconds = 3600;

    private Instant createdAt;

public Long getReleaseId() { return releaseId; }
    public void setReleaseId(Long releaseId) { this.releaseId = releaseId; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
    public String getChecksumSha256() { return checksumSha256; }
    public void setChecksumSha256(String checksumSha256) { this.checksumSha256 = checksumSha256; }
    public String getReleaseNotes() { return releaseNotes; }
    public void setReleaseNotes(String releaseNotes) { this.releaseNotes = releaseNotes; }
    public boolean isMandatory() { return mandatory; }
    public void setMandatory(boolean mandatory) { this.mandatory = mandatory; }
    public String getMinVersion() { return minVersion; }
    public void setMinVersion(String minVersion) { this.minVersion = minVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public String getObjectStorageUri() { return objectStorageUri; }
    public void setObjectStorageUri(String objectStorageUri) { this.objectStorageUri = objectStorageUri; }
    public int getGrayPercent() { return grayPercent; }
    public void setGrayPercent(int grayPercent) { this.grayPercent = grayPercent; }
    public String getDeviceAllowlist() { return deviceAllowlist; }
    public void setDeviceAllowlist(String deviceAllowlist) { this.deviceAllowlist = deviceAllowlist; }
    public int getPresignTtlSeconds() { return presignTtlSeconds; }
    public void setPresignTtlSeconds(int presignTtlSeconds) { this.presignTtlSeconds = presignTtlSeconds; }
    public Instant getCreatedAt() { return createdAt; }
}
