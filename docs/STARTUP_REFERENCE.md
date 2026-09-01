# 本地启动速查表

> 默认 **dev** 环境，验证码固定 `123456`。完整说明见 [LOCAL_SETUP.md](LOCAL_SETUP.md)。
>
> **先核对本机端口再启动**：不要盲跑 `docker-up.ps1` 与 IDEA 混用。全栈 Docker（trade `:18080`）与 IDEA 本地（trade `:8080`）二选一。

---

## 一、启动顺序（推荐：IDEA 本地 + Docker 仅基础设施）

| 顺序 | 组件 | 如何启动 |
|------|------|----------|
| 1 | Docker **基础设施** | 仓库根目录：`docker compose -p ai-cabinet -f infra/docker-compose.yml up -d`（postgres / redis / emqx / minio…）。**不要**默认 `.\docker-up.ps1`（那是 full 栈，会起 Docker 版 trade `:18080`） |
| 2 | vision-service | `cd vision-service && .\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8082`（**必启**：trade 的 `/actuator/health` 含 vision 探针，vision 挂则 trade=DOWN） |
| 3 | trade-service | IDEA Run `trade-service`（:8080）；需能连 **Redis `:6379`** 与 Postgres `:15433` |
| 4 | device-service | IDEA Run `device-service`（:8081）；MQTT 默认 `tcp://localhost:11883` |
| 5 | 设备模拟器 | IDEA Run `DeviceSimulator`，参数 `CAB-001` |
| 6 | 消费者小程序 | 微信：`pnpm --filter @aicabinet/consumer-mp dev:mp-weixin`；**浏览器 H5**：`pnpm --filter @aicabinet/consumer-mp dev:h5` → http://127.0.0.1:3002 |
| 7 | 商户小程序 | 微信：`pnpm --filter @aicabinet/merchant-mp dev:mp-weixin`；**浏览器 H5**：`pnpm --filter @aicabinet/merchant-mp dev:h5` → http://127.0.0.1:3001 |
| 8 | 运营控制台 | 有 Gateway 时 http://localhost/admin/index.html；仅 IDEA trade 时用 http://localhost:8080/admin/index.html |

可选全栈：`.\docker-up.ps1`（`infra/docker-compose.full.yml`）→ Admin/API 走 Gateway / `:18080`，此时不要再起 IDEA trade。

---

## 二、服务与端口

### 必启（完整购物流程 · IDEA 模式）

| 服务 | 端口 | 健康检查 / 入口 |
|------|------|-----------------|
| PostgreSQL | **15433** | Docker 内 5432，宿主机 15433 |
| **Redis** | **6379** | trade/device 默认 `REDIS_PORT=6379`；**会进 health**，不是「未使用」 |
| **EMQX MQTT** | 11883 | 设备通信（device-service） |
| **MinIO API** | 9000（Windows 常需 **19000**） | 视频存储；若 `bind: access permissions` 见下方 Windows 说明 |
| **trade-service** | **8080**（全栈 Docker 常为 **18080**） | http://localhost:8080/actuator/health 须为 **UP**（依赖 Redis + vision） |
| **device-service** | **8081**（Docker 常为 **18081**） | http://localhost:8081/actuator/health |
| **vision-service** | **8082**（Docker 常为 **18082**） | http://localhost:8082/health |
| **设备模拟器** | — | 程序参数 `CAB-001`，每 30s 心跳 |

### 可选

| 服务 | 端口 | 说明 |
|------|------|------|
| Redpanda/Kafka | 9092 | 异步识别用，默认关闭 |
| Nginx Gateway | 80 | http://localhost/admin/index.html（full 栈或单独起 gateway） |
| EMQX 控制台 | 28083 | http://localhost:28083 |
| MinIO 控制台 | 9001（Windows 常需 **19001**） | http://localhost:9001 或 http://localhost:19001 |

### 已知坑（2026-09 实测）

