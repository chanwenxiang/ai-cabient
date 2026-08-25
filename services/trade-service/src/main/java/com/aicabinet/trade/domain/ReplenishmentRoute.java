package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@TableName(value = "replenishment_route", autoResultMap = true)
@Getter
@Setter
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

}
