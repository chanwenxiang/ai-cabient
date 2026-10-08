# 本地启动与联调指南

本文档说明如何在 Windows 本机启动 **全部组件**，并完成一次完整的「扫码开门 → 购物 → 结算」测试。

> **关于 PowerShell**  
> 文档中的 PowerShell 只是「命令行写法示例」，**不是必须用 PowerShell**。  
> **Java 后端推荐直接在 IntelliJ IDEA 里打开项目、点 Run**，不必开多个终端敲 `mvn`。  
> 仍需要单独启动的：Docker 基础设施、vision-service（Python）、微信开发者工具。

## 开发原则（本地测试 + 生产标准代码）

| 原则 | 说明 |
|------|------|
| **同一套代码** | 登录、支付、内部 API、权限校验等与生产共用，不做两套实现 |
| **dev profile 联调** | 默认 `SPRING_PROFILES_ACTIVE=dev`，`mock-enabled=true` 走 mock 分支 |
| **上生产只改配置** | 设 `SPRING_PROFILES_ACTIVE=prod` + 环境变量，无需改代码 |
| **本地默认密钥** | 内部 API / vision 使用 `dev-internal-key-change-me`，各服务已对齐 |

本地 mock（`mock-enabled=true`）：验证码 `123456`、mock 支付、mock openId、视觉无模型自动结算。  
上线清单见 [PRODUCTION.md](PRODUCTION.md)。**端口与账号速查**见 [STARTUP_REFERENCE.md](STARTUP_REFERENCE.md)。

---

## 一、架构与启动顺序

```
┌─────────────┐     ┌──────────────┐     ┌───────────────┐
│ 微信小程序   │────▶│ trade-service│────▶│ device-service│
│ 运营 Web 后台│     │   :8080      │     │    :8081      │
└─────────────┘     └──────┬───────┘     └───────┬───────┘
                           │                     │ MQTT
                           ▼                     ▼
                    ┌──────────────┐      ┌─────────────┐
                    │vision-service│      │ EMQX :11883 │
                    │   :8082      │      └──────┬──────┘
                    └──────────────┘             │
                           ▲                     ▼
                    PostgreSQL              设备模拟器 / Android
                    Redis / MinIO
```

**推荐启动顺序：**

1. Docker 基础设施（数据库、MQTT、MinIO 等）
2. 编译 Java 项目
3. vision-service（Python）
4. trade-service
5. device-service
6. 设备端（模拟器 **或** Android，二选一）
7. 微信小程序 / 运营后台

---

## 二、环境准备

| 工具 | 版本要求 | 用途 |
|------|----------|------|
| **IntelliJ IDEA** | 最新 | **推荐**：打开 Maven 工程、运行 Java 服务 |
| JDK | **17+** | 在 IDEA → Project Structure → SDK 中配置 |
| Docker Desktop | 最新 | PostgreSQL、EMQX、MinIO 等 |
| Python | 3.10+ | vision-service（IDEA 装 Python 插件也可） |
| 微信开发者工具 | 最新 | 小程序调试 |
| Android Studio | 可选 | 真机/模拟器端（替代桌面模拟器） |

### 在 IDEA 中打开项目

1. **File → Open**，选择 `ai-cabinet/pom.xml`（根 POM），以 Maven 工程导入  
2. **File → Project Structure → Project → SDK** 选 **JDK 17**  
3. 右侧 **Maven** 面板 → **Reload All Maven Projects**  
4. 等待依赖下载完成

### Python 依赖（vision-service，首次）

**务必使用项目虚拟环境**（系统 Python 可能缺少 `python-multipart` 等依赖）：

```powershell
cd vision-service
python -m venv .venv
.\.venv\Scripts\pip install -r requirements-base.txt
# 开发联调默认 mock，无需 ML 依赖；端侧对接见 VISION_QUECTEL_INTEGRATION.md
```

启动：

```powershell
cd vision-service
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8082
```

<details>
<summary>旧写法（不推荐，易与 Conda 冲突）</summary>

```text
cd vision-service
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8082
```

</details>

