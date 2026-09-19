# AI Cabinet 性能压力测试

## 📋 测试环境

### 硬件配置（本机 dev 宿主，2026-09-19 实测）

> 🔴 原表写「CPU 8核 / 内存 16GB」与本机实况不符，已按实测替换。
> 关键限制不是宿主，而是 **Docker VM 只分到 3.82 GB** —— 见 §8.2。

- 宿主 CPU: **20 逻辑核**
- 宿主内存: **31.6 GB**（压测时可用 15.5 GB）
- 容器运行时: Docker Desktop / WSL2，**VM 内存上限 3.82 GB**（`C:\Users\cwx\.wslconfig` 的 `memory=4GB`、`swap=2GB`）
- 磁盘: SSD

### 软件版本
- Java: OpenJDK 17
- PostgreSQL: 14
- Redis: 7
- JMeter: 5.6.3

---

## 1. JMeter测试脚本

> **可跑真源（2026-09-13）**：仓库已落盘 `scripts/perf/order_read_scale.jmx`（订单/账户/柜状态**读压测**，1000 VU）。本机跑：`scripts/perf/run-order-read-scale.cmd`（默认使用桌面 `apache-jmeter-5.6.3`）。JWT 须经 `__FileToString` 读入，勿用 `-JTOKEN`（cmd 会截断）。下文内嵌 XML 为历史草稿，ThreadGroup 变量未填全，**不要直接当成品用**。

> **O8 新增真源（2026-09-19）**：
> `scripts/perf/poll_scale.jmx`（轮询读链路）+ `scripts/perf/run-o8-staged.sh`（**分级升压**，逐级健康检查、到拐点即停）；
> `scripts/perf/open_settle_cycle.jmx`（开门+结算闭环）+ `scripts/perf/run-o8-three-link.sh`。
> 🔴 **不要再直接盲打 1000 VU** —— 本轮实测会把本机 Docker 引擎打崩（`run-o8-three-link.sh` 里保留了 1000 VU，
> 属**复现故障**用，不是常规基线）。基线请用 `run-o8-staged.sh`。实测数据与根因见
> `docs/uat-screenshots/2026-09-19/o8-three-link/README.md`。

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

> ⚠️ **本节是 2026-07 拟定的场景草稿，其中的「目标 TPS」均为未经验证的设想值，且与实测冲突**：
> 建会话/开门受小时级风控（60 次/时/设备），「订单创建 TPS ≥500」不成立。**以 §3.2 与 §8 的实测为准。**
> 本节保留仅用于说明压测思路（场景划分、参数形态）。

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

### 3.1 判据阈值（门禁口径）

`scripts/perf/summarize-jtl.mjs` 按此判定 `pass`，压测脚本一律沿用：

| 指标 | 阈值 | 说明 |
|------|--------|------|
| 响应时间 P95 | ≤800ms | 95% 请求响应时间 |
| 错误率 | ≤0.1% | 接口错误率 |

> ⚠️ **原表里的「TPS ≥500（订单创建吞吐量）」已删除**：据 2026-09-19 实测，
> 开门（建会话）受**小时级风控**约束（20 次/时/用户、60 次/时/设备），
> 写路径的 TPS 由**策略**而非服务器决定，作为性能目标值不成立。详见 §8。

### 3.2 实测基线（2026-09-19，本机 dev 栈）

证据目录：`docs/uat-screenshots/2026-09-19/o8-three-link/`（含复现步骤与环境取证）。
**读链路**（3 端点：`sessions/active` / `devices/{id}/status` / `orders`，每级 60s）：

| 并发 VU | 样本 | 错误率 | p50 | **p95** | p99 | 吞吐 |
|---|---|---|---|---|---|---|
| 100 | 118,623 | 0.00% | 35ms | **119ms** | 164ms | ≈1,977/s |
| 200 | 146,340 | 0.00% | 49ms | **221ms** | 325ms | ≈2,439/s |
| 400 | 148,573 | 0.00% | 126ms | **305ms** | 400ms | ≈2,466/s |
| **600** | **150,666** | **0.00%** | 204ms | **379ms** | 476ms | **≈2,470/s** |

**写链路**（开门+结算闭环，串行 10 轮）：20 采样、**0 错误**；
`POST /api/v2/sessions` p50 **34ms**、`POST .../demo-close` p50 **45ms**。

🔴 **两条必须一起读的结论**：

1. **吞吐在 200 VU 即封顶 ≈2,470 TPS** —— 此后加 VU 只推高尾延迟，已进入饱和区；
2. **1000 VU 不可作为容量结论**：该轮 70.16% 错误且**压垮了宿主 Docker 引擎**，
   根因是**宿主/容器配额**（Docker VM 仅 3.82GB、`trade-service` `cpus: 1.5`、
   Tomcat 走默认 `maxThreads=200`/`acceptCount=100`），非应用逻辑。详见证据目录 README §4。


---

