package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "replenishment_route")
public class ReplenishmentRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long routeId;

    @Column(nullable = false, length = 128)
    private String routeName;

    private Long assigneeUserId;

    @Column(nullable = false)
    private LocalDate plannedDate;

    @Column(nullable = false, length = 16)
    private String status = "PLANNED";

    @Column(name = "total_distance_m")
    private Integer totalDistanceM;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "route_geo_json", columnDefinition = "jsonb")
    private String routeGeoJson;

    private Double startLatitude;
    private Double startLongitude;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public String getRouteName() { return routeName; }
    public void setRouteName(String routeName) { this.routeName = routeName; }
    public Long getAssigneeUserId() { return assigneeUserId; }
    public void setAssigneeUserId(Long assigneeUserId) { this.assigneeUserId = assigneeUserId; }
    public LocalDate getPlannedDate() { return plannedDate; }
    public void setPlannedDate(LocalDate plannedDate) { this.plannedDate = plannedDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getTotalDistanceM() { return totalDistanceM; }
    public void setTotalDistanceM(Integer totalDistanceM) { this.totalDistanceM = totalDistanceM; }
    public String getRouteGeoJson() { return routeGeoJson; }
    public void setRouteGeoJson(String routeGeoJson) { this.routeGeoJson = routeGeoJson; }
    public Double getStartLatitude() { return startLatitude; }
    public void setStartLatitude(Double startLatitude) { this.startLatitude = startLatitude; }
    public Double getStartLongitude() { return startLongitude; }
    public void setStartLongitude(Double startLongitude) { this.startLongitude = startLongitude; }
    public Instant getCreatedAt() { return createdAt; }
}