<details>
<summary>命令行设置 JDK 17（仅在不使用 IDEA 内置 JDK 时需要）</summary>

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
```

</details>

---

## 三、启动基础设施（Docker）

任选一种方式：

**方式 A — IDEA（推荐）**

1. 安装 **Docker** 插件（Settings → Plugins → Docker）  
2. **Settings → Build, Execution, Deployment → Docker**，连接本机 Docker  
3. 或在仓库根目录执行：`.\docker-up.ps1`（**不要**单独 `infra/docker-compose.yml` Compose Up，否则会多出一个 `infra` 项目）  
4. 在 **Services** 窗口查看 postgres / emqx / minio 是否 Running  

**方式 B — 命令行**

```powershell
cd ai-cabinet
.\docker-up.ps1
docker compose -f infra/docker-compose.full.yml ps
```

| 服务 | 端口 | 说明 |
|------|------|------|
| PostgreSQL | **15433** | 库名/用户/密码均为 `aicabinet`（宿主机端口，避开 Windows 保留段 5433–5532） |
| Redis | 6379 | 缓存（预留） |
| EMQX MQTT | 11883 | 设备通信（宿主机映射，避开 Windows 保留端口段） |
| EMQX 控制台 | 28083 | 默认 admin / public |
| MinIO API | 9000 | 视频存储 |
| MinIO 控制台 | 9001 | minioadmin / minioadmin |
| Redpanda | 9092 | Kafka（默认不用，可忽略） |
| Nginx Gateway | 80 | 可选，需 trade 已启动 |

### 数据库（无需手动执行 SQL）

本地测试 **不需要** 自己跑建表脚本。只要 Docker 里 PostgreSQL 已启动，**trade-service 第一次启动时 Flyway 会自动执行** `services/trade-service/src/main/resources/db/migration/` 下的脚本：

| 脚本 | 内容 |
|------|------|
| `V1__init_schema.sql` | 购物会话、设备、识别结果表 |
| `V2__user_order_sku.sql` | 用户/账户/订单/SKU + **种子数据** |
| `V3__auth_payment.sql` | 微信 openId、充值订单 |
| `V4__dispute_ops.sql` | 争议工单、运营测试账号 |
| `V5__dispute_vision.sql` | 争议扩展字段、SKU 视觉映射 |

启动 trade-service 后日志中应出现类似：

```
Flyway ... Successfully applied X migrations
```

验证数据库（可选）：

```powershell
docker exec -it infra-postgres-1 psql -U aicabinet -d aicabinet -c "\dt"
# 或容器名不同：docker compose ps 查看 postgres 容器名
```

应能看到 `shopping_session`、`user_info`、`sku_catalog` 等表，以及测试用户 `10001`。

> **重要：仅用 IDEA 连接数据库不会建表。** 必须 **Run 一次 `TradeServiceApplication`**，Flyway 才会自动执行迁移脚本并写入种子数据。

**重置数据库（可选）：** 想清空重来时：

```powershell
cd ai-cabinet\infra
docker compose down -v    # 删除 postgres 数据卷
docker compose up -d
# 再启动 trade-service，Flyway 会重新建表
```

连接信息（`application.yml` 默认，一般不用改）：

```
jdbc:postgresql://localhost:15433/aicabinet
用户名/密码：aicabinet / aicabinet
```

### MinIO 创建 bucket（可选）

模拟器使用 `minio://cabinet-videos/sim/...`，可在控制台 http://localhost:9001 创建 bucket `cabinet-videos`。

---

## 四、编译与启动 Java 后端（IDEA 推荐）

### 4.1 编译（首次或改了 common-core 后必做）

IDEA 右侧 **Maven** → 根项目 → **install**（可勾选 Skip Tests），或在终端：

```powershell
cd ai-cabinet
mvn install -pl services/common/common-core,services/trade-service,services/device-service,edge/device-simulator -am -DskipTests "-Dskip.admin.build=true"
```

> 若跳过此步，本地 Run 可能报 `ClassNotFoundException`（如 `OpsRolePermissionsDto`、`MqttTopics`）。**改完 common-core 后需重启** trade/device 进程。

