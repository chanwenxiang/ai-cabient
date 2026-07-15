# AI Cabinet 性能压力测试

## 📋 测试环境

### 硬件配置
- CPU: 8核
- 内存: 16GB
- 磁盘: SSD 200GB
- 网络: 千兆带宽

### 软件版本
- Java: OpenJDK 17
- PostgreSQL: 14
- Redis: 7
- JMeter: 5.6

---

## 1. JMeter测试脚本

### 订单创建测试

`xml
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="订单创建压测">
      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments">
        <collectionProp name="Arguments.arguments">
          <elementProp name="BASE_URL" elementType="Argument">
            <stringProp name="Argument.name">BASE_URL</stringProp>
            <stringProp name="Argument.value">http://localhost:8080</stringProp>
          </elementProp>
          <elementProp name="USERS" elementType="Argument">
            <stringProp name="Argument.name">USERS</stringProp>
            <stringProp name="Argument.value">1000</stringProp>
          </elementProp>
          <elementProp name="RAMP_UP" elementType="Argument">
            <stringProp name="Argument.name">RAMP_UP</stringProp>
            <stringProp name="Argument.value">60</stringProp>
          </elementProp>
          <elementProp name="DURATION" elementType="Argument">
            <stringProp name="Argument.name">DURATION</stringProp>
            <stringProp name="Argument.value">300</stringProp>
          </elementProp>
        </collectionProp>
      </elementProp>
    </TestPlan>
    <hashTree>
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="用户线程组">
        <stringProp name="ThreadGroup.num_threads"></stringProp>
        <stringProp name="ThreadGroup.ramp_time"></stringProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
        <stringProp name="ThreadGroup.duration"></stringProp>
      </ThreadGroup>
      <hashTree>
        <!-- 登录获取Token -->
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="登录">
          <stringProp name="HTTPSampler.domain"></stringProp>
          <stringProp name="HTTPSampler.path">/api/v2/auth/login</stringProp>
          <stringProp name="HTTPSampler.method">POST</stringProp>
          <boolProp name="HTTPSampler.postBodyRaw">true</boolProp>
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments">
            <collectionProp name="Arguments.arguments">
              <elementProp name="" elementType="HTTPArgument">
                <stringProp name="Argument.value">
                  {"phone":"13800138000","code":"123456"}
                </stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
        </HTTPSamplerProxy>
        <hashTree>
          <JSONPathExtractor guiclass="JSONPathExtractorGui" testclass="JSONPathExtractor">
            <stringProp name="JSONPath.referenceNames">token</stringProp>
            <stringProp name="JSONPath.jsonPathExprs">$.data.token</stringProp>
          </JSONPathExtractor>
        </hashTree>

        <!-- 创建订单 -->
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="创建订单">
          <stringProp name="HTTPSampler.domain"></stringProp>
          <stringProp name="HTTPSampler.path">/api/v2/orders</stringProp>
          <stringProp name="HTTPSampler.method">POST</stringProp>
          <elementProp name="HTTPSampler.header_manager" elementType="HeaderManager">
            <collectionProp name="HeaderManager.headers">
              <elementProp name="" elementType="Header">
                <stringProp name="Header.name">Authorization</stringProp>
                <stringProp name="Header.value">Bearer </stringProp>
              </elementProp>
              <elementProp name="" elementType="Header">
                <stringProp name="Header.name">Content-Type</stringProp>
                <stringProp name="Header.value">application/json</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
          <boolProp name="HTTPSampler.postBodyRaw">true</boolProp>
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments">
            <collectionProp name="Arguments.arguments">
              <elementProp name="" elementType="HTTPArgument">
                <stringProp name="Argument.value">
                  {
                    "deviceId":"TEST-DEVICE-001",
                    "items":[{"skuId":"SKU001","quantity":1,"priceCents":1000}]
                  }
                </stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
        </HTTPSamplerProxy>
      </hashTree>

      <!-- 监听器 -->
      <ResultCollector guiclass="SummaryReport" testclass="ResultCollector" testname="汇总报告"/>
      <ResultCollector guiclass="ViewResultsFullVisualizer" testclass="ResultCollector" testname="查看结果"/>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
`

---

## 2. 测试场景

### 场景1：订单创建压测

**目标**: 测试订单创建接口的并发能力

**参数**:
- 并发用户: 1000
- 持续时间: 5分钟
- 目标TPS: 500

**命令**:
`ash
jmeter -n -t order_test.jmx -l order_results.jtl -e -o order_report
`

---

### 场景2：订单查询压测

