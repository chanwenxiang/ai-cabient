# 分步推进路线图

硬件型号未定、时间充裕时，按下面顺序**一步一步**做即可。每步都有明确「完成标准」，通过后再进下一步。

> Android 真机端：在 **Step 1～4 跑通** 后再动；硬件规格书到位后只改 HAL/摄像头（见 [edge/android-app/README.md](../edge/android-app/README.md)）。

---

## 总览

| 步骤 | 主题 | 主要涉及 | 预计 |
|------|------|----------|------|
| **1** | 全栈 Docker + E2E | infra、脚本 | 0.5～1 天 |
| **2** | IDEA 本地联调（可选加深） | trade、device、vision、模拟器 | 0.5 天 |
| **3** | 小程序体验完善 | clients/miniapp | 1～2 天 |
| **4** | 视觉识别实链路 | vision-service、MinIO 视频 | 2～3 天 |
| **5** | 生产化与试点 | PRODUCTION、微信/SMS 实配 | 按上线计划 |
| **6** | 硬件到位后 Android | edge/android-app device  flavor | 硬件到后再做 |

---

## Step 1：全栈 Docker + E2E（当前建议从这里开始）

**目标**：不依赖 IDEA，一条命令拉起整套服务，脚本验证充值 + 购物闭环。

### 前置

1. 安装并启动 **Docker Desktop**（Windows 需 Linux 引擎运行中）
2. JDK 17+、Maven（镜像构建用）

### 操作

```powershell
cd ai-cabinet
.\scripts\verify-step1.ps1 -Build
```

或手动：

```powershell
cd infra
copy .env.example .env    # 若尚未创建
.\up.ps1 -Build
# 等待 2～5 分钟，trade-service 显示 (healthy)
cd ..
.\scripts\e2e-recharge.ps1
.\scripts\e2e-shopping.ps1
```

### 完成标准

- [x] `docker compose ps` 中 postgres、trade、device、vision 为 **healthy**
- [x] 浏览器打开 http://localhost/admin/index.html 能登录（`13900000001` / `123456`）
- [x] `e2e-recharge.ps1` 输出 `OK`
- [x] `e2e-shopping.ps1` 输出 `OK shopping E2E passed`

### 常见问题

| 现象 | 处理 |
|------|------|
| Docker daemon 未运行 | 启动 Docker Desktop，等托盘图标就绪后重试 |
| `INTERNAL_API_KEY` 警告 | 确保 `infra/.env` 存在（从 `.env.example` 复制） |
| trade 长期 starting | `docker compose logs -f trade-service`，查 Flyway/端口占用 |
| 8080 被 IDEA 占用 | 停掉本地 trade-service，或改 compose 端口 |

---

## Step 2：IDEA 本地联调（与 Step 1 二选一或对照）

**目标**：开发时只 Docker 基础设施，Java/Python 在 IDE 里调试。

文档：[LOCAL_SETUP.md](LOCAL_SETUP.md)

### 一键脚本

```powershell
# 停 Step1 容器 + 只起 infra
.\scripts\start-infra.ps1

# 验证（需 IDEA 已启动 trade/device/vision/simulator）
.\scripts\verify-step2.ps1

# 或自动后台启动 Maven/Python 并跑 E2E（无需 IDEA）
.\scripts\verify-step2.ps1 -StartLocal
```

IDEA 共享 Run Configuration：项目根目录 `.run/`（trade-service、device-service、vision-service、DeviceSimulator）。

### 操作概要

1. `.\scripts\stop-apps.ps1` 或 `cd infra && docker compose up -d`（仅基础设施，不要 `--profile apps`）
2. IDEA Run：`trade-service` → `device-service`（`.run/` 里已配好）
3. Terminal / Run：`vision-service` uvicorn :8082
4. Run：`DeviceSimulator` 参数 `CAB-001`
5. 微信开发者工具导入 `clients/miniapp`

### 完成标准

- [x] 脚本 E2E：`.\scripts\verify-step2.ps1 -SkipInfra`（本地 trade/device/vision 已启动时）
- [ ] 小程序开门 → 模拟器关门 → 账单页有订单（需微信开发者工具 + DeviceSimulator）
- [x] 运营后台能看到设备、会话、订单（http://localhost:8080/admin/index.html）

---

## Step 3：小程序（C 端）

**目标**：在 dev mock 稳定基础上，完善用户侧体验；**暂不要求**生产微信支付。

建议顺序：

1. 会话状态中文 + 识别中/待审核/失败提示（✅ 已做）
2. tabBar：首页（开门）+ 我的（✅ 已做）
3. 真机预览：`api.js` 改局域网 IP + 开发者工具「不校验合法域名」
4. （Step 5 再做）微信登录、真实微信支付

文档：`clients/miniapp/README.md`

---

## Step 4：视觉识别实链路

**目标**：购物视频从 MinIO 拉取 → YOLO/融合识别 → 订单扣款，减少 mock。

文档：

- [VISION_YOLO_TEST.md](VISION_YOLO_TEST.md)
- [PHASE4.md](PHASE4.md)、[PHASE5.md](PHASE5.md)

建议顺序：

1. 本地 YOLO 识别一张测试图（✅ `vision-service/scripts/step4_check.py`）
2. vision-service 支持 `minio://` 视频下载后推理（✅ 已实现）
3. 多摄 `videoClipsJson` 融合（✅ 已实现，见 step4_check MULTI）
4. `VISION_MOCK_ENABLED=false` 时在 compose 里验证（见下方命令）

一键验证：

```powershell
.\scripts\verify-step4.ps1              # upload + minio + 多摄 API
.\scripts\verify-step4.ps1 -WithE2e     # 再加 trade 实链路 E2E
```

Compose 真实 YOLO（`infra/.env` 设 `VISION_INSTALL_ML=true`、`VISION_MOCK_ENABLED=false` 后 rebuild vision-service）。

---

## Step 5：生产化与试点

**目标**：同一套代码，改环境变量上线。

文档：

- [PRODUCTION.md](PRODUCTION.md) — 上线检查表
- [OPS_COMMERCIAL.md](OPS_COMMERCIAL.md) — 运营模块与待办

包括：强密钥、微信 V3、SMS、EMQX TLS、OSS、对账 API 等。

---

## Step 6：Android 真机（硬件确定后）

**目标**：替换 mock 门锁与 CameraX 绑定，不改 MQTT/上传契约。

文档：[edge/android-app/README.md](../edge/android-app/README.md)「硬件未定时的开发范围」

---

## 当前进度（可自行勾选）

- [x] 运营后台 Vite + 核心运营模块
- [x] 小程序主流程 + 订单/充值记录 + 我的
- [x] Android mock 模式 + 文档（硬件暂缓）
- [x] Compose healthcheck + infra 文档
- [x] **Step 1：全栈 Docker E2E 通过**
- [x] **Step 2：IDEA 本地联调（脚本 E2E + 运营后台）**
- [x] **Step 3：小程序 tabBar + 会话状态体验**
- [x] **Step 4：视觉实链路（YOLO + MinIO + 多摄融合）**
- [ ] Step 5～6

---

## 你怎么继续

对助手说 **「继续 Step 1」**、**「继续 Step 3」** 等即可，会按该步逐项实现或排查。

若本机 Docker 未开，先启动 Docker Desktop，再运行：

```powershell
.\scripts\verify-step1.ps1 -Build
```
