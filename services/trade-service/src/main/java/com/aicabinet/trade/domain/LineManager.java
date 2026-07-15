package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "line_manager")
public class LineManager {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long managerId;
    
    @Column(length = 100, nullable = false)
    private String managerName;
    
    @Column(length = 32, nullable = false)
    private String phone;
    
    @Column(length = 64)
    private String employeeId;
    
    @Column(nullable = false)
    private Long franchiseId;
    
    @Column(precision = 10, scale = 4)
    private java.math.BigDecimal commissionRate;
    
    @Column(length = 16, nullable = false)
    private String status;
    
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    
    private Instant updatedAt;
    
    public Long getManagerId() { return managerId; }
    public void setManagerId(Long managerId) { this.managerId = managerId; }
    
    public String getManagerName() { return managerName; }
    public void setManagerName(String managerName) { this.managerName = managerName; }
    
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }
    
    public Long getFranchiseId() { return franchiseId; }
    public void setFranchiseId(Long franchiseId) { this.franchiseId = franchiseId; }
    
    public java.math.BigDecimal getCommissionRate() { return commissionRate; }
    public void setCommissionRate(java.math.BigDecimal commissionRate) { this.commissionRate = commissionRate; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
