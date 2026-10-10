# 部署清单（Staging / Production）

与 [PRODUCTION.md](PRODUCTION.md) 互补：本文是可勾选的上线步骤。

---

## 1. 本地 / 演示（已完成能力）

| 步骤 | 命令 |
|------|------|
| 启动全栈 | `cd infra && docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps up -d --build` |
| 完整 E2E | `.\scripts\verify-step1.ps1 -Build` |
| Phase A 效期/补货 | `.\scripts\e2e-inventory-phase-a.ps1` |
| Phase B 仓配 | `.\scripts\e2e-warehouse-phase-b.ps1` |

**演示数据（Flyway V25）：** 6 款 SKU（分类+占位图）、CAB-001 柜内库存、WH-DEMO-001 仓库批次。

**小程序：** 开发者工具勾选「不校验合法域名」以便加载 `placehold.co` 商品图；上线前改为 OSS/CDN 并配置 downloadFile 白名单。

---

## 2. Staging 预发

```powershell
copy infra\.env.staging.example infra\.env.staging
# 编辑 JWT / INTERNAL / POSTGRES 等密钥

.\scripts\deploy-staging.ps1
```

包含：
- `docker-compose.staging.yml`（`SPRING_PROFILES_ACTIVE=staging`，SMS mock，mock 支付关闭）
- `verify-step5.ps1 -CheckEnv` 校验
- 可选 E2E：inventory + warehouse

**Staging 与 dev 差异：**

| 项 | dev | staging |
|----|-----|---------|
| Mock 登录验证码 | 123456 | SMS mock 8099 |
| AICABINET_MOCK_ENABLED | true | false |
| Vision | mock | mock（可改 yolo） |

---

## 3. Production 生产

```powershell
copy infra\.env.production.example infra\.env.production
# 填写全部 WECHAT_* / SMS / OSS / 强密钥

.\scripts\deploy-production.ps1   # 仅校验，不自动部署
```

### 3.1 部署前必查

- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] `JWT_SECRET` / `INTERNAL_API_KEY` / `VISION_API_KEY` ≥ 32 字符，非默认值
- [ ] `SMS_WEBHOOK_URL` 可达
- [ ] 微信支付 API v3 证书与 notify URL HTTPS
- [ ] 小程序 `WECHAT_MINIAPP_ID` / `SECRET`
- [ ] PostgreSQL 备份；Flyway 迁移至 V26+
- [ ] MinIO/OSS 桶策略、CORS、视频生命周期（**OSS 侧见 §3.1.1，必配**）
- [ ] EMQX TLS（设备 MQTT）
- [ ] Gateway HTTPS、CORS 仅运营域名

### 3.1.1 OSS 交易录像生命周期（`jiangyi-video/` 前缀，必配）

背景：CB-024 起，将邑柜机经 STS 直传的交易录像落在私有桶 `ai-cabinet-by`（`oss-cn-shenzhen`）的 `jiangyi-video/` 前缀下，运营侧经服务端预签名读（CB-029）。对象**只增不减** ⇒ 必须用 OSS **生命周期规则**兜底清理。

- 🔴 **不要写成应用侧定时任务**：生命周期规则是**桶级配置**，由 OSS 服务自身执行，**不消耗我方角色权限**（与 `jiangyivideoupload` 是否有 `oss:DeleteObject` 无关），零代码零 UI。见铁律 #68。
- 路径：**OSS 控制台 → `ai-cabinet-by` → 数据管理 → 生命周期 → 创建规则**

| 规则项 | 取值 | 说明 |
|---|---|---|
| 应用前缀 | `jiangyi-video/` | 只作用于将邑录像，不碰桶内其它对象 |
| 过期删除 | 对象创建后 **N 天**（⬜ 待定，见下） | 到点自动删除；⚠️ OSS 在规则生效后**次日**执行，非精确定时 |
| 历史版本 | 不启用 | 桶未开版本控制 |
| 碎片过期 | 建议一并配置（如 7 天） | 清理分片上传失败的残留碎片 |

