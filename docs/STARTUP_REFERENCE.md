# 本地启动速查表

> 默认 **dev** 环境，验证码固定 `123456`。完整说明见 [LOCAL_SETUP.md](LOCAL_SETUP.md)。

---

## 一、启动顺序

| 顺序 | 组件 | 如何启动 |
|------|------|----------|
| 1 | Docker 全栈 | 仓库根目录 `.\docker-up.ps1`（**不要** `cd infra && docker compose up`） |
| 2 | vision-service | `cd vision-service && uvicorn app.main:app --reload --port 8082` |
| 3 | trade-service | IDEA Run `TradeServiceApplication`（:8080） |
| 4 | device-service | IDEA Run `DeviceServiceApplication`（:8081） |
| 5 | 设备模拟器 | IDEA Run `DeviceSimulator`，参数 `CAB-001` |
| 6 | 消费者小程序 | 微信：`pnpm --filter @aicabinet/consumer-mp dev:mp-weixin`；**浏览器 H5**：`pnpm --filter @aicabinet/consumer-mp dev:h5` → http://127.0.0.1:3002 |
| 7 | 商户小程序 | 微信：`pnpm --filter @aicabinet/merchant-mp dev:mp-weixin`；**浏览器 H5**：`pnpm --filter @aicabinet/merchant-mp dev:h5` → http://127.0.0.1:3001 |
| 8 | 运营控制台 | 浏览器 http://localhost/admin/index.html（Gateway）或 http://localhost:8080/admin/index.html |

---

## 二、服务与端口

### 必启（完整购物流程）

| 服务 | 端口 | 健康检查 / 入口 |
|------|------|-----------------|
| PostgreSQL | **15433** | Docker 内 5432，宿主机 15433 |
| **EMQX MQTT** | 11883 | 设备通信 |
| **MinIO API** | 9000（Windows 常需 **19000**） | 视频存储；若 `bind: access permissions` 见下方 Windows 说明 |
| **trade-service** | **8080**（全栈 Docker 常为 **18080**） | http://localhost:8080/actuator/health 或 :18080 |
| **device-service** | **8081**（Docker 常为 **18081**） | http://localhost:8081/actuator/health |
| **vision-service** | **8082**（Docker 常为 **18082**） | http://localhost:8082/health |
| **设备模拟器** | — | 程序参数 `CAB-001`，每 30s 心跳 |

### 可选

| 服务 | 端口 | 说明 |
|------|------|------|
| Redis | 6379 | 预留，当前未用 |
| Redpanda/Kafka | 9092 | 异步识别用，默认关闭 |
| Nginx Gateway | 80 | http://localhost/admin/index.html |
| EMQX 控制台 | 28083 | http://localhost:28083 |
| MinIO 控制台 | 9001（Windows 常需 **19001**） | http://localhost:9001 或 http://localhost:19001 |

### Windows MinIO 端口（Hyper-V 预留）

若宿主机排除段包含 9000（如 `8905–9004`），`ai-cabinet-minio-1` 会报错
`bind: An attempt was made to access a socket in a way forbidden by its access permissions`。
注意：`netstat` **可能看不到占用**——这是系统预留段，不是进程监听。用
`netsh interface ipv4 show excludedportrange protocol=tcp` 确认。

解决：

```powershell
# 仓库根目录（推荐）
.\docker-up.ps1
# Windows MinIO 端口冲突时：
docker compose --env-file infra\.env -f infra\docker-compose.full.yml -f infra\docker-compose.win-ports.yml up -d
```

| 项 | URL |
|----|-----|
| MinIO API | http://localhost:19000 |
| MinIO 控制台 | http://localhost:19001 （minioadmin / minioadmin） |

### 前端入口

| 入口 | URL |
|------|-----|
| 运营控制台 | http://localhost/admin/index.html |
| 消费者小程序 H5 | http://127.0.0.1:3002 （`dev:h5`，账号 `13800138000` / 验证码 `123456`） |
| 商户小程序 H5 | http://127.0.0.1:3001 （`dev:h5`，账号 `13800138001` / 验证码 `123456`） |
| 小程序 API | H5 经 Vite 同源代理到 Gateway；微信端用 `VITE_API_BASE_URL` |
| API Gateway | http://localhost/api/v2/ |

---

## 三、账号与密码

### 业务测试账号

| 角色 | 手机号 | 验证码 | userId | 说明 |
|------|--------|--------|--------|------|
| **消费者** | `13800138000` | `123456` | 10001 | 余额 100 元，开门购物 |
| **超级管理员** | `13900000001` | `123456` | 100000001 | 运营控制台全权限 |
| **财务** | `13900000002` | `123456` | 100000007 | 财务毛利 / 对账 / 商户分账 |
| **运营人员** | `13900000003` | `123456` | 100000008 | 设备 / 订单 / 争议 / 异常 |
| **补货员** | `13900000004` | `123456` | 100000009 | 补货调度 / 仓库 |
| **只读** | `13900000005` | `123456` | 100000010 | 列表只读 |
| **商户管理员** | `13800138001` | `123456` | 100000002 | 商户小程序 |

- 登录接口：`POST /api/v2/auth/login`

### 设备

| 项 | 值 |
|----|-----|
| 设备 ID | `CAB-001` |
| 模拟器启动参数 | `CAB-001` |

### 商品

| SKU | 名称 | 价格 |
|-----|------|------|
| `SKU-DEMO-001` | 演示可乐 | 3.5 元 |

---

### 基础设施账号（Docker）

| 服务 | 地址 | 用户名 | 密码 |
|------|------|--------|------|
| **PostgreSQL** | localhost:**15433** / 库 `aicabinet` | `aicabinet` | `aicabinet` |
| **MinIO 控制台** | http://localhost:9001 | `minioadmin` | `minioadmin` |
| **EMQX 控制台** | http://localhost:28083 | `admin` | `public` |

---

## 四、最小联调检查

```text
✓ docker compose ps          → postgres / emqx / minio Running
✓ :8080/actuator/health      → trade-service UP
✓ :8081/actuator/health      → device-service UP
✓ :8082/health               → vision-service UP
✓ DeviceSimulator CAB-001    → 控制台有心跳日志
✓ uni-app 13800138000/123456 → 开门 CAB-001
✓ 运营控制台 13900000001     → 登录
```

一键脚本：`.\scripts\verify-local.ps1`

---

## 四（补）、Gateway 502 / 间歇不可用（ENV-02）

全栈 Docker 下入口为 `http://localhost`（Nginx gateway）。若出现 **502 Bad Gateway**：

1. 确认 `trade-service` / `device-service` 容器 healthy：`docker compose -f infra/docker-compose.full.yml ps`
2. 查看 gateway 上游：`docker compose -f infra/docker-compose.full.yml logs gateway --tail 80`
3. 常见原因：trade 刚重启、Flyway 迁移中、或上游端口未就绪 — 等待 30～60 秒后重试
4. 仍失败时重启 gateway：`docker compose -f infra/docker-compose.full.yml restart gateway`
5. 直连排查：`http://localhost:18080/actuator/health`（绕过 Nginx）

---

## 五、相关文档

| 文档 | 内容 |
|------|------|
| [MODULES.md](MODULES.md) | 模块索引 |
| [LOCAL_SETUP.md](LOCAL_SETUP.md) | 完整本地联调步骤 |
| [PRODUCTION.md](PRODUCTION.md) | 上生产环境变量与安全清单 |