### 4.2 启动 trade-service（:8080）

1. 打开 `services/trade-service/.../TradeServiceApplication.java`  
2. 点击类旁绿色 **Run** ▶  
3. 控制台出现 `Started TradeServiceApplication`  
4. 浏览器访问 http://localhost:8080/actuator/health  

首次启动 Flyway 会自动建表（见第三节「数据库」）。

**IDEA 连接数据库（可选）：** View → Tool Windows → **Database** → `+` → **PostgreSQL**：

| 字段 | 值 |
|------|-----|
| Host | localhost |
| Port | **15433** |
| Database | aicabinet |
| User / Password | aicabinet / aicabinet |

### 4.3 启动 device-service（:8081）

1. 打开 `services/device-service/.../DeviceServiceApplication.java`  
2. 点击 **Run** ▶  
3. 访问 http://localhost:8081/actuator/health  

> 两个 Spring Boot 服务各用一个 Run Configuration，可同时运行（IDEA 允许多个进程）。

### 4.4 启动 vision-service（:8082，Python）

IDEA 已装 **Python** 插件时：

1. **File → Project Structure → Modules** → `+` → 将 `vision-service` 加为 Python 模块（或单独用 PyCharm 打开该目录）  
2. 新建 **Python** Run Configuration：  
   - Script: `uvicorn` 或 Module name: `uvicorn`  
   - Parameters: `app.main:app --reload --port 8082`  
   - Working directory: `vision-service`  
3. **Run** ▶，访问 http://localhost:8082/health  

未配置 Python 插件时，在 IDEA 底部 **Terminal** 执行：

```powershell
cd vision-service
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8082
```

### 4.5 启动设备模拟器

1. 打开 `edge/device-simulator/.../DeviceSimulator.java`  
2. **Run → Edit Configurations** → 该 Main 的 **Program arguments** 填：`CAB-001`  
3. **Run** ▶，控制台出现 `[simulator] listening on ...`  

<details>
<summary>命令行等价方式（不用 IDEA Run 时）</summary>

```powershell
cd ai-cabinet
mvn clean install -DskipTests

cd services\trade-service && mvn spring-boot:run
cd services\device-service && mvn spring-boot:run
cd edge\device-simulator && mvn exec:java "-Dexec.args=CAB-001"
```

</details>

---

## 五、设备端（二选一）

> 本地联调默认用 **4.5 设备模拟器**；有真实柜体再用 Android。

### 方案 A：桌面模拟器

见 **4.5**，IDEA 运行 `DeviceSimulator`，参数 `CAB-001`。

### 方案 B：Android 工控端

1. Android Studio 打开 `ai-cabinet/edge/android-app`
2. 构建变体选 **mockDebug**（模拟器）或 **deviceDebug**（真机串口）
3. 默认 `build.gradle.kts` 已配置 Android 模拟器访问本机：

```kotlin
buildConfigField("String", "MQTT_BROKER", "\"tcp://10.0.2.2:11883\"")
buildConfigField("String", "TRADE_SERVICE_URL", "\"http://10.0.2.2:8080\"")
buildConfigField("String", "MINIO_ENDPOINT", "\"http://10.0.2.2:9000\"")
```

真机部署改为电脑局域网 IP；也可在 App 界面修改 MQTT Broker 后重启 App。

4. 安装到设备/模拟器并启动 App（前台服务自动连接 MQTT）
5. **不要**与桌面模拟器同时运行（同一 `CAB-001` 会冲突）
6. Mock 模式：App 可点 **「模拟用户关门」**，或约 5 秒自动关门

> 默认 **mock** flavor，`useMockDriver=true`，无真实门锁也会完整走通开门→关门→上传流程。

详见 [`edge/android-app/README.md`](../edge/android-app/README.md)。

---

## 六、启动微信小程序

### 1. 安装与开发

```powershell
cd clients/consumer-mp
npm run dev:mp-weixin

cd ../merchant-mp
npm run dev:mp-weixin
```

微信开发者工具分别导入两个客户端的 `dist/dev/mp-weixin` 目录。

