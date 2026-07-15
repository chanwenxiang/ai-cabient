package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "franchise_device")
public class FranchiseDevice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long franchiseId;
    
    @Column(length = 32, nullable = false)
    private String deviceId;
    
    @Column(length = 16, nullable = false)
    private String status;
    
    @Column(nullable = false)
    private Instant assignedAt = Instant.now();
    
    private Instant unassignedAt;
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getFranchiseId() { return franchiseId; }
    public void setFranchiseId(Long franchiseId) { this.franchiseId = franchiseId; }
    
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }
    
    public Instant getUnassignedAt() { return unassignedAt; }
    public void setUnassignedAt(Instant unassignedAt) { this.unassignedAt = unassignedAt; }
}
