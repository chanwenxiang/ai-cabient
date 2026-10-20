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
| `JIANGYI_PUBLIC_BASE_URL` | 固定公网域名（勿用穿透域名） | ① gather-finish-notify 回调不可达；② **域名自钉失效**：`domainConfig/*` 三端点一起回 404，设备继续用将邑云 HTTP base（设计文档 §5 拍板 A） |
| `JIANGYI_OSS_*` 五项 | 阿里云 RAM（子账号只授 `AliyunSTSAssumeRoleAccess`；直传角色 `jiangyivideoupload`，权限=Put/Get/Delete 限 `ai-cabinet-by/jiangyi-video/*`） | STS 签发失败→视频上传不可用 |

**验证顺序**（部署后逐条）：

1. `docker compose ps` → `jiangyi-gateway` Up（apps 基座自带，nginx `/jiangyi/`、`/websocket/` 依赖它）。
2. 未配 secret 冒烟（可选）：临时注释 `JIANGYI_JWT_SECRET` 重建后 `POST /jiangyi/api/token/openDoorDeviceStatus` 应 **503**；恢复后对未登记 SN 应 **404**、对已绑定设备 **200** 且拿到 token——fail-closed 三态即闭环。
3. 运营后台「设备详情→将邑」：SN 登记 → setDomain（domain=`https://<域名>/jiangyi/api`，socketUrl=`wss://<域名>/websocket/device/`——**尾段 `/device/` 与尾斜杠都不能少**，固件按 `socketUrl + identifier + ?token=` 直拼，缺 `/device/` 即 404；留空则按 `JiangyiOnboardingService.deriveSocketUrl` 自动推导）→ 设备通电后 WS 在线（`/internal/v1/jiangyi/devices/{id}/routable`）。
   - 🔴 **顺序不可颠倒**：设备是在**已持有合法 token 之后**才轮询 `domainConfig/*`（实测 token → status → domain，同 base 同 token）。先钉域名、设备 yet 无我方 token ⇒ 从厂商云切到我方再吃 401，比不切更难查。
4. **域名自钉冒烟（拍板 A，配了 `JIANGYI_PUBLIC_BASE_URL` 才做）**：一键跑 `pnpm jiangyi:day1-check`（=`node scripts/jiangyi-day1-check.mjs`，**纯只读探测**，可用 `--base=` 指 dev 子域）；手工口径同脚本：带合法设备 token `POST /jiangyi/api/domainConfig/getConfigStatusByIdentifier` 应回 `{"status":null,"msg":null,"data":1}`（**注意 status/msg 是 null，不是 200**——照抄云侧怪形态；该值在固件里是 `USE_TYPE` 设备使用模式，见设计文档 §5 语义修订），`POST /jiangyi/api/domainConfig/getConfigDomainByIdentifier` 应回 `data` = `https://<域名>/jiangyi/api`（尾段自动补、幂等；**每次开机都会调，是首切硬前置**），`POST /jiangyi/api/domainConfig/getConfigSocketUrlByIdentifier` 应回 `data` = `wss://<域名>/websocket/device/`（由同一 host 推导；**迁移链交接端点**——首次切到我方时设备问的是厂商云，此端点不是首切硬前置，但三端点共用同一开关，部署应带全，避免半开）。未配该变量时三端点**一起 404** = 预期不启用，不是故障。
   - 🔴 **首切真正的前置是前两条在 prod 在线**：`getToken`→`getConfigStatus`→`getConfigDomain` 是每次开机的必经链（真机 log3 实测顺序），旧 jar 缺任何一条都会让已切到我方的新固件吃 404 走未实测回落。第三条（socketUrl）只在「拉回域名≠`COMMON_URL` 且 prefs `url`/`socketurl` 任一为空」的迁移链里被调，且请求打的是 `COMMON_URL`（设备当前服务器）——首切时=厂商云，真机 4 份 logcat 检索为 0 次调用；它在我方被再次换域时才是关键（此时设备问的才是我们）。版本与部署仍应三端点齐全（半开只有坏处）。部署前 `git status --porcelain` 必须干净（含新文件已提交）。
   - 脚本的判据解释：**不带 token 也得先探**——三条路由门（`/jiangyi/api/**`、catch-all、`/websocket/` 握手）全回 401 才证明入口 nginx 把设备面两条路送到网关；此时若自钉已配却回 404/502，说明钉的是死地址。
   - 域名更换（花生壳 → 正式域名）**只改 `JIANGYI_PUBLIC_BASE_URL` 一行**，无需改代码；但改完必须重跑本条冒烟，并确认 `infra/.env.production`（`deploy-prod.sh:111` 的 `--env-file`，仓库只有 `.example`）已同步换值。
   - 🔴 **部署前的 archive 陷阱**（`scripts/deploy-prod.sh:43-49/57`）：干净性判定是 `git status --porcelain | grep -v '^??'`——**未跟踪文件不拦**，而打包用 `git archive HEAD` 只含已提交内容。⇒ 新增的 Java 文件（如 `DomainConfigController` / `DeviceCompatController` / `DeviceUnknownPathController`）若忘了 `git add`+commit，**部署会照常成功但跑的是没有自钉的旧类**（现网表现为真机拉 `domainConfig` 吃 catch-all 404，与"配置没生效"极像）。部署前 `git status --porcelain | grep '^??'` 必须为空。
