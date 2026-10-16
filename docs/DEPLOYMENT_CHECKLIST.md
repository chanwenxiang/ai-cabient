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
# 编辑 JWT / INTERNAL / POSTGRES 等密钥，以及「将邑接入」段（见下 §2.1）

.\scripts\deploy-staging.ps1
```

包含：
- `docker-compose.staging.yml`（`SPRING_PROFILES_ACTIVE=staging`，SMS mock，mock 支付关闭）
- `verify-step5.ps1 -CheckEnv` 校验
- 可选 E2E：inventory + warehouse

### 2.1 将邑接入验证（识别=端侧单轨，2026-10-11）

**env（`.env.staging` 的「将邑接入」段，模板已入 `.env.staging.example`）**：

| 变量 | 来源 | 缺失后果 |
|------|------|----------|
| `JIANGYI_JWT_SECRET` | 自生成 ≥32 字节 | 设备 token 签发 503（fail-closed） |
| `JIANGYI_TENANT_ID` / `JIANGYI_SECRET` | 将邑售后 | 入驻（setDomain）失败 |
| `JIANGYI_MERCHANT_MOBILE` / `JIANGYI_MERCHANT_PASSWORD` | 商户后台账号 | 采集/模型/商品库 API 全部不可用 |
| `JIANGYI_PUBLIC_BASE_URL` | 固定公网域名（勿用花生壳） | gather-finish-notify 回调不可达 |
| `JIANGYI_OSS_*` 五项 | 阿里云 RAM（子账号只授 `AliyunSTSAssumeRoleAccess`；直传角色 `jiangyivideoupload`，权限=Put/Get/Delete 限 `ai-cabinet-by/jiangyi-video/*`） | STS 签发失败→视频上传不可用 |

**验证顺序**（部署后逐条）：

1. `docker compose ps` → `jiangyi-gateway` Up（apps 基座自带，nginx `/jiangyi/`、`/websocket/` 依赖它）。
2. 未配 secret 冒烟（可选）：临时注释 `JIANGYI_JWT_SECRET` 重建后 `POST /jiangyi/api/token/openDoorDeviceStatus` 应 **503**；恢复后对未登记 SN 应 **404**、对已绑定设备 **200** 且拿到 token——fail-closed 三态即闭环。
3. 运营后台「设备详情→将邑」：SN 登记 → setDomain（domain=`https://<域名>/jiangyi/api`，socketUrl=`wss://<域名>/websocket`）→ 设备通电后 WS 在线（`/internal/v1/jiangyi/devices/{id}/routable`）。
4. 采集→学习→模型：识别入驻页发起采集 → 将邑 App 采样本 → commitTraining → 回调 `gather-finish-notify` 应 200/202 →「识别映射」页预生成映射→核对→激活→模型下发→设备回执 CONFIRMED。
5. 全链购物： JiangyiDeviceSimulator（或真机）token→getDetail→WS→开门→上报→`COMPLETED`+扣款+扣库存；识别映射未命中的 classId 应转 DISPUTED（fail-closed）。
6. 视频：触发异常单视频上传 → OSS `jiangyi-video/` 出对象 → 争议详情 `videoPreviewUrl` 可播（预签名读路径）。
7. staging 特性：`AICABINET_MOCK_ENABLED=false` ⇒ **未登记将邑的柜机消费开门 409**「仅支持将邑柜机」（批1 生产门禁生效的实证）。

**Staging 与 dev 差异：**

| 项 | dev | staging |
|----|-----|---------|
| Mock 登录验证码 | 123456 | SMS mock 8099 |
| AICABINET_MOCK_ENABLED | true | false |
| 识别 | 无云端识别（将邑端侧） | 无云端识别；jiangyi-gateway 必配 `JIANGYI_JWT_SECRET` |

---

## 3. Production 生产

```powershell
copy infra\.env.production.example infra\.env.production
# 填写全部 WECHAT_* / SMS / OSS / 强密钥

.\scripts\deploy-production.ps1   # 仅校验，不自动部署
```

### 3.1 部署前必查

- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] `JWT_SECRET` / `INTERNAL_API_KEY` / `JIANGYI_JWT_SECRET` ≥ 32 字符，非默认值
- [ ] `SMS_WEBHOOK_URL` 可达
- [ ] 微信支付 API v3 证书与 notify URL HTTPS
- [ ] 小程序 `WECHAT_MINIAPP_ID` / `SECRET`
- [ ] PostgreSQL 备份；Flyway 迁移至最新（V340+）
- [ ] 将邑面：`JIANGYI_JWT_SECRET` 已配（≥32 字节）；`JIANGYI_OSS_ACCESS_KEY_ID/SECRET/ROLE_ARN/REGION/BUCKET` 已配；`JIANGYI_PUBLIC_BASE_URL` 指向固定公网域名（勿用花生壳穿透）；OSS `jiangyi-video/` 生命周期规则已建（§3.1.1）
- [ ] MinIO/OSS 桶策略、CORS、视频生命周期（**OSS 侧见 §3.1.1，必配**）
- [ ] EMQX TLS（设备 MQTT）
- [ ] Gateway HTTPS、CORS 仅运营域名

### 3.1.1 OSS 交易录像生命周期（`jiangyi-video/` 前缀，必配）

> ✅ **已于 2026-10-12 在 `ai-cabinet-by` 桶实际创建**（规则名 `jiangyi-video-expire-90d`：前缀 `jiangyi-video/`、修改时间 90 天数据删除、碎片 30 天删除）。生产环境若换桶，须在新桶重建同规则。

背景：CB-024 起，将邑柜机经 STS 直传的交易录像落在私有桶 `ai-cabinet-by`（`oss-cn-shenzhen`）的 `jiangyi-video/` 前缀下，运营侧经服务端预签名读（CB-029）。对象**只增不减** ⇒ 必须用 OSS **生命周期规则**兜底清理。

- 🔴 **不要写成应用侧定时任务**：生命周期规则是**桶级配置**，由 OSS 服务自身执行，**不消耗我方角色权限**（与 `jiangyivideoupload` 是否有 `oss:DeleteObject` 无关），零代码零 UI。见铁律 #68。
- 路径：**OSS 控制台 → `ai-cabinet-by` → 数据管理 → 生命周期 → 创建规则**

| 规则项 | 取值 | 说明 |
|---|---|---|
| 应用前缀 | `jiangyi-video/` | 只作用于将邑录像，不碰桶内其它对象 |
| 过期删除 | 对象创建后 **90 天**（2026-10-12 定稿，见下） | 到点自动删除；⚠️ OSS 在规则生效后**次日**执行，非精确定时 |
| 历史版本 | 不启用 | 桶未开版本控制 |
| 碎片过期 | 30 天（2026-10-12 实建值） | 清理分片上传失败的残留碎片 |

**N 定稿 = 90 天**（2026-10-12）：设备端视频保留策略已实锤——**视频保留 1 个月 / 设备本地存储 64GB**（`JIANGYI_GATEWAY_DESIGN.md §7.6`）。OSS 与设备保留期**解耦**：OSS 只存已上传的**异常单**视频（正常单只留柜机本地，超 1 个月随设备清理消失且不可回拉）。90 天是**自己的设计判断**：覆盖常见资金争议/审计 90 天追溯窗口；桶内仅异常单录像，存储成本可忽略，取值可随业务窗口调整。若抽检要求 >1 个月且需覆盖正常单 ⇒ 先改 §4.2.13 为**全量上传**，再相应调大本值。

> 角色 `jiangyivideoupload` 的 `oss:DeleteObject` 权限（2026-10-11 已开通）**只**服务于「运营手动删单条」这类**服务端**调用——该能力**当前未开放**（无对应业务需求；且删对象会让 `jiangyi_order_video` 台账地址变死链，须同步处置台账）。保留期清理**一律走本生命周期规则**。

### 3.2 部署命令

```bash
cd infra
docker compose -f docker-compose.yml -f docker-compose.apps.yml \
  --env-file .env.production --profile apps up -d --build
```

> **`infra/docker-compose.prod-server.yml` 是服务器侧维护的覆盖文件（审计 A3，2026-10-16 定性）**：
> 它**不在 git 里**（`git log --all` 证实从未入库），只存在于生产服务器部署目录，
> 由 `scripts/deploy-prod.sh:103` 在服务器现场引用（2026-10-13 实战部署已验证可用）。
> 服务器特定配置（如 bind mount 路径）改它须 **ssh 到服务器直接编辑**；本地没有、也不该有副本。
> 排查「compose 文件缺失」类报错时先分辨：仓库内文件 vs 服务器侧文件。

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