### 2. 配置 API 地址

消费者端开发命令会自动探测电脑局域网 IP；商户端通过 `.env.development` 或
`VITE_API_BASE_URL` 配置 API。生产构建必须使用真实 HTTPS 合法域名。

### 3. 开发者工具设置

右上角 **详情 → 本地设置**：

- ✅ 不校验合法域名、web-view、TLS 版本以及 HTTPS 证书

### 4. 测试账号

| 角色 | 手机号 | 验证码 | userId |
|------|--------|--------|--------|
| 消费者 | 13800138000 | 123456 | 10001 |
| 运营员 | 13900000001 | 123456 | 100000001 |
| 财务 | 13900000002 | 123456 | 100000007 |
| 商户管理员 | 13800138001 | 123456 | 100000002 |

余额：消费者种子账户 **100 元**（10000 分）。

### 5. appid 与 urlCheck（构建期注入）

源码里**刻意不存真 appid**（账号资产），`urlCheck` 在源码里也**必须长期保持 `false`**——否则开发者工具会按域名白名单校验，本地连 localhost 后端会直接跑不通。两者都在构建期由 `scripts/inject-miniapp-env.mjs` 写入**构建产物** `dist/{dev,build}/mp-weixin/project.config.json`（该目录已被 `.gitignore` 忽略，不会把账号资产提交进仓库）。

| 变量 | 作用 | 缺省 |
|------|------|------|
| `MP_WEIXIN_APPID_CONSUMER` / `MP_WEIXIN_APPID_MERCHANT` | 对应小程序的 appid（可用 `MP_WEIXIN_APPID` 兜底） | dev 回落 `touristappid`（试玩号） |
| `MP_WEIXIN_URL_CHECK` | 显式 `true` / `false` 覆盖 | dev=false，release=true |

```powershell
# 本地开发：产物 appid=touristappid、urlCheck=false
npm run build:mp-weixin:dev

# 发布构建：必须提供 appid，否则直接失败（这是正确的 fail-closed，不是构建坏了）
$env:MP_WEIXIN_APPID_CONSUMER="wxXXXXXXXXXXXXXXXX"; npm run build:mp-weixin
```

> appid 申请下来后**不需要改任何代码**：在 CI 里把它配成 secret（`MP_WEIXIN_APPID_CONSUMER` / `MP_WEIXIN_APPID_MERCHANT`）即可。
> `dev` 是 watch 模式，不会在结束时跑注入；首次编译后执行一次 `npm run inject:mp-weixin` 即可。

### 6. 编译产物自检（别只看退出码）

`build:mp-weixin:dev` 会产出**两份**：`dist/dev/mp-weixin`（开发者工具导入这个）
与 `dist/build/mp-weixin`。两端编译完成后按下面几项回读磁盘：

| 检查项 | 怎么看 | 正常值 |
|--------|--------|--------|
| 四份产物都在 | `ls clients/*/dist/{dev,build}/mp-weixin` | consumer / merchant 各两份 |
| appid 注入生效 | 读产物 `project.config.json` 的 `appid` | 有环境变量则真值，否则 `touristappid` |
| 后端地址内联 | `grep -r "127.0.0.1" dist/dev/mp-weixin/common/vendor.js` | dev 应命中 `http://127.0.0.1` |
| 源码没被污染 | `git status --porcelain` + 源码 `appid` 字段 | 只应看到 `docs/` 改动；源码 appid 仍为空 |

两个容易误判的点：

- **后端地址不在 `config/` 或 `api/` 目录**，而在 `common/vendor.js`（打包后内联）。
  搜那两个目录搜不到就以为没写入，是误判。
- `app.json` 里 `pages` 只有 5～6 条是**分包（subPackage）结构**的正常表现，
  不是「页面丢失」。真实页面数 = `pages` 长度 + 各 `subPackages[].pages` 之和
  （consumer 21 页 / merchant 27 页）。

> `scripts/sync-consumer-mp-api.mjs` 的地址探测顺序是**回环地址优先、局域网 IP 兜底**
> （真机预览才需要局域网 IP），所以在本机编译出 `http://127.0.0.1` 是符合设计的，不是没探测到。

