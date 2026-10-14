package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;

/** line_route（V358 线路组织）。 */
@TableName("line_route")
public class LineRoute {
    // 🔴 库列名是 route_id（V358），Java 字段名保持 id（JSON 输出 id，前端零改动）
    @TableId(value = "route_id", type = IdType.AUTO)
    private Long id;
    private String routeName;
    private Long managerId;
    private String remark;
    private String status;
    private Instant createdAt;
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getRouteName() { return routeName; }
    public void setRouteName(String v) { this.routeName = v; }
    public Long getManagerId() { return managerId; }
    public void setManagerId(Long v) { this.managerId = v; }
    public String getRemark() { return remark; }
    public void setRemark(String v) { this.remark = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