**N 取值**（与「设备端视频保留策略」一并定稿，见 `JIANGYI_GATEWAY_DESIGN.md §7.6`）：须 **≥** 运营抽检/争议追溯窗口。若设备端保留期短于抽检窗口 ⇒ 需先改 §4.2.13 为**全量上传**（默认只传异常单视频），再配本规则兜底。

> 角色 `jiangyivideoupload` 的 `oss:DeleteObject` 权限（2026-10-11 已开通）**只**服务于「运营手动删单条」这类**服务端**调用——该能力**当前未开放**（无对应业务需求；且删对象会让 `jiangyi_order_video` 台账地址变死链，须同步处置台账）。保留期清理**一律走本生命周期规则**。

### 3.2 部署命令

```bash
cd infra
docker compose -f docker-compose.yml -f docker-compose.apps.yml \
  --env-file .env.production --profile apps up -d --build
```

### 3.3 部署后冒烟

1. `GET /actuator/health` → UP  
2. 运营后台登录 + 仪表盘  
3. 仓库页可见 WH-DEMO-001 库存  
4. 小程序：商品列表、开门购物 E2E（真 SMS/支付沙箱）  
5. Prometheus/Grafana（可选）

---

## 4. 数据库迁移版本

| 版本 | 说明 |
|------|------|
| V24 | Phase A 批次/效期/补货行 |
| V25 | 演示 SKU + 柜内库存 |
| V26 | Phase B 仓库 WMS + 出库绑定路线 |

---

## 5. 回滚

1. 停止 apps：`docker compose ... down`  
2. 恢复 PostgreSQL 快照（迁移不可逆时需还原库）  
3. 回退镜像 tag：`IMAGE_TAG=<previous> docker compose ... up -d`

---

## 6. 相关脚本

| 脚本 | 用途 |
|------|------|
| `deploy-staging.ps1` | 预发一键 compose + E2E |
| `deploy-production.ps1` | 生产 env 校验 + 人工清单 |
| `verify-full.ps1` | 本地 Maven + Docker 全量 |
| `init-staging-env.ps1` | 初始化 staging env 文件 |
### Production hard gates

- [ ] If `PAYSCORE_LIVE_CHARGE_ENABLED=true`, configure a real `PAYSCORE_CHARGE_GATEWAY_URL` and `PAYSCORE_CHARGE_GATEWAY_API_KEY`.
- [ ] `MQTT_CLIENT_ID` is unique per environment, and `MQTT_USERNAME` / `MQTT_PASSWORD` are non-empty with a password length of at least 16 characters.
- [ ] Prometheus scrapes `device.command` metrics and shows published / ack_success / ack_failure / ack_timeout.

### Step 4 production readiness gate

Run this before staging sign-off and before every production release candidate:

```powershell
.\scripts\verify-step4.ps1 -ProductionReadiness -SkipRuntime
```

When trade-service and device-service are already running, remove `-SkipRuntime` and add the abnormal smoke check:

```powershell
.\scripts\verify-production-readiness.ps1 -BaseUrl http://localhost:8080 -DeviceUrl http://localhost:8081
.\scripts\run-step4-abnormal-smoke.ps1 -BaseUrl http://localhost:8080 -DeviceUrl http://localhost:8081 -InternalApiKey $env:INTERNAL_API_KEY
```

For one-command Docker startup plus runtime smoke:

```powershell
.\scripts\start-docker-step4.ps1 -Build
```

Equivalent command from `infra/`:

```powershell
.\up.ps1 -Build -Smoke
```

The Step 4 gate must verify:

- [ ] Admin static bundle exists and `clients/admin` build passes.
- [ ] Trade targeted tests cover reconciliation mismatch rerun and replenishment outbound exceptions.
- [ ] Device command tracker tests cover ACK timeout, duplicate ACK, late ACK, and terminal status transitions.
- [ ] `device-service` compiles with the current common module.
- [ ] Ops workbench exposes stale sessions, reconciliation mismatches, split exceptions, and in-transit overdue counters.
- [ ] Reconciliation run/detail API returns a traceable `reconId`, `detailJson`, and bill lines.
- [ ] Device command status API returns a trackable command state for open-door commands.