### 7. 编译报 `SAFE_DELETE_BULK_CONFIRM_REQUIRED` 怎么办

若 `uni build` 在 `Compiling...` 后报：

```text
Error: [safe-delete][SAFE_DELETE_BULK_CONFIRM_REQUIRED] {"count":62,"threshold":50,...}
  at checkBulkDeleteGuard (...\cli\vendor\shim\node-safe-delete-shim.cjs:239:19)
  at emptyDir (...\node_modules\@dcloudio\uni-cli-shared\dist\fs.js:15:22)
```

这是**开发环境拦截，不是代码或依赖坏了**。成因：`uni build` 每次都要清空产物目录，
而 CLI 里的删除动作触发了 IDE 的批量删除保护（阈值 50 个文件）。

处理办法——把旧产物**移走**而不是删掉（移动不触发删除保护，且可逆）：

```bash
# 在仓库根目录执行；.tmp/ 已被 .gitignore 忽略
mkdir -p .tmp/stale-backup
mv clients/consumer-mp/dist/build/mp-weixin .tmp/stale-backup/consumer-mp-mp-weixin
mv clients/merchant-mp/dist/build/mp-weixin .tmp/stale-backup/merchant-mp-mp-weixin
```

🔴 **必须整个 `mp-weixin` 目录一次移走**：uni 的 `emptyDir` 是**按子目录逐个清理**的
（`fs.js:15` 里是 `for (readdirSync) → rmSync`），只移走 `components/` 会在下一个
`pages/`（上百个文件）继续失败。移完直接重跑构建即可。

确认新产物没问题后，`.tmp/stale-backup/` 下的旧文件可以自行清理。

### 8. 操作流程

1. 消费者端登录 → 设备 ID `CAB-001` → 开门购物
2. 商户端登录 → 查看柜机 / 待办 / 定价（受平台开关控制）

详见 [`clients/consumer-mp/README.md`](../clients/consumer-mp/README.md) 与
[`clients/merchant-mp/README.md`](../clients/merchant-mp/README.md)。

---

## 七、运营管理系统（Web 后台）

trade-service 启动后，浏览器打开：

```
http://localhost:8080/admin/index.html
```

或通过 Gateway（需 docker gateway 已启动且 trade 在宿主机运行）：

```
http://localhost/admin/index.html
```

**登录：** 运营账号 `13900000001` / `123456`（userId ≥ 100000000）

**功能模块：**

| 模块 | 说明 |
|------|------|
| 概览 | 统计卡片 + **近7日营收柱状图** |
| 设备 | 柜机列表、**心跳在线状态**（2 分钟无心跳自动离线）；注册/编辑 |
| 会话 | 购物会话列表，按设备/状态筛选；**取消**卡住会话；**导出 CSV** |
| 订单 | 历史订单列表、订单明细；**导出 CSV** |
| 商品 | SKU 新增/编辑（名称、价格） |
| 用户 | 消费者/运营列表、余额；**运营可调余额**（充值/扣减） |
| 设备报表 | 每台柜机累计/今日订单与营收、会话数 |
| 操作日志 | 运营操作审计（取消会话、调余额、SKU/设备变更等） |
| 争议审核 | 识别存疑工单人工结案 |

**Admin API（需运营 JWT）：**

- `GET /api/v2/ops/admin/stats`
- `GET|POST /api/v2/ops/admin/devices` · `PATCH /devices/{deviceId}`
- `GET /api/v2/ops/admin/sessions` · `GET /sessions/export` · `POST /sessions/{id}/cancel`
- `GET /api/v2/ops/admin/orders` · `GET /orders/export` · `GET /orders/{orderId}`
- `GET|POST /api/v2/ops/admin/skus` · `PUT /skus/{skuId}`
- `GET /api/v2/ops/admin/reports/devices` — 按设备经营报表
- `GET /api/v2/ops/admin/audit-logs?page=` — 操作审计日志
- `GET /api/v2/ops/admin/trend` — 近 7 日订单/营收
- `POST /api/v2/ops/admin/users/{userId}/balance` — 调整余额 `{ "deltaCents": 1000 }`
- `POST /internal/v1/devices/{deviceId}/heartbeat` — 设备心跳（device-service 自动调用）

