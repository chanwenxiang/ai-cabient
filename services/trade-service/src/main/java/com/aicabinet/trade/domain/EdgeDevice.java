package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("edge_device")
public class EdgeDevice {
    @TableId(type = IdType.AUTO)
    private Long edgeId;

    
    private String deviceId;
    
    private String modelVersion;
    
    private String deviceModel;
    
    private Integer cpuCores;
    
    private Integer memoryMB;
    
    private Integer storageGB;
    
    private String status;
    
    private Instant lastSyncAt;
    
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
