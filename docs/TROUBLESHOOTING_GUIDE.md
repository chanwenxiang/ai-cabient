# AI Cabinet 故障处理手册

## 📋 目录

1. [故障诊断流程](#故障诊断流程)
2. [常见故障处理](#常见故障处理)
3. [应急响应预案](#应急响应预案)
4. [数据恢复方案](#数据恢复方案)
5. [联系方式](#联系方式)

---

## 故障诊断流程

### 1. 故障分类

| 级别 | 定义 | 响应时间 | 处理时间 |
|------|------|---------|---------|
| P0 | 核心服务不可用 | 5分钟 | 30分钟 |
| P1 | 部分功能异常 | 15分钟 | 2小时 |
| P2 | 性能下降 | 30分钟 | 4小时 |
| P3 | 小问题 | 2小时 | 1天 |

### 2. 诊断步骤

`
1. 确认故障现象
   ↓
2. 检查监控系统
   ↓
3. 查看应用日志
   ↓
4. 检查基础设施
   ↓
5. 定位问题根因
   ↓
6. 实施修复方案
   ↓
7. 验证修复效果
   ↓
8. 编写故障报告
`

### 3. 常用诊断命令

`ash
# 检查服务状态
systemctl status trade-service
docker ps | grep trade-service
kubectl get pods -n ai-cabinet

# 查看资源使用
top
free -h
df -h
netstat -tunlp

# 查看应用日志
tail -f /var/log/trade-service.log
docker logs -f trade-service --tail 100
kubectl logs -f trade-service-xxx -n ai-cabinet

# 检查数据库连接
psql -h localhost -U postgres -c "SELECT count(*) FROM pg_stat_activity;"

# 检查Redis连接
redis-cli -h localhost ping

# 检查网络连接
curl -v http://localhost:8080/actuator/health
telnet localhost 5432
`

---

## 常见故障处理

### 1. 服务无法启动

#### 现象
`
Application failed to start
`

#### 排查步骤

**步骤1**: 检查端口占用
`ash
netstat -tunlp | grep 8080
lsof -i :8080
`

**步骤2**: 检查配置文件
`ash
# 检查application.yml语法
cat application.yml

# 检查环境变量
env | grep SPRING
`

**步骤3**: 检查依赖服务
`ash
# 数据库
psql -h localhost -U postgres

# Redis
redis-cli -h localhost ping

# Kafka
kafka-topics.sh --list --bootstrap-server localhost:9092
`

**解决方案**:
`ash
# 杀掉占用端口的进程
kill -9 <PID>

# 修复配置文件
vim application.yml

# 启动依赖服务
docker-compose up -d postgres redis
`

---

### 2. 数据库连接池耗尽

#### 现象
`
HikariPool - Connection is not available
`

#### 排查步骤

**步骤1**: 查看连接池状态
`sql
SELECT count(*), state FROM pg_stat_activity GROUP BY state;
`

**步骤2**: 查看长时间查询
`sql
SELECT pid, now() - pg_stat_activity.query_start AS duration, query, state
FROM pg_stat_activity
WHERE (now() - pg_stat_activity.query_start) > interval '5 minutes';
`

**步骤3**: 查看锁等待
`sql
SELECT blocked_locks.pid AS blocked_pid,
       blocked_activity.usename AS blocked_user,
       blocking_locks.pid AS blocking_pid,
       blocking_activity.usename AS blocking_user,
       blocked_activity.query AS blocked_statement
FROM pg_catalog.pg_locks blocked_locks
JOIN pg_catalog.pg_stat_activity blocked_activity ON blocked_activity.pid = blocked_locks.pid
JOIN pg_catalog.pg_locks blocking_locks ON blocking_locks.locktype = blocked_locks.locktype
    AND blocking_locks.database = blocked_locks.database
    AND blocking_locks.relation = blocked_locks.relation
    AND blocking_locks.granted
WHERE NOT blocked_locks.granted;
`

**解决方案**:
`sql
-- 杀掉长时间运行的查询
SELECT pg_terminate_backend(pid) 
FROM pg_stat_activity 
WHERE (now() - query_start) > interval '10 minutes';

-- 增加连接池大小
ALTER SYSTEM SET max_connections = 200;
`

---

### 3. 内存溢出（OOM）

#### 现象
`
java.lang.OutOfMemoryError: Java heap space
`

#### 排查步骤

**步骤1**: 检查内存使用
`ash
free -h
jstat -gc <pid> 1000 10
`

**步骤2**: 生成堆转储
`ash
jmap -dump:format=b,file=heap.hprof <pid>
`

**步骤3**: 分析堆转储
`ash
# 使用MAT或VisualVM分析
# 查看大对象和内存泄漏
`

**解决方案**:
`ash
# 增加堆内存
java -Xms4g -Xmx4g -jar trade-service.jar

# 启用GC日志
-XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:gc.log
`

---

### 4. Redis连接失败

#### 现象
`
Unable to connect to Redis
`

#### 排查步骤

**步骤1**: 检查Redis状态
`ash
redis-cli ping
redis-cli info
`

**步骤2**: 检查连接数
`ash
redis-cli info clients
redis-cli info stats | grep connected
`

**步骤3**: 检查内存
`ash
redis-cli info memory
`

**解决方案**:
`ash
# 重启Redis
docker restart redis

# 增加最大连接数
redis-cli CONFIG SET maxclients 10000

# 清理内存
redis-cli FLUSHDB  # 谨慎使用
`

---

### 5. 支付回调失败

#### 现象
`
支付成功但订单状态未更新
`

#### 排查步骤

**步骤1**: 查看支付日志
`ash
grep "payment_callback" /var/log/trade-service.log
`

**步骤2**: 查询支付记录
`sql
SELECT * FROM payments WHERE order_id = 'xxx';
SELECT * FROM orders WHERE id = 'xxx';
`

**步骤3**: 查看幂等记录
`sql
SELECT * FROM idempotency_keys WHERE idempotency_key = 'xxx';
`

**解决方案**:
`sql
-- 手动补单
UPDATE orders SET status = 'PAID' WHERE id = 'xxx';
INSERT INTO payments (order_id, status, ...) VALUES ('xxx', 'SUCCESS', ...);

-- 重新触发分账
INSERT INTO revenue_share_tasks (order_id, status) VALUES ('xxx', 'PENDING');
`

---

### 6. 设备离线

#### 现象
`
设备状态显示离线，无法开门
`

#### 排查步骤

**步骤1**: 检查MQTT连接
`ash
# 查看EMQX连接
docker exec emqx emqx_ctl clients show device_001

# 查看订阅
docker exec emqx emqx_ctl subscriptions list
`

**步骤2**: 检查设备状态
`sql
SELECT * FROM devices WHERE device_id = 'xxx';
SELECT * FROM device_sessions WHERE device_id = 'xxx';
`

**步骤3**: 检查网络
`ash
# 测试设备连接
mosquitto_pub -h emqx-host -t "device/xxx/command" -m "ping"
`

**解决方案**:
`ash
# 重启设备服务
docker restart device-service

# 清理会话
docker exec emqx emqx_ctl clients kick device_001

# 重新注册设备
curl -X POST http://localhost:8080/api/v2/devices/xxx/reconnect
`

---

### 7. 高延迟/慢查询

#### 现象
`
API响应时间超过5秒
`

#### 排查步骤

**步骤1**: 查看慢查询日志
`sql
SELECT query, calls, total_time, mean_time
FROM pg_stat_statements
ORDER BY mean_time DESC
LIMIT 10;
`

**步骤2**: 分析执行计划
`sql
EXPLAIN ANALYZE SELECT * FROM orders WHERE user_id = 123;
`

**步骤3**: 检查索引
`sql
SELECT schemaname, tablename, indexname, idx_scan
FROM pg_stat_user_indexes
ORDER BY idx_scan;
`

**解决方案**:
`sql
-- 创建索引
CREATE INDEX CONCURRENTLY idx_orders_user_id ON orders(user_id);

-- 优化查询
-- 避免SELECT *
-- 使用覆盖索引
-- 添加查询条件
`

---

### 8. 消息队列堆积

#### 现象
`
Kafka消息堆积，处理延迟
`

#### 排查步骤

**步骤1**: 查看消费组状态
`ash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group ai-cabinet-group
`

**步骤2**: 查看Topic信息
`ash
kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic orders
`

**步骤3**: 查看消费者日志
`ash
grep "kafka_consumer" /var/log/trade-service.log
`

**解决方案**:
`ash
# 增加消费者实例
kubectl scale deployment trade-service --replicas=5

# 重置消费位置（谨慎使用）
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ai-cabinet-group --reset-offsets --to-latest --execute

# 增加分区
kafka-topics.sh --bootstrap-server localhost:9092 \
  --alter --topic orders --partitions 10
`

---

## 应急响应预案

### 1. 服务全量不可用（P0）

**现象**: 所有用户无法访问服务

**响应流程**:
`
1. 确认影响范围（5分钟内）
   - 检查监控大盘
   - 确认故障时间
   - 统计影响用户数

2. 快速恢复（15分钟内）
   - 重启服务
   - 回滚最近发布
   - 切换备用服务

3. 通报（10分钟内）
   - 通知相关人员
   - 建立应急群
   - 同步故障进展

4. 根因分析（事后）
   - 分析日志和监控
   - 定位问题代码
   - 制定改进措施
`

### 2. 数据丢失（P0）

**现象**: 重要数据丢失或损坏

**响应流程**:
`
1. 立即停止写入
2. 评估数据丢失范围
3. 从备份恢复
4. 验证数据完整性
5. 补录缺失数据
6. 编写事故报告
`

### 3. 支付异常（P1）

**现象**: 支付功能异常

**响应流程**:
`
1. 检查支付渠道状态
2. 查看支付日志
3. 核对账单
4. 手动处理异常订单
5. 通知财务部门
`

---

## 数据恢复方案

### 1. 数据库恢复

#### 全量恢复
`ash
# 停止服务
systemctl stop trade-service

# 恢复数据库
pg_restore -h localhost -U postgres -d ai_cabinet backup.dump

# 启动服务
systemctl start trade-service
`

#### 时间点恢复（PITR）
`ash
# 恢复到指定时间点
pg_restore -h localhost -U postgres -d ai_cabinet \
  --time="2026-07-14 10:00:00" backup.dump
`

### 2. Redis数据恢复

`ash
# 检查RDB文件
ls -lh /var/lib/redis/dump.rdb

# 恢复数据
cp /backup/redis/dump.rdb /var/lib/redis/
chown redis:redis /var/lib/redis/dump.rdb
systemctl restart redis
`

### 3. 文件恢复（MinIO）

`ash
# 使用mc恢复
mc mirror /backup/minio local/

# 恢复单个文件
mc cp /backup/product-images/image1.jpg local/product-images/
`

---

## 联系方式

### 技术团队

| 角色 | 姓名 | 电话 | 微信 |
|------|------|------|------|
| 技术负责人 | 张三 | 138-xxxx-xxxx | zhangsan |
| 后端负责人 | 李四 | 139-xxxx-xxxx | lisi |
| 前端负责人 | 王五 | 137-xxxx-xxxx | wangwu |
| 运维负责人 | 赵六 | 136-xxxx-xxxx | zhaoliu |

### 服务商

| 服务 | 紧急电话 | 工单系统 |
|------|---------|---------|
| 阿里云 | 95187 | workorder.console.aliyun.com |
| 腾讯云 | 95716 | console.cloud.tencent.com/workorder |
| 华为云 | 4000-955-988 | support.huaweicloud.com/tickets |

### 应急响应时间

- **P0故障**: 5分钟内响应，30分钟内恢复
- **P1故障**: 15分钟内响应，2小时内恢复
- **P2故障**: 30分钟内响应，4小时内恢复

---

## 故障报告模板

`markdown
# 故障报告

## 基本信息
- 故障时间：2026-07-14 10:00 - 10:30
- 故障级别：P0
- 影响范围：所有用户无法下单
- 处理人员：张三、李四

## 故障描述
2026-07-14 10:00开始，用户反馈无法下单，接口返回500错误。

## 根因分析
数据库连接池耗尽，导致无法获取数据库连接。

## 处理过程
1. 10:05 确认故障现象
2. 10:10 查看应用日志，发现连接池耗尽
3. 10:15 杀掉长时间运行的查询
4. 10:20 重启服务
5. 10:30 服务恢复正常

## 改进措施
1. 增加数据库连接池监控
2. 优化慢查询
3. 添加连接池告警

## 总结
本次故障持续30分钟，影响所有用户下单操作。通过杀掉慢查询和重启服务恢复。

---
报告人：张三
日期：2026-07-14
`

---

**文档版本**: v1.0
**更新时间**: 2026-07-14