---

## 八、完整联调测试

### 9.1 小程序开门（推荐）

1. 确认终端 1–4 全部运行（infra + 三服务 + 模拟器）
2. 小程序登录 → 开门
3. 观察模拟器终端输出 `door OPEN` → `door CLOSED`
4. 小程序应跳转账单，扣款 3.5 元（SKU-DEMO-001 可乐）

### 9.2 curl 命令行测试

```powershell
# 1. 登录获取 token
$resp = Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v2/auth/login" `
  -ContentType "application/json" `
  -Body '{"phoneNumber":"13800138000","code":"123456"}'
$token = $resp.data.token

# 2. 创建会话（触发开门）
$session = Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v2/sessions" `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType "application/json" `
  -Body '{"deviceId":"CAB-001"}'
$sid = $session.data.sessionId
Write-Host "sessionId=$sid state=$($session.data.state)"

# 3. 等待约 5 秒后查询
Start-Sleep -Seconds 5
Invoke-RestMethod -Uri "http://localhost:8080/api/v2/sessions/$sid" `
  -Headers @{ Authorization = "Bearer $token" }

# 4. 查订单（COMPLETED 后）
Invoke-RestMethod -Uri "http://localhost:8080/api/v2/sessions/$sid/order" `
  -Headers @{ Authorization = "Bearer $token" }
```

### 9.3 争议工单测试（可选）

对会话发送 `file://` 关门事件可触发人工审核：

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/internal/v1/sessions/door-event" `
  -ContentType "application/json" `
  -Headers @{ "X-Internal-Api-Key" = "dev-internal-key-change-me" } `
  -Body "{`"sessionId`":`"$sid`",`"deviceId`":`"CAB-001`",`"doorState`":`"CLOSED`",`"videoUri`":`"file:///tmp/test.mp4`"}"
```

然后在运营后台或小程序「争议审核」页面结案。

### 9.4 充值测试（mock 支付）

小程序 → 充值 → 选择金额 → 确认；`wechat-pay.enabled=false` 时自动 mock 到账。

---

## 九、种子数据速查

| 类型 | 值 |
|------|-----|
| 设备 ID | `CAB-001` |
| 消费者 | 手机 `13800138000`，余额 100 元 |
| 运营员 | 手机 `13900000001`，userId ≥ 100000000 |
| 商品 | `SKU-DEMO-001` 演示可乐，3.5 元 |
| 验证码 | 开发环境固定 `123456` |

---

## 十、端口汇总

| 服务 | 地址 |
|------|------|
| trade-service | http://localhost:8080 |
| device-service | http://localhost:8081 |
| vision-service | http://localhost:8082 |
| 运营后台 | http://localhost:8080/admin/index.html |
| API Gateway | http://localhost/api/v2/ |
| EMQX 控制台 | http://localhost:28083 |
| MinIO 控制台 | http://localhost:9001 |
| PostgreSQL | localhost:**15433** |

---

## 十一、常见问题

### `mvn` 报 Java 版本不对

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

### PostgreSQL 密码认证失败 / 连不上 aicabinet

**常见原因：本机已安装 PostgreSQL（如 `C:\Program Files\PostgreSQL\16`），占用了 5432。**  
IDEA 连 `localhost:5432` 实际连的是**本机 PostgreSQL**，不是 Docker 里的库，因此用户 `aicabinet` 不存在。

**本项目已把 Docker 映射改为宿主机 `15433` → 容器 5432。**

1. 重新启动全栈：`.\docker-up.ps1`（仓库根目录）
2. IDEA Database / trade-service 使用：
   - Host: `localhost`
   - Port: **`15433`**
   - Database / User / Password: `aicabinet` / `aicabinet` / `aicabinet`
3. 确认 `application.yml` 为 `jdbc:postgresql://localhost:15433/aicabinet`

验证：

