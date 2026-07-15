package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "edge_device")
public class EdgeDevice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long edgeId;
    
    @Column(length = 32, nullable = false)
    private String deviceId;
    
    @Column(length = 64)
    private String modelVersion;
    
    @Column(length = 32, nullable = false)
    private String deviceModel;
    
    @Column(nullable = false)
    private Integer cpuCores;
    
    @Column(nullable = false)
    private Integer memoryMB;
    
    @Column(nullable = false)
    private Integer storageGB;
    
    @Column(length = 16, nullable = false)
    private String status;
    
    @Column(nullable = false)
    private Instant lastSyncAt;
    
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    
    private Instant updatedAt;
    
    public Long getEdgeId() { return edgeId; }
    public void setEdgeId(Long edgeId) { this.edgeId = edgeId; }
    
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    
    public String getDeviceModel() { return deviceModel; }
    public void setDeviceModel(String deviceModel) { this.deviceModel = deviceModel; }
    
    public Integer getCpuCores() { return cpuCores; }
    public void setCpuCores(Integer cpuCores) { this.cpuCores = cpuCores; }
    
    public Integer getMemoryMB() { return memoryMB; }
    public void setMemoryMB(Integer memoryMB) { this.memoryMB = memoryMB; }
    
    public Integer getStorageGB() { return storageGB; }
    public void setStorageGB(Integer storageGB) { this.storageGB = storageGB; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant lastSyncAt) { this.lastSyncAt = lastSyncAt; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