| 现象 | 原因 | 处理 |
|------|------|------|
| trade health=`DOWN`，但 `/v3/api-docs` 仍 200 | Redis 连不上，和/或 vision `:8082` 未起（`VisionServiceHealthIndicator`） | 先确认 `127.0.0.1:6379` 与 `:8082/health`，再看 trade health |
| Redis 在 **16379** 而应用默认 6379 | 旧容器端口与仓库 `docker-compose*.yml`（`6379:6379`）不一致 | 按仓库 compose **重建 redis**，或 IDEA 环境变量 `REDIS_PORT=16379`（勿长期分叉） |
| 盲跑 `docker-up.ps1` + IDEA trade | 双 trade / 端口与心智模型混乱 | 选一种模式 |

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
| 运营 UAT | `cd clients/consumer-mp && node ../admin-vue/tests/admin-uat.mjs`（`13900000001` / `123456` + Redis 图形验证码） |
| 三端业务 UI UAT | `cd clients/consumer-mp && node ../admin-vue/tests/three-end-business-uat.mjs`（消费者订单视频 → 商户订单视频 → 运营争议/异常/订单） |
| 争议结案 UI UAT | 先 `.\scripts\create-open-dispute.ps1`，再 `node ../admin-vue/tests/three-end-dispute-ui-uat.mjs`（**运营后台**含图形验证码；商户端无验证码） |
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

## 四（补2）、演示购物视频 & OpenAPI 同步

### MinIO 演示录像（容器重建后）

订单购物视频依赖 `shopping_session.video_uri`（MinIO 对象）。MinIO 数据卷清空后需重新上传：

```powershell
.\scripts\seed-demo-shopping-video.ps1
# 指定会话：.\scripts\seed-demo-shopping-video.ps1 -SessionId 1788233611382431271
```

| 项 | 值 |
|----|-----|
| 演示会话 | `1788233611382431271` |
| 演示订单 | `1788233752744411094` |
| 柜机 | `CAB-001` |
| MinIO 对象 | `cabinet-videos/demo/sample-shopping.mp4` |

消费者 / 商户 H5 订单详情 →「查看购物视频」；API：`GET /api/v2/orders/{id}/video`、`GET /api/v2/merchant/orders/{id}/video`。

### Apifox OpenAPI 同步

从 trade-service 导出并可选导入 Apifox（项目默认 `8780097`）：

```powershell
.\scripts\sync-apifox-oas.ps1
# 带令牌自动导入：
$env:APIFOX_ACCESS_TOKEN = '<系统级访问令牌>'
$env:APIFOX_PROJECT_ID = '8780097'   # 可选
.\scripts\sync-apifox-oas.ps1
```

- 导出文件：`.tmp/live-openapi.json`（须用 **trade** 端口 `:18080` 或 `:8080`，网关 `/v3/api-docs` 常无 paths）
- 无令牌时仅导出，可在 Apifox 手动 Import → OpenAPI
- **Mock 冒烟场景（对齐 Apifox S01–S10）**：`.\scripts\apifox-smoke-scenario.ps1`；可选 `-WithOpenDoor` 试开门并取消。手工建场景见 [APIFOX_MOCK_SCENARIO.md](APIFOX_MOCK_SCENARIO.md)
- **三端联调（含争议 KEEP/WAIVE/CONFIRM）**：`.\scripts\e2e-three-end.ps1`（加 `-IncludeHappyPath` 含 happy-path）

---

## 五、相关文档

| 文档 | 内容 |
|------|------|
| [MODULES.md](MODULES.md) | 模块索引 |
| [LOCAL_SETUP.md](LOCAL_SETUP.md) | 完整本地联调步骤 |
| [APIFOX_MOCK_SCENARIO.md](APIFOX_MOCK_SCENARIO.md) | Apifox Mock 场景步骤（S01–S10） |
| [PRODUCTION.md](PRODUCTION.md) | 上生产环境变量与安全清单 |