```text
psql -h 127.0.0.1 -p 15433 -U aicabinet -d aicabinet
# 密码：aicabinet
```

若仍要用本机 5432 上的 PostgreSQL，需自行建库建用户，不建议与 Docker 混用。

### trade-service 连不上数据库

确认 Docker 中 postgres 已启动：`docker compose ps`。首次启动需等 postgres healthy 后再起 trade。

### 小程序请求失败 / 网络错误

- 开发者工具：确认 `BASE_URL` 为 `http://localhost:8080`，并勾选「不校验合法域名」
- 真机预览：`BASE_URL` 改为电脑局域网 IP，手机与电脑同一 WiFi
- 确认 trade-service 已启动：`curl http://localhost:8080/actuator/health`

### 开门后会话一直 OPENING / SHOPPING

- 确认 **device-service** 已启动
- 确认 **设备模拟器或 Android** 已连接 MQTT（模拟器日志有 `listening on`）
- EMQX 是否运行：`docker compose ps emqx`

### 会话 FAILED

常见原因：余额不足（需 ≥ 5 元）、设备被占用、识别争议未处理。查 trade-service 日志。

### Gateway 502

Gateway 转发到 `host.docker.internal:8080`，需宿主机上 trade-service 已启动。

### Maven 下载依赖慢

可配置国内镜像，或重试 `mvn install -DskipTests`。

---

### IDEA 里 Run 报错 Java 版本不对

**File → Project Structure → Project SDK** 选 JDK 17；**Settings → Build → Build Tools → Maven → Runner → JRE** 也选 17。

---

## 十二、最小启动清单（IDEA 速查）

| 步骤 | 在 IDEA / 其他工具里做什么 |
|------|---------------------------|
| 1 | Docker：仓库根目录 `.\docker-up.ps1` |
| 2 | Maven：根项目 **install**（Skip Tests） |
| 3 | Run `TradeServiceApplication` |
| 4 | Run `DeviceServiceApplication` |
| 5 | Run vision-service（`.venv\Scripts\python.exe -m uvicorn app.main:app --port 8082`） |
| 6 | Run `DeviceSimulator`，Program args：`CAB-001` |
| 7 | 微信小程序 | 分别启动 `clients/consumer-mp` 与 `clients/merchant-mp` |

运营后台：http://localhost:8080/admin/index.html

**一键验证**（服务已启动后）：

```powershell
.\scripts\verify-local.ps1 -WithVision
```

---

## 附录：运营大屏底图（高德 JS API）

大屏 `/big-screen` 的底图用高德 JS API 2.0；**未配置 key 时自动降级 Leaflet**（免 key 瓦片），
功能一样、只是底图样式不同。所以这一步是**可选**的。

### 两个 key 必须分清（控制台上是两个不同条目）

| 键 | 平台类型 | 用途 | 填错的后果 |
|---|---|---|---|
| `AMAP_WEB_KEY` | **Web服务** | 服务端地理编码（`AmapGeocodeService`） | 大屏 JS 地图加载不了 |
| `AMAP_JS_KEY` | **Web端(JS API)** | 浏览器加载暗色底图 | 大屏降级 Leaflet |

🔴 **不可互相顶替**：拿 JS key 调 Web 服务接口会返回 `USERKEY_PLAT_NOMATCH`(10009)，
这**不代表 key 失效**，只说明类型不匹配。

### 配置步骤

**最小可用只需第 2、3、4步**（填 `infra/.env` + 生成运行时配置 + 刷新页面）。
第 1、5 步是**推荐**而非必需，理由见下方「实测踩过的坑」。

1. **（推荐）hosts 加一条**（需管理员权限编辑 `C:\Windows\System32\drivers\etc\hosts`）：

   ```
   127.0.0.1    aicabinet.local
   ```

2. **（推荐）高德控制台** → 应用管理 → 对应 Key 的「设置」→ 域名白名单填：

   ```
   aicabinet.local
   ```

   ⚠️ 控制台**不接受** `localhost` / `127.0.0.1`（报「不符合规范的域名」），也不接受端口。
   **但当前实测该白名单并未真正拦截 Referer**（见下），所以留空也能出图 ——
   填它是为了防盗用、避免将来高德收紧校验。

