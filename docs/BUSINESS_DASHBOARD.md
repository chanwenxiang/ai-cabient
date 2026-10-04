# AI Cabinet 业务监控Dashboard

## 📊 Dashboard概览

### 访问地址
- Grafana: http://monitor.aicabinet.com
- Prometheus: http://prometheus.aicabinet.com
- 默认账号: admin / admin123

---

## 1. 业务总览Dashboard

### 核心指标

#### 实时数据
`promql
# 今日订单数
sum(increase(aicabinet_orders_total[24h]))

# 今日GMV（元）
sum(increase(aicabinet_order_amount_total[24h])) / 100

# 当前在线设备数
count(aicabinet_device_status{status="online"})

# 当前活跃用户数
count(aicabinet_user_active)
`

#### 核心业务指标面板

| 指标名称 | PromQL | 说明 |
|---------|--------|------|
| 订单总量 | sum(aicabinet_orders_total) | 累计订单数量 |
| 今日订单 | sum(increase(aicabinet_orders_total[24h])) | 今日新增订单 |
| 今日GMV | sum(increase(aicabinet_order_amount_total[24h]))/100 | 今日交易总额 |
| 在线设备 | count(aicabinet_device_status{status="online"}) | 在线设备数量 |
| 活跃用户 | count(aicabinet_user_active) | 当前活跃用户 |

---

## 2. 订单监控Dashboard

### 订单趋势

`promql
# 订单量趋势（每5分钟）
rate(aicabinet_orders_total[5m])

# 订单金额趋势
rate(aicabinet_order_amount_total[5m]) / 100

# 订单状态分布
sum by (status) (aicabinet_orders_total)

# 订单失败率
rate(aicabinet_orders_total{status="failed"}[5m]) / 
rate(aicabinet_orders_total[5m])
`

### 订单性能指标

`promql
# 订单创建耗时P95
histogram_quantile(0.95, 
  rate(aicabinet_order_creation_seconds_bucket[5m]))

# 订单支付耗时P95
histogram_quantile(0.95, 
  rate(aicabinet_order_payment_seconds_bucket[5m]))

# 订单完成耗时P95
histogram_quantile(0.95, 
  rate(aicabinet_order_completion_seconds_bucket[5m]))
`

### 订单告警规则

| 告警名称 | 触发条件 | 级别 |
|---------|---------|------|
| 订单失败率高 | 订单失败率 > 10% | P0 |
| 订单量异常低 | 订单量 < 历史平均50% | P1 |
| 订单创建慢 | P95耗时 > 3秒 | P1 |

---

## 3. 支付监控Dashboard

### 支付渠道统计

`promql
# 各渠道支付量
sum by (channel) (aicabinet_payments_total)

# 微信支付成功率
rate(aicabinet_payments_total{channel="wechat",status="success"}[5m]) /
rate(aicabinet_payments_total{channel="wechat"}[5m])

# 支付宝支付成功率
rate(aicabinet_payments_total{channel="alipay",status="success"}[5m]) /
rate(aicabinet_payments_total{channel="alipay"}[5m])
`

### 支付趋势

`promql
# 支付金额趋势
rate(aicabinet_payment_amount_total[5m]) / 100

# 支付成功率趋势
rate(aicabinet_payments_total{status="success"}[5m]) /
rate(aicabinet_payments_total[5m])

# 支付失败原因分布
sum by (error_code) (aicabinet_payment_failures_total)
`

### 支付告警规则

| 告警名称 | 触发条件 | 级别 |
|---------|---------|------|
| 支付失败率高 | 支付失败率 > 5% | P0 |
| 支付渠道异常 | 某渠道成功率 < 90% | P1 |
| 支付耗时慢 | P95耗时 > 5秒 | P1 |

---

## 4. 设备监控Dashboard

### 设备状态概览

`promql
# 设备总数
count(aicabinet_device_status)

# 在线设备数
count(aicabinet_device_status{status="online"})

# 离线设备数
count(aicabinet_device_status{status="offline"})

# 设备在线率
count(aicabinet_device_status{status="online"}) / 
count(aicabinet_device_status) * 100
`

### 设备性能指标

`promql
# 设备响应时间
rate(aicabinet_device_response_seconds[5m])

# 设备开门成功率
rate(aicabinet_device_opens_total{status="success"}[5m]) /
rate(aicabinet_device_opens_total[5m])

# 设备识别成功率
rate(aicabinet_device_recognitions_total{status="success"}[5m]) /
rate(aicabinet_device_recognitions_total[5m])
`

### 设备告警规则

| 告警名称 | 触发条件 | 级别 |
|---------|---------|------|
| 设备离线率高 | 离线率 > 20% | P1 |
| 设备开门失败 | 开门失败率 > 5% | P1 |
| 设备识别失败 | 识别失败率 > 10% | P2 |

---

## 5. 用户监控Dashboard

### 用户增长

`promql
# 用户总数
count(aicabinet_users_total)

# 今日新增用户
sum(increase(aicabinet_users_new_total[24h]))

# 活跃用户（最近24小时）
count(aicabinet_user_active)

# 用户活跃率
count(aicabinet_user_active) / count(aicabinet_users_total) * 100
`

