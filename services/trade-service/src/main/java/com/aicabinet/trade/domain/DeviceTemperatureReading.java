package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "device_temperature_reading")
public class DeviceTemperatureReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long readingId;

    @Column(nullable = false, length = 64)
    private String deviceId;

    @Column(name = "temp_c", nullable = false)
    private int tempC;

    @Column(nullable = false, updatable = false)
    private Instant reportedAt;

    @PrePersist
    void prePersist() {
        if (reportedAt == null) {
            reportedAt = Instant.now();
        }
    }

    public Long getReadingId() { return readingId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public int getTempC() { return tempC; }
    public void setTempC(int tempC) { this.tempC = tempC; }
    public Instant getReportedAt() { return reportedAt; }
    public void setReportedAt(Instant reportedAt) { this.reportedAt = reportedAt; }
}