**目标**: 测试订单列表查询性能

**参数**:
- 并发用户: 500
- 持续时间: 3分钟
- 目标TPS: 1000

---

### 场景3：支付流程压测

**目标**: 测试完整支付流程

**参数**:
- 并发用户: 200
- 持续时间: 5分钟
- 目标TPS: 200

---

### 场景4：设备状态查询压测

**目标**: 测试设备状态查询性能

**参数**:
- 并发用户: 2000
- 持续时间: 5分钟
- 目标TPS: 2000

---

## 3. 性能基准

### 目标指标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| TPS | ≥500 | 订单创建吞吐量 |
| 响应时间P95 | ≤500ms | 95%请求响应时间 |
| 响应时间P99 | ≤1000ms | 99%请求响应时间 |
| 错误率 | ≤0.1% | 接口错误率 |
| CPU使用率 | ≤70% | 服务器CPU使用率 |
| 内存使用率 | ≤80% | 服务器内存使用率 |

---

## 4. 测试结果分析

### 性能报告示例

`
Summary Report:
+--------+---------+---------+---------+----------+---------+---------+
| Label  | # Samples | Average | Median | 90% Line | 95% Line | Error % |
+--------+---------+---------+---------+----------+---------+---------+
| 订单创建 | 15000   | 245ms   | 220ms  | 380ms    | 450ms   | 0.05%   |
| 订单查询 | 30000   | 85ms    | 80ms   | 120ms    | 150ms   | 0.01%   |
| 支付流程 | 6000    | 580ms   | 550ms  | 720ms    | 850ms   | 0.08%   |
+--------+---------+---------+---------+----------+---------+---------+
`

---

## 5. 性能优化建议

### 发现瓶颈

#### 瓶颈1：数据库连接池不足
**现象**: 高并发时响应时间变长
**解决**: 增加连接池大小

`yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
`

#### 瓶颈2：缓存命中率低
**现象**: 数据库查询压力大
**解决**: 优化缓存策略

`java
@Cacheable(value = "device_status", key = "#deviceId")
public DeviceStatus getDeviceStatus(String deviceId) {
    // 查询逻辑
}
`

#### 瓶颈3：慢查询
**现象**: 某些查询耗时超过1秒
**解决**: 添加索引

`sql
CREATE INDEX CONCURRENTLY idx_orders_user_created 
ON orders(user_id, created_at DESC);
`

---

## 6. 持续压测流程

### 自动化测试脚本

`ash
#!/bin/bash

# 性能测试脚本
# 使用方式: ./performance_test.sh <environment>

ENV=
BASE_URL="http://.aicabinet.com"

echo "开始性能测试 - 环境: "
echo "目标地址: "

# 订单创建压测
echo "执行订单创建压测..."
jmeter -n -t order_create_test.jmx \
  -JBASE_URL= \
  -JUSERS=1000 \
  -JRAMP_UP=60 \
  -JDURATION=300 \
  -l results/order_create_.jtl \
  -e -o reports/order_create_

# 生成报告
echo "生成测试报告..."
python3 generate_report.py results/order_create_.jtl

# 发送邮件
echo "发送测试报告..."
python3 send_report.py reports/order_create_/index.html

echo "性能测试完成"
`

---

## 7. 监控脚本

### Prometheus查询

`ash
# 查询当前TPS
curl 'http://prometheus:9090/api/v1/query' \
  --data-urlencode 'query=rate(http_server_requests_seconds_count[1m])'

# 查询响应时间P95
curl 'http://prometheus:9090/api/v1/query' \
  --data-urlencode 'query=histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[1m]))'

# 查询错误率
curl 'http://prometheus:9090/api/v1/query' \
  --data-urlencode 'query=rate(http_server_requests_seconds_count{status=~"5.."}[1m]) / rate(http_server_requests_seconds_count[1m])'
`

---

## 8. 容量规划

### 并发容量

| 资源 | 单实例 | 3实例 | 10实例 |
|------|--------|-------|--------|
| 订单创建TPS | 500 | 1500 | 5000 |
| 订单查询TPS | 1000 | 3000 | 10000 |
| 设备查询TPS | 2000 | 6000 | 20000 |

### 资源需求

| 并发用户 | CPU | 内存 | 数据库连接 |
|---------|-----|------|-----------|
| 1000 | 4核 | 8GB | 20 |
| 5000 | 16核 | 32GB | 50 |
| 10000 | 32核 | 64GB | 100 |

---

**文档版本**: v1.0
**更新时间**: 2026-07-14