### 用户行为

`promql
# 用户登录次数
rate(aicabinet_user_logins_total[5m])

# 用户下单次数
rate(aicabinet_user_orders_total[5m])

# 用户平均订单金额
rate(aicabinet_order_amount_total[5m]) / 
rate(aicabinet_orders_total[5m])
`

### 会员统计

`promql
# 各等级会员数量
sum by (level) (aicabinet_members_total)

# 今日新增会员
sum(increase(aicabinet_members_new_total[24h]))

# 会员积分总额
sum(aicabinet_member_points_total)
`

---

## 6. 分账监控Dashboard

### 分账统计

`promql
# 今日分账总额
sum(increase(aicabinet_revenue_share_total[24h])) / 100

# 平台收入
sum(increase(aicabinet_platform_revenue_total[24h])) / 100

# 加盟商收入
sum(increase(aicabinet_franchise_revenue_total[24h])) / 100

# 线长收入
sum(increase(aicabinet_leader_revenue_total[24h])) / 100
`

### 提现统计

`promql
# 待处理提现
sum(aicabinet_withdraw_pending_total)

# 今日提现金额
sum(increase(aicabinet_withdraw_amount_total[24h])) / 100

# 提现成功率
rate(aicabinet_withdraw_total{status="success"}[5m]) /
rate(aicabinet_withdraw_total[5m])
`

---

## 7. 营销监控Dashboard

### 活动统计

`promql
# 进行中的活动
count(aicabinet_campaigns_total{status="active"})

# 活动参与人数
sum(aicabinet_campaign_participants_total)

# 优惠券发放量
sum(aicabinet_coupons_issued_total)

# 优惠券使用量
sum(aicabinet_coupons_used_total)

# 优惠券使用率
sum(aicabinet_coupons_used_total) / 
sum(aicabinet_coupons_issued_total) * 100
`

### 活动效果

`promql
# 活动带来的订单
sum(increase(aicabinet_campaign_orders_total[24h]))

# 活动带来的GMV
sum(increase(aicabinet_campaign_gmv_total[24h])) / 100

# 活动ROI
sum(increase(aicabinet_campaign_gmv_total[24h])) /
sum(increase(aicabinet_campaign_cost_total[24h]))
`

---

## 8. 服务健康Dashboard

### 服务状态

`promql
# 服务实例数
count(up{job="trade-service"})

# 服务健康状态
up{job="trade-service"}

# 服务启动时间
process_start_time_seconds{job="trade-service"}
`

### JVM监控

`promql
# 堆内存使用
jvm_memory_used_bytes{area="heap"}

# 堆内存使用率
jvm_memory_used_bytes{area="heap"} / 
jvm_memory_max_bytes{area="heap"} * 100

# GC停顿时间
rate(jvm_gc_pause_seconds_sum[5m])

# 线程数
jvm_threads_current
`

### 数据库监控

`promql
# 数据库连接数
hikaricp_connections_active

# 连接池使用率
hikaricp_connections_active / hikaricp_connections_max * 100

# 查询耗时P95
histogram_quantile(0.95, 
  rate(spring_data_repository_invocations_seconds_bucket[5m]))
`

### Redis监控

`promql
# Redis内存使用
redis_memory_used_bytes

# Redis内存使用率
redis_memory_used_bytes / redis_memory_max_bytes * 100

# Redis连接数
redis_connected_clients

# Redis命中率
rate(redis_keyspace_hits_total[5m]) /
(rate(redis_keyspace_hits_total[5m]) + rate(redis_keyspace_misses_total[5m]))
`

---

## Dashboard JSON导出

### 导入步骤

1. 登录Grafana: http://monitor.aicabinet.com
2. 点击 + -> Import
3. 上传JSON文件或粘贴JSON内容
4. 选择Prometheus数据源
5. 点击 Import

### 推荐Dashboard

| Dashboard名称 | ID | 说明 |
|--------------|-----|------|
| JVM Micrometer | 4701 | JVM监控 |
| Spring Boot Statistics | 12900 | Spring Boot监控 |
| PostgreSQL Database | 9628 | PostgreSQL监控 |
| Redis Dashboard | 11835 | Redis监控 |

---

## 告警通知配置

### 钉钉通知

`yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: alertmanager-config
data:
  alertmanager.yml: |
    global:
      resolve_timeout: 5m
    
    route:
      group_by: ['alertname', 'severity']
      group_wait: 30s
      group_interval: 5m
      repeat_interval: 1h
      receiver: 'dingtalk'
    
    receivers:
    - name: 'dingtalk'
      webhook_configs:
      - url: 'http://dingtalk-webhook/send'
        send_resolved: true
`

### 企业微信通知

`yaml
receivers:
- name: 'wechat'
  webhook_configs:
  - url: 'http://wechat-webhook/send'
    send_resolved: true
`

---

**文档版本**: v1.0
**更新时间**: 2026-07-14
