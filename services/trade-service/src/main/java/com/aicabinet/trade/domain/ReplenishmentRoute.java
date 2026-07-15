package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import java.time.Instant;
import java.time.LocalDate;

@TableName(value = "replenishment_route", autoResultMap = true)
public class ReplenishmentRoute {

    @TableId(type = IdType.AUTO)
    private Long routeId;

    private String routeName;

    private Long assigneeUserId;

    private LocalDate plannedDate;

    private String status = "PLANNED";

    private Integer totalDistanceM;

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String routeGeoJson;

    private Double startLatitude;
    private Double startLongitude;

    private Instant createdAt;

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
