package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ota_release")
public class OtaRelease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long releaseId;

    @Column(nullable = false, length = 32)
    private String appVersion;

    @Column(nullable = false, length = 32)
    private String channel = "stable";

    @Column(nullable = false, length = 512)
    private String downloadUrl;

    @Column(length = 64)
    private String checksumSha256;

    @Column(columnDefinition = "TEXT")
    private String releaseNotes;

    @Column(nullable = false)
    private boolean mandatory;

    @Column(length = 32)
    private String minVersion;

    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    private Instant publishedAt;

    @Column(name = "object_storage_uri", length = 512)
    private String objectStorageUri;

    @Column(nullable = false)
    private int grayPercent = 100;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "device_allowlist", columnDefinition = "jsonb")
    private String deviceAllowlist;

    @Column(name = "presign_ttl_seconds", nullable = false)
    private int presignTtlSeconds = 3600;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

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