5. 采集→学习→模型：识别入驻页发起采集 → 将邑 App 采样本 → commitTraining → 回调 `gather-finish-notify` 应 200/202 →「识别映射」页预生成映射→核对→激活→模型下发→设备回执 CONFIRMED。
6. 全链购物： JiangyiDeviceSimulator（或真机）token→getDetail→WS→开门→上报→`COMPLETED`+扣款+扣库存；识别映射未命中的 classId 应转 DISPUTED（fail-closed）。
7. 视频：触发异常单视频上传 → OSS `jiangyi-video/` 出对象 → 争议详情 `videoPreviewUrl` 可播（预签名读路径）。
8. staging 特性：`AICABINET_MOCK_ENABLED=false` ⇒ **未登记将邑的柜机消费开门 409**「仅支持将邑柜机」（批1 生产门禁生效的实证）。

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
- [ ] PostgreSQL 备份；Flyway 已到本分支高水位 **V389**（全新库启动日志逐条 Migrating 到 `389 - drop six legacy wallet tables`；已在该版本的老库启动时不再有 Migrating 行属正常）
- [ ] 将邑面：`JIANGYI_JWT_SECRET` 已配（≥32 字节）；`JIANGYI_OSS_ACCESS_KEY_ID/SECRET/ROLE_ARN/REGION/BUCKET` 已配；`JIANGYI_PUBLIC_BASE_URL` 指向固定公网域名（勿用花生壳穿透）；OSS `jiangyi-video/` 生命周期规则已建（§3.1.1）
- [ ] MinIO/OSS 桶策略、CORS、视频生命周期（**OSS 侧见 §3.1.1，必配**）
- [ ] **资金类定时任务的开启状态已逐条确认**（V376 起默认关；2026-10-17 拍板**上线当天两条都保持关**，见 §3.1.2）
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

### 3.1.2 资金类定时任务默认关闭（V376；上线当天**保持关**）

> DC-29（2026-10-16 用户拍板「靠运营台手动开」）：三条「无人开启就会自己动钱」的路径改成了显式启用。
> §12 待拍板 #8（2026-10-17 用户拍板）：**`refund-retry` 与 `deposit-monthly-refund` 上线当天都保持关闭**
> （两种 `seed_env` 下实测种子行均为 `enabled=false`，无需迁移）。开启是**上线后的独立运营决策**，
> 下表「不开的后果」就是这个决策的代价，必须读完再决定，不要靠记忆。
> 机制：`ScheduledTaskService#tryBegin` 见到 `scheduled_task.enabled=FALSE` 直接跳过（跳过原因 `task disabled`
> 在运营台执行记录可见），所以**关的是数据行，不是代码**；运营台「定时任务」页可一键启停。

| 任务 key | 干什么 | 不开的后果 | 何时该开（拍板后仍要逐条确认） |
|---|---|---|---|
| `refund-retry` | 每 60 秒扫 `REFUND_INITIATED` 痕迹：查单优先、同号重发（渠道幂等）、超 30 分钟置 `REFUND_FAILED` + HIGH 告警 | 🔴 **渠道退款失败/进程中断后无人闭环**——本地痕迹永挂，只剩 T+1 金额对账兜底；商户/消费者看到的「退款中」不会自己变终态 | ⚠️ 此处与 2026-10-17 拍板有张力：审计建议是「生产一上线就该开」，当前定的是**上线当天先关**。⇒ 微信退款凭据就绪、且确认试点期有真实退款流量后，**由运营在定时任务页显式开**，并在下面勾选项留痕 |
| `deposit-monthly-refund` | 每月 1 日 01:30 按柜把押金分期退回商户余额 | 押金不自动退还，须运营手动退（对齐老系统「人工点退押金」形态）——试点柜少时人工可控 | 确定要跑「按月自动退还」商业模式时再开（V360/CB-044 拍板项）；开之前先核对押金台账 `device_rental_deposit` |
| `device_rental_deposit.install_fee_clawback_enabled` | 押金已按月退完且未满合同年数时**倒扣商户余额**安装费（可挂负） | 撤机时不追回安装费（少收钱，不会多扣商户） | 逐柜显式开：当前**运营台登记表单未暴露该参数**，只能由登记接口显式传 `installFeeClawbackEnabled=true` |

- [ ] 已就 `refund-retry` 作出「开/关」决定并记在这里：\_\_\_\_\_\_\_\_（决定人 / 日期）
- [ ] 已就 `deposit-monthly-refund` 作出「开/关」决定并记在这里：\_\_\_\_\_\_\_\_（决定人 / 日期）

- 验证：`GET /api/v2/ops/admin/scheduled-tasks` 或运营台页面看 `enabled`；迁移侧 V376 末尾有零残留断言（没关上就 fail 迁移）。
- dev/staging 同状态（迁移不分环境，凭据可能是真的就不该自动跑）；要测退款闭环 ⇒ 运营台打开该任务再测，测完按需关闭。

### 3.2 部署命令

```bash
cd infra
docker compose -f docker-compose.yml -f docker-compose.apps.yml \
  --env-file .env.production --profile apps up -d --build
```

> **`infra/docker-compose.prod-server.yml` 已入库（AU-185，2026-10-19 拍板「入库+预检」）**：
> 旧定性（A3，2026-10-16）是「服务器侧维护、不入库、不该有副本」——已被本拍板取代。
> 文件内容自生产服务器实取（**纯拓扑无密钥**，密钥全走 `.env.production`），入库基线
> sha256 `1b038520…` 注记在文件头。此后**改动一律走 git**（可评审、可复现），每次部署
> 由 tar 解包以仓库版本收敛服务器副本；`deploy-prod.sh` 在本地 [0/6] 与服务器 [4/6]
> 两处预检该文件存在，并把 sha256 指纹打进部署日志。门禁
> `check:deploy-compose-chain` 拒绝「deploy-prod.sh 引用了仓库里不存在的 -f 文件」。

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
