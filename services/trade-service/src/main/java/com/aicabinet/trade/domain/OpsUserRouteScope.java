package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@TableName("ops_user_route_scope")
@Getter
@Setter
public class OpsUserRouteScope {
    private Long userId;
    private String routeCode;
}