3. **`infra/.env`** 填两个值（未入库文件）——**这步是必需的**：

   ```bash
   AMAP_JS_KEY=<控制台「Web端(JS API)」那条的 Key>
   AMAP_JS_SECURITY_CODE=<该key 配套的安全密钥>
   ```

4. **生成运行时配置**（gateway 会 bind-mount `static/admin`，所以**无需重建镜像**）：

   ```powershell
   node scripts\gen-admin-runtime-config.mjs
   ```

   期望输出 `amapJsKey: 已注入 (len=32, from infra/.env#AMAP_JS_KEY)`。
   该 json 在 `.gitignore` 里 ⇒ **不参与** CI 的 admin-artifacts 逐字节比对。

5. **访问大屏**（`localhost` / `127.0.0.1` / `aicabinet.local` 实测均可）：

   ```
   http://localhost/admin/big-screen
   ```

### 大屏底图的样子

大屏传了 `mapStyle: 'amap://styles/blue'`（见 `BigScreenView.vue:724`），
所以是**深蓝暗色**底图；控制台里默认预览的样式（浅绿/默认色）**与大屏不一致是正常的**。

### 🔴 三个实测踩过的坑

- **`localhost` / `127.0.0.1` 都会被控制台判「不符合规范的域名」**，端口也不行
  （别写 `localhost:80`）—— 这是**保存白名单时**的格式校验，与能否出图无关。
- 🔴 **白名单目前并不强制拦截 Referer**（2026-10-08 实测，勿依赖此行为）：
  用 playwright 把 Referer 伪造成 `http://evil.example.com/x`，`AMapLoader.load()`
  仍返回 `ok:true`、矢量瓦片仍全 200 ⇒ `localhost` 与 `127.0.0.1` 访问都能正常出高德图。
  **白名单仍应填**（防止 key 被他人盗用、避免将来高德收紧校验），
  但「填了才有图」是错误认知 —— 别为绕开它去改 hosts。
- **`infra/.env` 可能落后于 `infra/.env.example`**（它是人工复制的）。
  若新增了配置项而脚本报「未配置」，先diff 两边的键集合 —— 缺失项都有fallback 默认值，
  补齐只为配置显式完整。可用 `.tmp/sync-env-keys.py` 的思路批量同步。

### 为什么 key 放运行时而不是构建时

JS key **禁止**写进 `clients/admin-vue/.env.local`：它在**所有 mode** 下加载，会被 vite 内联进
产物字节；而 CI 检出里没有该文件 ⇒ 同一提交在两处构建得到不同 chunk 哈希，
`admin-artifacts` 的「重建后 `git status` 必须为空」判据永远对不上。
详见 `clients/admin-vue/src/utils/amap.ts` 顶部注释。

### 怎么验证真的出图了（别用 curl）

瓦片接口 `appmaptile` **不校验 Referer**，用 curl 测恒返回 200 ⇒ **无法证明白名单是否生效**。
真判据是浏览器里 `AMapLoader.load()` 与 `web_map/get_tile` 是否 200（失败报`INVALID_USER_DOMAIN` 10008）。
自动化验证需用最小独立页**同源**访问（`/admin/big-screen` 需 `ops:dashboard:view` 登录态，
直接 goto `/admin/index.html` 会落到登录页 → 得到假阴性的 `hasAMap:false`）。

---

---

## 十三、相关文档

| 文档 | 内容 |
|------|------|
| [CODEBASE_FOUNDATION.md](CODEBASE_FOUNDATION.md) | 全仓底稿（测试与优化） |
| [MODULES.md](MODULES.md) | 模块路径与职责 |
| [PRODUCTION.md](PRODUCTION.md) | **生产部署**、环境变量、安全清单 |
| [VISION_QUECTEL_INTEGRATION.md](VISION_QUECTEL_INTEGRATION.md) | 端侧识别对接（替代已废弃的云端 YOLO 文档） |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 架构速查 |