## 4. 测试结果分析

> ⚠️ **下面的「性能报告示例」是格式示意，数字是编的**（2026-07 草稿），**不是任何一次实测结果**。
> 真实结果见 §3.2 与 `docs/uat-screenshots/2026-09-19/o8-three-link/`。

### 性能报告示例（仅格式示意）

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

> ⚠️ **本节三条「瓶颈」是 2026-07 的推测，未与实测对齐**（实测未出现连接池/缓存/慢查询症状：
> 400–600 VU 下错误率 0%、`trade-service` RSS 稳定 ~850MB）。
> **实测暴露的瓶颈见 §8.4**：Tomcat 未配置、Docker VM 内存上限、`device-service` 扇出、容器配额。

### 发现瓶颈（2026-07 推测，未验证）

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

> ⚠️ **下面这段是 2026-07 的**模板**，引用的 `order_create_test.jmx` 在仓库里并不存在**，
> 且 `-JUSERS=1000` 直接盲打 —— 2026-09-19 实测会把本机 Docker 引擎打崩（见 §8.2）。
> **实际可跑的流程见 §8.1 与 `docs/uat-screenshots/2026-09-19/o8-three-link/README.md` §8**：
> 先用 `scripts/perf/run-o8-staged.sh` 分级升压找拐点，不要固定 1000 VU。

### 自动化测试脚本（历史模板，不可直接运行）

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

> 🔴 **本节于 2026-09-19 按 O8 实测重写。** 原表（订单创建 500/1500/5000、订单查询
> 1000/3000/10000、设备查询 2000/6000/20000）是**没有任何实测支撑的估算**，已删除；
> 下面只保留**有取证**的数字，并显式标出**不可外推**的部分。

### 8.1 实测：单实例（本机 dev 栈）

证据：`docs/uat-screenshots/2026-09-19/o8-three-link/`。

| 链路 | 稳定区间（实测通过） | 吞吐 | 该点 p95 | 备注 |
|---|---|---|---|---|
| 读：`sessions/active`、`orders` | **≥600 VU** | — | 266 / 277ms | — |
| 读：`devices/{id}/status` | **≥600 VU** | 445ms | **最重**，跨服务扇出到 `device-service` |
| 读：三端点合计 | **≥600 VU** | **≈2,470/s** | 379ms | 200 VU 起吞吐即封顶 |
| 写：开门 + 结算闭环 | 串行，10 轮全绿 | — | 88ms | 容量由风控决定，见 8.3 |

拐点落在 **(600, 1000] VU** 之间（未二分，原因见证据目录 README §7）。

### 8.2 为什么不能线性外推（本机硬约束，全部取证）

| 约束 | 实测值 | 影响 |
|---|---|---|
| Docker VM 内存 | **3.82 GB**（`.wslconfig: memory=4GB`；宿主 31.6GB） | 业务栈 idle 已占 2.46GB；1000 VU 时引擎自身先崩 |
| `trade-service` 容器 | **`mem_limit: 1024m`、`cpus: 1.5`** | 压测期 CPU 打满；RSS 稳在 ~850MB（85%） |
| Tomcat | **完全未配置**（默认 `maxThreads=200`、`acceptCount=100`） | 并发连接超接受队列即被内核 RST |
| `device-service` | `cpus: 1.0` | 扇出链路最先劣化 |

⇒ 上表是**单机开发宿主**的数字，**不可线性外推到生产集群**。
**多实例容量（`infra/docker-compose.ha.yml` 的 `--scale trade-service=2`）本轮未测**，无数据。

### 8.3 写路径的容量由**风控策略**决定，不由服务器决定

| 动作 | 上限 | 窗口 |
|---|---|---|
| 建会话（开门） | **20 / 用户** | 1 小时 |
| 开门 | **30 / 用户** | 1 小时 |
| 开门 | **60 / 设备** | 1 小时 |

叠加「**同一用户同时只允许一个活跃会话**」⇒ 单柜每小时最多 **60 次开门**（0.017 次/秒），
且**无法并发**。任何「订单创建 TPS」形式的容量指标在本设计下都不成立。

### 8.4 扩容前必做

1. 给 `trade-service` 显式配置 Tomcat 线程/积压（当前全默认），并与 `cpus` 配额匹配；
2. 压测/联调时把 `.wslconfig` 的 `memory` 提到 ≥12GB，并停掉 `infra-sonarqube`（`mem_limit: 3g`）
   等 devops 容器 —— 它们与业务栈抢同一块内存；
3. `device status` 扇出链路单独核算（`device-service` 仅 1 核）；
4. 容器 `mem_limit` 与 JVM 堆成对设计（现 `1024m` 下 JVM 自动取 ~256MB 堆）。


---

**文档版本**: v1.1
**更新时间**: 2026-09-19（O8 实测改写：§3/§4/§8 去估算、§2 标注、测试环境按实测修正）
**原始版本**: v1.0 / 2026-07-14
