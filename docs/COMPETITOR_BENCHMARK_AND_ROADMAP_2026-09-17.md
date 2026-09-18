# 竞品对标审计与功能升级路线图（2026-09-17）

> 审计方式：只读代码盘点（后端 83 个 Controller / 276 个 Flyway 迁移、admin-vue 约 60 页面 318 端点、双小程序、edge/android-app、vision-service、infra），结合既有审计文档（`three-end-full-audit-2026-09-15.md`、`production-launch-checklist.md`、`CODEBASE_FOUNDATION.md`、`GO_LIVE_EXECUTION_PLAN.md`），对标市面主流竞品后输出。**本文档只出计划，不动代码。**

> **09-18 复核更正**：对本文档关键判据逐条回到源码 / 工作流重新取证，**推翻 2 条过期结论**（§2.5 的 M-1/M-2/M-3、Sonar `on.push`），**修正 2 处判据**（appid 实际有 `project.config.json` 一份且门禁红灯在 `urlCheck`；M 组客户端其实已接开关），**新发现 1 条**（两端小程序 `type-check` 因 `tsc` 不解析 `.vue` 而假绿），另补充 G1 的线长侧同族副本。**并当轮修复了 C-1/C-2/C-3 与 A-1**（见 §2.5 逐条）。更正过程见 §2.5 行内标注。

---

## 1. 一句话结论

本项目「交易与运营主链路」已达到或超过国内头部竞品的中台能力（开门购物闭环、微信服务商分账、采购-仓储-补货全链路、按钮级 RBAC、审批流、30+ 可托管定时任务）；真正的差距集中在三处：**① 端侧视觉识别未落地（生死线）；② 资金末梢未闭环（提现 mock、发票无真实开票）；③ 增长玩法与变现侧空白（分享裂变、动态定价、广告变现、企业 B 端、平台流量）**。另有若干上线硬阻塞（P0）不属于竞品差距，但必须先修。

---

## 2. 现状盘点（代码事实）

### 2.1 后端 trade/device service — 成熟度：交易主链路接近生产级

**已完成且深于多数竞品的域：**
- 开门购物全链路：开门预授权冻结（`ConsumerPreauthService`）→ 视觉识别 → 自动结算 → 多渠道支付 → 部分/全额退款 → 争议 SLA（`DisputeSlaService`）→ 发票记录流。幂等键、TCC 补偿、分布式锁、每日对账（支付宝/微信 CSV）、数据一致性巡检（5 分钟）齐备。
- 资金分账：微信服务商分账**真实对接**（提交/回退/查询/重试/补偿，`WeChatProfitSharingService`）；商户/线长钱包、佣金日结、场地租金账单、物联卡费用、资金日账单。
- 供应链：采购单→收货→退货→供应商应付、仓库库位/出入库/调拨/盘点/在途、智能补货建议（趋势预测+动态安全库存）、补货路线优化（高德距离矩阵可插拔+回退）、批次/临期。
- 商户生态：进件工作台、微信收款方绑定、商户定价（乐观锁）、功能包、团队角色、AI 洞察、数据范围隔离。
- 平台工程：按钮级 RBAC（若依式）、可配置审批流（V228-232）、通知三渠道（站内信/微信订阅/短信）、XXL-JOB 托管 30+ 任务、2FA（TOTP+后备码）、OTA 固件、数据一致性巡检。

**明确缺口（附证据）：**
| # | 缺口 | 证据 |
|---|------|------|
| G1 | 商户/线长提现打款未闭环，仅 Mock | `MerchantWithdrawPayoutService.java:47`「微信商户转账接口尚未接入（非 Mock 环境不可打款）」＋ **同族副本** `LineWithdrawPayoutService.java:47`「微信转账到零钱接口尚未接入」；两处 `transferApiReady=false`（各 `:68`）。**修时两端成对** |
| G2 | 发票无税控服务商对接，仅记录流 | `InvoiceService`（apply/issue/reject + 税档），无航信/百望类集成 |
| G3 | 营销玩法单一：无秒杀/拼团/签到任务/裂变 | 全库 grep 无 seckill/groupbuy/checkin 有效命中；V128/V130/V131 曾主动 DROP 邀请裂变/加盟/游戏化 |
| G4 | 无动态定价/时段促销/临期自动折扣引擎 | 仅商户手工调价（`MerchantSkuPricingService`+版本历史），无策略引擎 |
| G5 | 无设备租赁/分期/押金计费（对商户侧） | 仅有向场地方的 `SiteRentBill`（我方应付） |
| G6 | 无加盟/点位合伙人体系（曾建后删） | V130 DROP franchise_settlement/franchise_device |
| G7 | 无企业 B 端（团购/福利采购/账期） | 无对应代码 |
| G8 | 无第三方平台流量接入（美团等） | 无对应代码 |
| G9 | ~~PayScore 签约开通/解约链路不完整~~ → **签约已实现，仅缺口收窄为「用户主动解约端点」（09-18 复核更正）** | ✅ **签约链路已在**：`AccountController.java:81` `POST /payscore/sign` → `PayScoreService.signWeChatPayScore:92`；`:88` `POST /alipay-agreement/sign` → `signAlipayAgreement:124`，回调绑定 `bindAlipayAgreementFromNotify:166/:176`，含 `AlipayPayClient:45` 的 H5 签约表单。⚠️ **仍缺**：**用户主动解约**端点——解约目前只在**通知回调**侧处理（H68，`PayScoreService:159-247` 识别 `UNSIGN/TERMINATE/CLOSE…` 类状态并清除 `alipayAgreementId`），没有「用户在 App 内点解约」的入口。原表述「依赖已有 contractId、无签约流程控制器」**已过期** |
| G10 | 告警有钉钉/企微/Webhook 三渠道，但无电话/飞书、无升级链（escalation） | `OpsAlertDispatcher` |

### 2.2 三端客户端 — 成熟度：后台最厚、双端精炼

- **admin-vue**：约 60 路由页面、318 端点、70 view 组件，覆盖设备/交易/仓储/财务/增长/风控/RBAC/审批/告警规则/OTA/DevOps 中心，路由守卫 fail-closed。运营侧基本无功能空白。
- **consumer-mp**：主闭环完整（静默登录→扫码开门→识别购物/免密结算→申诉/退款/开票→余额/券/积分/消息）。缺口：**无分享能力**（`onShareAppMessage` 全目录 0 命中）、订单列表无关键字搜索、结算页不能主动选支付方式、**无商品浏览/详情页**（商品只出现在扫码后）、充值渠道依赖后端开关。
- **merchant-mp**：补货任务全生命周期、要货申请、定价、经营分析、结算提现、争议、团队管理，按功能包裁剪导航。缺口：经营分析**无图表可视化**（grep 无 echarts/canvas 命中）。

### 2.3 边缘端与视觉 — 成熟度：边缘工程扎实，识别是最大风险

- android-app：MQTT(TLS/离线队列/去重)、创智辉 M8 串口门锁驱动、CameraX 双摄录像、断网续传（离线队列+MinIO 断路器）、OTA 下载+SHA-256 校验（**无自动安装/分批/回滚**）。
- **vision-service：云端是 mock（`MockRecognizer`），端侧移远 OpenVending 集成为显式占位（`quectel_recognizer.py:36` "not configured yet"），阶段 A/B/C（端侧结果接入/监控/灰度）全部未实现。** DeepSeek 仅用于争议类名辅助。多摄融合有骨架未实测。
- 协议漂移：`proto/cabinet.proto` 与实际 JSON MQTT 消息不一致（SET_TARGET_TEMP/重力/分片未入 proto）。
- edge 端**零自动化测试**；`PrefsJsonQueue.kt:53` 同步 commit 主线程 ANR 风险未排除。

### 2.4 基础设施/可观测性 — 有骨架、缺末端

- 有：Prometheus + Grafana 看板 + 基础告警规则、Micrometer /actuator/prometheus、EMQX 内置认证+ACL（clientId=deviceId、no_match=deny）、Redpanda(Kafka)、MinIO、XXL-JOB。
- 缺：**Alertmanager 未部署**（业务级告警规则无人消费 —— 09-18 逐条复核精确化：`infra/prometheus/alert_rules.yml` 现 **22 条**规则，`prometheus-full.yml:5` / `prometheus.compose.yml:5` 确在 `rule_files` 里加载，**但三份 prometheus 配置都无 `alerting:` 段、全仓无 alertmanager service** ⇒ 规则**会评估、无人被通知**；Grafana 侧虽有 contact point（email → ops@aicabinet.local）与 notification policy，但 **alert rule 数为 0**、且 compose 未配 `GF_SMTP_*` ⇒ 邮件也发不出去，**两条链路末端都是断的**）、无日志聚合（Loki/ELK）、无 APM/错误追踪（OTLP exporter 无接收端）、边缘端无 metrics 上报。
- 🔴 **09-18 新发现（已修）：告警指标名静默失效。** Micrometer 把 `_total` 当作 **Counter 的保留后缀** —— Counter 追加、**Gauge 剥离**。用镜像内 micrometer 1.15.12 做最小复现（三类命名并列导出）：`gauge("cabinet.devices.total")` → **`cabinet_devices`**、`gauge("cabinet.devices.count")` → `cabinet_devices_count`、`counter("cabinet.door.open")` → `cabinet_door_open_total`。于是任何按 `xxx_total` 写的 **Gauge** 引用**静默失效且不报错**。实证受害面 4 处：`DeviceOfflineRateHigh`、`DeviceAllOffline`（**「全部设备离线」critical 告警**）、Grafana「设备总数」面板、运维页 2 张卡片。已改为 `cabinet.devices.count`（4 处源 + admin 产物重建 + 镜像重建后实测 `cabinet_devices_count 3.0`），并新增门禁 `check:prometheus-metric-names`（33 注册点 → 24 个有效名；A/B 9/9）。
- **`infra/monitoring/alerts.yml` 定论（原「未挂载的死配置」精确化）**：那 **14 条**规则**不是「被取代的旧版」**（与 `alert_rules.yml` 只 1 条重名，且连那条的指标名也是错的），而是**从未落地的设计草稿** —— ①从未被任何 `rule_files` 引用；②指标名与实现不符（`ai_cabinet_*` vs 实际 `cabinet_*`、`hikari_connections_*` vs 实测 `hikaricp_*`）；③依赖 blackbox / kafka / redis 三个**本仓从未部署**的 exporter（prometheus 只有 trade-service / device-service / minio 三个 job）⇒ **即便挂上也不会触发**。已加 `# gate: draft-unimplemented` 显式标记，由 `check:prometheus-metric-names` 的 R1 规则守着（既防它被误挂，也防新规则文件重蹈"写了没人加载"）。

### 2.5 既有审计未决项（截至 2026-09-17；**09-18 逐条复核后更正**）

> 更正方式：对源码 / 工作流**重新取证**，不采信旧文档有无「已修复」标注。结果：**3 条 P0 已于 09-18 当轮修复**（C-1/C-2/C-3、A-1）、**1 条（appid/urlCheck）判据修正**、**1 条（M-1/M-2/M-3）推翻但收窄为 2 个新缺口**、**1 条（Sonar）过期**、**新发现 1 条**（两端小程序 type-check 假绿）、1 条（V276）未实测。
>
> **09-18 第二轮（以下各条为最新状态）**：已落地 3 项 —— ① **appid / urlCheck 构建期注入点**（新增 `scripts/inject-miniapp-env.mjs`：写**构建产物**而不是源码）；② **坐标必填 + 无坐标拒签**（`UpsertDeviceRequest` 补经纬度字段、`createDevice` 落库并 fail-closed、`ReplenishmentService` 改 fail-closed，共 3 处 + 3 条新单测）；③ **两端 `type-check` 换 `vue-tsc`** 并清空 62 条既有类型错误。另发现 1 条**环境层假绿**（`script-shell`，**09-18 第三轮已更正为两个独立来源**，见下方「工具链假绿」）。
>
> **09-18 第三轮（A1/A2/A3 收口 + 2 条「让整栈起不来」的已提交缺陷）**：① **A1 闭环**——手改 `packages/shared-types/src/generated/openapi.ts` 的 `UpsertDeviceRequest` 与真 regen **逐字节一致**（块级 diff = 0），不在 CI 上制造漂移；但整文件差异另有归属，**`ci.yml:184` 门禁会在 zcode 提交时变红**；② **A2 已实证**——坐标回填完成，拒签 **8 个用例全部命中预期**（含 450m 通过 / 600m 拒签的边界钉子）；③ **A3 根因修正**——上一条只记了 `~/.npmrc`，实际有**两个独立来源**，且**只修用户级仍会假绿**；④ 另修 2 条**已提交、却会让服务一重启就崩溃循环**的缺陷（`XxlJobWiringSelfCheck` 缺 `@Autowired`；zcode 改写过 6 条已执行迁移导致 checksum 不符）。

> **09-18 第四轮（把「无人消费的过期脚本」接上门禁；提拔只活在 temp 里的资产）**：
> ① 🔴 **查出失效形态⑤的实例** —— `scripts/e2e-replenishment.ps1:142-156` 的签到步骤仍是**旧契约**（「柜机无坐标 ⇒ 发空 body 放行」），而服务端已 fail-closed ⇒ **柜机只要漏填坐标，脚本第 5 步必红**；该脚本**聚合链/CI/任何引用全 0 命中** ⇒ 改了契约没有任何信号。② **修法（三件套）**：第 5 步改为「契约用例（负向）+ 正式签到」，期望值集中到 `$CheckInContract` 真值块；新增离线**静态门禁** `check:replenishment-checkin-contract`（**已接入 `check:audit-gates` 第 2 位**）；新增实跑脚本 `scripts/e2e-checkin-contract.ps1`（期望值**读自**真值块，不复制）。③ **门禁判据设计**：真值 = `doCheckInTask` 闸门顺序 + `ApiMessages` 文案（脚本声明须为 Java 常量的**子串**且 ≥8 字）+ `SystemConfigService` 的 `max_distance_m` 默认值；外加「真值块必须**真被消费**」（设备侧文案要 **2 处**断言：空 body + **带合法坐标** —— 后者才是旧逻辑放行的那条路径）。④ **5 类漂移真注入全部转红**，每次按字节还原（`docs/evidence/.../static-gate-negative-validation.txt`）；⑤ 实跑 **5/5 通过**，含 2 个**防假绿对照组**（请求成功却期望被拒 / 状态码对但文案不符 ⇒ 助手必须报错）；⑥ 提拔 4 类只活在 temp 的资产（flyway repair 工具、实证日志、回填/回滚 SQL、坐标用例），隔离区 88 项已永久清空。详见 `2026-09-18.md` 第 ⑧ 段与 `docs/evidence/2026-09-18-replenishment-checkin/`。

> **09-18 第五轮（把第四轮的两条「未决」真正收口；第四轮那些红点已逐条转绿）**：
> ① ✅ **签到状态闸落地**：`ReplenishmentService.doCheckInTask:589-591` 头部拒 `COMPLETED`/`CANCELLED` → **409 `REPLENISHMENT_TASK_FINISHED`**（与开门 `DeviceValidationService.ensureRestockDoorAllowed` 共用一套状态集合与文案）。**第四轮那条「签到不校验任务状态 / CANCELLED 会被复活」已从「源码判读」升级为「运行期 A/B 实证」**：把 `doCheckInTask` 回退到修复前形态、重建镜像后跑 `scripts/e2e-checkin-contract.ps1` ⇒ **19 用例红 9 个**（CANCELLED 被写成 `IN_PROGRESS`、`COMPLETED` 的 `check_in_at/lat/lng` 被覆盖、柜机 `frozen_tasks` 0→2）；恢复修复形态再跑 ⇒ **19/19 绿**，且 13 列任务行快照 `BEFORE` 与 `AFTER_FIXED` **逐字段相同**（数据已还原）。证据：`docs/evidence/2026-09-18-replenishment-checkin/`（含 `e2e-checkin-contract.PREFIX.txt` / `.FIXED.txt` 与三份 `task_rows.*.tsv`）。
> ② ✅ **DTO 补 `deviceHasCoords`**：`common-core .../ReplenishmentTaskDto.java` 新增字段，由 `MerchantInventoryPortalService` 按 `DeviceLocationSupport.hasCoords(device)` 填充；同批以 **CI 同路径**重生成 `packages/shared-types/src/generated/openapi.ts`，满足 `ci.yml:184` 的「重生成后 `generated/` 无 git diff」。
> ③ ✅ **客户端收口（`clients/merchant-mp`）**：新增判据 `isDeviceCoordsMissing(task)`（`useReplenishmentFulfillment.ts:71-75`，判据与服务端同源 `deviceHasCoords === false`）；`replenishment.vue` 上签到按钮 `:disabled` 并给出「本柜尚未录入点位坐标」指路提示；dev-only「跳过定位」开关在无坐标柜机上**整块隐藏**（`:213-220`，避免与提示自相矛盾），文案由「跳过定位**验证**」改为「跳过定位**采集**」。**第三轮那条「客户端应明确提示并终止、而非允许无定位签到」已实现。**
> ④ ✅ **`withdraw-paying-timeout` 托管补齐 7 处**：`XxlJobManagedTasks.KEYS`、`@XxlJob("withdrawPayingTimeoutJob")` 处理器、`ScheduleZones.XXL_CRON_BY_TASK`、`ScheduleZones.MAX_SILENCE_BY_TASK`、`ScheduledTaskRegistry.register`、`V278__withdraw_paying_timeout_scheduled_task.sql` 种子行、`infra/xxl-job/seed_aicabinet_jobs.sql` id=132；`@Scheduled` **保留**（走 `tryBegin` 让位逻辑）。**运行期取证**：`scheduled_task` 行 `task_key=withdraw-paying-timeout`、`enabled=t`、`last_run_at=2026-09-18 07:50Z`、`last_result=SUCCESS`；XXL 库 `xxl_job_info.id=132` handler `withdrawPayingTimeoutJob`、cron `0 0/10 * * * ?`、`trigger_status=1`。**第三轮那条 `check:scheduled-task-seed` 红已转绿。**
> ⑤ 🔴 **V134/V135 已接地（**工作区未提交**）** —— 两条迁移被改成 `SELECT 1;`（档案头注明「演示柜种子不再保留」）。**后果两条**：(a) 已应用过旧版的环境**必须 `flyway repair`**；本机已对齐并实测启动日志 `Successfully validated 277 migrations`（两条 checksum 现同为 `-1254568443`）。(b) **本文档 §2.5 原先引用的「`V135` 按 `ROW_NUMBER()` 散点补齐 ⇒ 迁移跑完后不存在无坐标的种子柜机」这条覆盖率证据随之作废** —— 现在「柜机必有坐标」的保证只剩两条：**建档期必填**（`UpsertDeviceRequest` 含 lat/lng/address + `createDevice` 落库）+ **存量回填**（实测 `device_info` 3 台 `330449777078 / 777740024057 / CAB-001` **全部有经纬度**）。⚠️ 档案头称「该测试设备已从**真实环境**删除」，而本机 **dev 库 `device_info` 里 `CAB-001` 仍在**（且带坐标）——若「真实环境」专指生产则两者不矛盾，但**该注释极易被读成「本地也不再有这一行」**，建议改为不含歧义的表述（如「种子不再针对该柜机」）。**本轮未改动该迁移文件本身**，以免再次触发 checksum 漂移+repair。
> ⑥ ✅ **本机可信门禁复核（第五轮）**：`node scripts/run-audit-gates.mjs` ⇒ **聚合链 17 个门禁，失败 0 个**（含 `check:scheduled-task-seed`、`check:xxl-job-wiring`、`check:replenishment-checkin-contract`）。另新增三个一次性工具并落地：`scripts/devops/verify-checkin-gate-drift.py`（**18 例**，含 `expect_red=False` 的**防假红**对照组 D17：只改文案不改结构必须保持绿）、`scripts/devops/patch-checkin-gate-prefix.py`（`prefix`/`restore` 双模式 + **幂等守卫**，防止覆盖还原点）、`scripts/devops/verify-withdraw-timeout-hosting-drift.py`。

- **P0 残留（09-18 逐条复核 + 当轮修复状态）**：
  - ✅ **假 appid 已清除 + 构建期注入点已建（09-18 第二轮）**：原 `clients/consumer-mp/project.config.json:2` 与 `clients/admin-vue/project.config.json:23` 里的 `wx5a5bc7b541b62a13` **是假值**（用户已确认尚未申请）——它最坏的地方不是「假」，而是 `validate-miniapp-env.mjs` **分辨不出真假**，于是那条「appid 校验」对 consumer 侧是**假绿**（旧 `:76` 取 `manifestAppId || projectAppId`，有值即过）。处置：① consumer 该字段置 `""`，恢复 fail-closed；② `clients/admin-vue/project.config.json` 与 admin 端**毫无关系**（admin 是 vite/vitest 纯 Web，无任何 uni / mp-weixin 依赖，全仓无引用），属**死文件**，已删除；③ 真值改由新增的 `scripts/inject-miniapp-env.mjs` 在构建期从 `MP_WEIXIN_APPID_{CONSUMER,MERCHANT}` / `MP_WEIXIN_APPID` 注入**构建产物** `dist/{dev,build}/mp-weixin/project.config.json`（该目录已被 `.gitignore:16` 忽略，账号资产不会误提交）。⚠️ 因为 uni 会把 appid 写进产物（实测产物里是 `touristappid`），所以注入必须发生在 **`uni build` 之后**，不能改 `manifest.json` 了事。
  - ✅ **`urlCheck` 判据已从「源文件」移到「产物」（09-18 第二轮）**：源码必须长期 `false`（开发者工具要连 localhost/内网后端），发布期必须 `true`——同一文件放不下两种值，所以旧判据（`validate-miniapp-env.mjs:88` 要求**源文件**为 `true`）位置就是错的：它要么逼人把 `true` 提交进源码（破坏本地联调），要么只能被 `AICABINET_ALLOW_URL_CHECK_OFF=1` 绕过（**该 bypass 已删除**）。新判据在 `scripts/inject-miniapp-env.mjs`：**release 模式检查产物** `dist/*/mp-weixin/project.config.json` 的 `appid` 与 `setting.urlCheck`，不达标 `exit(1)`，并回读产物自检。这就是「按**有效值**判、按**最终产物**判」，而不是「按文件里写了什么判」。
  - **消费者端隐私授权声明缺失**：consumer-mp `src/manifest.json` 无 `__usePrivacyCheck__` / `requiredPrivateInfos`。⚠️ 但**隐私弹窗本身已实现**（`packages/shared-uni/src/components/privacy-consent-modal` + `privacy-consent.ts`，已挂 index/login），缺的只是微信侧声明开关与后台隐私协议配置——**勿当成「隐私功能未做」重做**。
  - ✅ **C-1/C-2/C-3 会话竞态三连 → 09-18 已修**（`clients/consumer-mp/src/pages/index/index.vue`）：
    - **C-1** 根因确认为 `restoreActiveSession()` 的 `if (sessionId.value) return;` 早退 + `onShow` 无任何重启路径。修法：新增 `resumeSessionPollingIfActive()`（sessionId 非空且状态非终态 → `startPoll()`），在 `onShow` 兜底调用**并**替换 `restoreActiveSession` 的早退分支。放在 `onAuthenticatedShow()` **之后**，因为该函数内部有 `resumeReopenDeviceFlow()/resumeBrowseDeviceFlow()` 的提前 return 分支会跳过 `restoreActiveSession`。
    - **C-2** 根因补充取证：`consumer-api.ts:704-715` 的 `createSession` 内部有「失败 → 等 600ms → 重试」，而请求层单次超时 `REQUEST_TIMEOUT_MS = 12s` ⇒ **底层请求最长约 24.6s 才有结论，而开门侧 `withTimeout` 在 20s 就放弃**（幽灵窗口确实可达）。同时服务端 `SessionService.java:121-128` 按 `idempotencyKey` **幂等回放**已有会话 ⇒ 迟到的会话是「可被接管」的。修法：`adoptOrphanSession()` 增加 ① 给在途 `createSession` 一个 `ORPHAN_GRACE_MS=5s` 宽限期，拿到会话直接接管；② 退避查询 `/sessions/active`（`[0,1000,2000]`ms）；③ `prepareDeviceForOpen()` 在 `busyReason === 'SESSION'` 时先做一次接管查询——否则「超时幽灵会话」会让用户既进不去（柜机正在被使用）也退不出。
    - **C-3** 修法：`beginCabinetEntry()` 前置 `rejectEntryWhenSessionActive()`：同柜机提示「购物进行中」并拦截，跨柜机弹确认框（查看订单 / 继续当前）后拦截。`canReopen`/`showDeviceCatalog` 本就要求 `!sessionActive`，故不误伤既有入口。
    - 另把「进行中状态集合」抽成单一常量 `SESSION_ACTIVE_STATES`，供 `sessionActive` / 接管判定 / 轮询恢复共用，消除三处字面量漂移。
  - ✅ **A-1 演示口令 → 09-18 已修**：`clients/admin-vue/src/views/LoginView.vue` 删除 `DEMO_LOGIN_PASSWORD` 与 `isDemoPhone`，`password = ref('')` 不再预填；同时把模板测试账号提示里的口令字面量也去掉（`v-if` 只保证不渲染，字符串仍会进 bundle）。⚠️ 后续若要加「生产 bundle 不含演示口令」门禁：**不能用 `grep 123456` 这种朴素子串匹配**——`clients/admin-vue/src/views/skus/SkuListView.vue:20` 的条码 `'6901234567890'` 就会误报。
- **❌ M-1/M-2/M-3「未见修复标注」已作废（09-18 更正）**：服务端门禁**2026-09-08 即已落地**（commit `20864a08`，**早于 09-15 审计**）—— `DeviceValidationService.java:143-145`（补货开门要求 `checkInAt != null`，否则 409）、`ReplenishmentService.java:590-604`（服务端系统参数 `replenishment.check_in.require_location` 控制能否无定位签到 = **审计 M-3 的建议原文**）、`:629-641`（haversine 距离校验，`replenishment.check_in.max_distance_m` 缺省 500m）、`:783-786`（`/complete` 要求 `checkInAt != null`），并有 `ReplenishmentCheckInLocationGatesTest` 4 例全绿。**残留缺口收窄为两点**：
  1. **设备无坐标时整条校验短路** —— `:590` 的条件是 `deviceHasCoords && !requestHasCoords`，且 `validateCheckInLocation:630-632` 在 `device.latitude/longitude` 为空时直接 `return` ⇒ 未采坐标的柜机可自由签到、无地理围栏。**坐标种子覆盖率已取证（09-18）**：~~`V134`+`V135` 末段是 `UPDATE device_info ... WHERE latitude IS NULL`（`V135__seed_more_device_map_coords.sql:19-31`，按 `ROW_NUMBER()` 散点补齐）⇒ **迁移跑完后不存在「无坐标的种子柜机」**~~ 🔴 **此结论 09-18 第五轮作废**：`V134`/`V135` 已被改成 `SELECT 1;`（档案头注明「演示柜种子不再保留」，checksum 已 repair 对齐），**迁移不再补任何坐标**。现行保证改为两条：**(a) 建档期必填**（`UpsertDeviceRequest` 含 `latitude/longitude/address`）、**(b) 存量回填**（实测 `device_info` 3 台全部有经纬度）。✅ **「档案头与事实不符」已判定（09-18 复核）**：两句话说的是**两个环境**，并非矛盾——档案头说的是**真实环境**（该测试柜已撤除），而**开发/演示环境必须保留 `CAB-001`**：它是整条开发工具链的默认演示柜机（`seed-demo-data.ps1`、`run-api-tests.ps1`、`e2e-nearby.ps1`、`start-local.ps1`、`verify-local.ps1`、`phase-f-gray-launch.ps1`、`full-round-dv06-perf1.mjs`、`create-open-dispute.ps1`、`security-concurrency-recon-test.ps1`，外加 `clients/{consumer,merchant}-mp/tests/*-h5-uat.mjs` 与 `DeviceSimulator`/`EdgeRuntimeConfig` 的默认 ID）。它的 `device_info` 基础行由 `V2__user_order_sku.sql:55` 建立，运行期由 `DemoDataService.ensureDemoData()` **幂等**补齐（`ensureDevice()` 建档即带 31.2304/121.4737，且经纬度为空时自动回填 `:193-200`；`ensureDeviceInventory()` `:206-228` 等价原 `V25`）⇒ 被归档的 8 处种子在代码里有**更好的归属地**，归档本身是安全的。🔴 **结论：`CAB-001` 不得删除**（删掉会打断上述 ~12 个脚本 + 两端 UAT + 模拟器默认 ID）；原「注释与事实不符，待更正」的判断**作废**。唯一没被 `DemoDataService` 承接的是 `sales_locked` 解锁（原 `V253`），现行口径是 `DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED` 默认 false，UAT 对该情形记 SKIP。而坐标在设备新建/编辑时**是可选项**（`OpsDeviceAdminService.java:486-491` 仅在非 null 时写入），无坐标柜机还会从设备地图里消失（同文件 `:506` 过滤掉经纬度为空的设备）。⇒ 兜底策略二选一：**把坐标做成必填**，或**把「无坐标 → 跳校验」改成「无坐标 → 拒签并提示补坐标」（fail-closed）**。**此条需产品决策，勿直接改校验逻辑。**
  2. **客户端不是「没接开关」，而是「不知道柜机有没有坐标」（09-18 更正）** —— `clients/merchant-mp/src/composables/useReplenishmentFulfillment.ts:89` **已经在读** `requireReplenishmentCheckInLocation`（该开关为 false 时整段跳过定位），`:92-95` 另有人工跳过位；真正缺的是客户端拿不到「柜机已配坐标」这个事实——柜机无坐标时服务端不强制定位（`:590`），客户端却在 `:102-122` 两次定位失败后终止 ⇒ **无坐标柜机的补货员会被自己的客户端卡死**。修法：任务 / `/me` DTO 增加 `deviceHasCoords`，为 false 时允许无定位签到。⚠️ **09-18 第二轮后语义已变**：服务端现在「柜机无坐标 → 直接拒签」，所以客户端的正确处理不再是「允许无定位签到」，而是**明确提示「本柜未录入点位坐标，请联系运营补录」并终止**（避免补货员在柜前反复重试定位）。此项仍未实现。
- **P2**：V276 迁移兜底默认值需镜像重建才生效（`V276__device_auto_unlock_stable_minutes_15_to_5.sql`），遵循「先重跑 seed + 再重建镜像」成对规则。
- 🔴 **新发现（09-18）：两端小程序页面的类型门禁是「假绿」，真跑一次就露 25 条既有类型错误** —— `clients/{consumer-mp,merchant-mp}` 的 `type-check` 是 **`tsc --noEmit`**，而 `tsconfig.json` 虽 `include` 了 `src/**/*.vue`，**tsc 不解析 `.vue`、会静默跳过**（需要 `vue-tsc`；admin-vue 用的正是 `vue-tsc`）。**负向验证**：往 `clients/consumer-mp/src/pages/index/index.vue` 顶部注入 `const __probe__: number = 'x'`，`tsc --noEmit` 仍 **exit 0 且零诊断**（已还原 + grep 回验）⇒ 整个页面层没有类型检查，而 CI 的 `test:mp` → `test:ci` 依赖它。**换 `vue-tsc` 实跑**（借 admin-vue 的 vue-tsc 指向 consumer-mp）：**25 条 `error TS`、散布 9 个 `.vue`**（`index.vue` 8、`points/redeem.vue` 8、`points/points.vue` 7、`messages.vue` 3、`coupons.vue` 3，`verify/help/member/order-detail` 各 1）。⇒ ① 本轮 C-1/C-2/C-3 的改动**不能**拿 `pnpm type-check` 当类型证据——已改用 vue-tsc 单独核过：`index.vue` 那 8 条全在 `:176/179/247/278/313/327/1514/1524`，**无一落在我改动的行段**；② 「把 `type-check` 换成 `vue-tsc --noEmit`」不是一行改动，得先把这 25 条清掉——**建议单列整改项，不要混进 P0 阻塞上线**。
  **✅ 09-18 第二轮已收口**：`vue-tsc@2.2.12` 已进两端 devDependencies，`type-check` 改为 `vue-tsc --noEmit`；62 条错误（consumer 33 / merchant 29；含 `.vue` 内 25 条 + `packages/shared-types` 引用漂移一批）**全部修净**，两端 `vue-tsc --noEmit` 均 **exit 0**。**负向验证**：临时插入 `clients/consumer-mp/src/__typec_probe.vue`（`const n: number = 'not-a-number'`）后 `vue-tsc --noEmit` **exit 2** 并报 `__typec_probe.vue(3,7) TS2322` / `(4,7) TS2322` ⇒ 这条门禁对 `.vue` 真的会红（同文件在旧 `tsc` 下 exit 0 且零诊断）。顺带修掉一批**真实缺陷**而非纯类型噪声：`merchant-api.ts` 把 `/api/v2/merchant/devices` 的返回标成 `DeviceInfo`/`AdminDeviceDto`（实为 `MerchantDeviceDto`，导致 `devices.vue` 读 `oosSlotCount`/`lifecycleStatus` 整个错位）、`disputes.vue` 引用列表 DTO 上不存在的 `videoUri/videoPreviewUrl`（死条件）。
- **❌ Sonar workflow 仍挂 `on.push`（自欺信号）→ 已过期（09-18 更正）**：`.github/workflows/sonar.yml:9-10` 现**仅 `workflow_dispatch`**，无 push / pull_request；文件头 `:6-7` 已自述按审计 §25.9.4 收敛（「push/PR 触发的 job 会永远排队」）；最近改动 `6415a329 2026-09-17`。
- 🔴 **工具链假绿：`pnpm run` 在本机 **exit 0 但什么都不跑**（09-18 第三轮；根因两处，互相独立）** —— 症状：`pnpm run <script>` 只回显命令行横幅、**不执行脚本**，却返回 **exit code 0**。根因是 `script-shell` 指向**非批处理**的 Windows 可执行文件：pnpm 内部只对 `.bat`/`.cmd` 后缀走批处理包装（`isWindowsBatchFile`），`*.exe` 会被按 POSIX 约定拼 `-c`，cmd 拿不到可执行命令。
  - ① **用户级持久环境变量** `npm_config_script_shell = C:\Users\cwx\.cursor\mcp-servers\no-window.exe`（**优先级高于 `.npmrc`**）——已于 09-18 删除；`HKCU\Environment` 与 `HKLM\SYSTEM\...\Environment` 现均为空（**实查**）。当前会话里仍能读到该值属**宿主进程启动时继承的陈旧值**，**须重启宿主应用**才消失。
  - ② **项目 `.npmrc:5` `script-shell=cmd.exe`**（提交 `cf052a9d`，2026-08-31）——它**本身就坏**：2×2 实测「有/无 `.npmrc` × 有/无环境变量」四种组合**只有「两者都无」才真的 RAN**，其余三组全 NO-RUN。该行注释自称「项目内强制 cmd」，但 `cmd.exe` 同样不被批处理包装接受 ⇒ **纯负收益**（干净环境下它反而**制造**故障）。**已删除该行**（`git diff .npmrc` = 2 删 0 增）。
  - 🔴 **放大效应**：`pnpm check:audit-gates` 本体就是一串**嵌套 `pnpm run`**。链式实测：外层 **exit 0**、内层**从未执行** ⇒ **整条门禁链可以「全绿」而没跑任何一条判据**。**处置**：本机复核门禁改走 `node scripts/run-audit-gates.mjs`（不经 `pnpm run`），宿主重启后再复核 `pnpm run type-check`。
  - **负向验证（证明门禁真的会红）**：删掉 `.npmrc` 该行后，注入 `clients/admin-vue/src/__typecheck_probe__.ts`（`const __probe: number = "…"`）→ `pnpm run build:admin:typecheck` **exit 2** 并报 `error TS2322`；撤掉探针 → 恢复 exit 0。**同一命令在修复前是 exit 0 且零诊断。**
- ✅ **无坐标拒签 · 实证（09-18 第三轮，`ReplenishmentService.doCheckInTask:584-619`）** —— 闸门顺序：`assertTaskHasFulfillableWork` → **设备无坐标拒签** → **请求无坐标拒签** → haversine `max_distance_m`。
  - **Phase1（回填前；柜机 `777740024057` 坐标为空、任务 #2 `PENDING`）**：空 body → 400「本柜尚未录入点位坐标…」；**带柜前**合法**坐标 → 仍 400 同一文案**（← 旧逻辑 `deviceHasCoords=false` 会直接放行，这正是被修掉的**静默失效**）；重复一次仍 400；任务保持 `PENDING`、`check_in_at` 为 NULL。
  - **回填**：`_backfill_device_coords.sql` 幂等守卫 `latitude IS NULL OR longitude IS NULL`，实测 CAB-001 `UPDATE 0`（已有坐标，**守卫有效**）、其余各 `UPDATE 1`；只写 `latitude/longitude/address`，不动 `route_code`。现 3 台柜机均有坐标。
  - **Phase2（回填后）**：空 body → 400「请开启定位后到柜前签到…」；北京坐标 → 400「约 **1066782** 米，超出 500 米」；正北 **600m** → 400 超范围；正北 **450m** → **200**（`checkInDistanceM=449.99`）；柜机原点 → **200**（`checkInDistanceM=0.0`，`check_in_lat/lng` 落库、任务转 `IN_PROGRESS`）。**边界被钉在 450/600 之间 ⇒ `max_distance_m` 真被消费，不是「配了不用」。**
- ✅ **A1 闭环：生成类型已与运行态对齐（09-18 第三轮）** —— 用 **CI 同路径**（`node scripts/gen-openapi-types.mjs`，**不经 `pnpm run`**）以运行镜像的 `/v3/api-docs`（530 paths）重生成：
  - 手改块 `UpsertDeviceRequest` 与真 regen **块级 diff = 0**（属性 `deviceId/deviceName/deviceType/merchantId/latitude/longitude/address`，含 `/** Format: double */` 注释）。
  - 整文件 vs `HEAD` 共 **+49/−1**：**5 行是本轮改动**（`lat/lng/address`），**44 行是 zcode 尚未重生成的在飞改动** —— 新增 `POST /api/v2/ops/admin/rbac/me/phone-sms-code`（`OpsRbacController.java:235`）与 `GET /api/v2/merchant/wallet` 新增 `merchantId` query（`MerchantPortalController.java:664`，HEAD 版签名为 `wallet(HttpServletRequest)`）。
  - ⚠️ **`ci.yml:184` 的 `OPENAPI_CHECK_REGEN=1 … pnpm check:openapi-types` 会「重生成 + 要求 `packages/shared-types/src/generated/` 无 git diff」⇒ zcode 提交这两个控制器却**不带**重生成的 `openapi.ts`，CI 必红。**已重生成 `openapi.ts`（现与运行态一致），须与控制器**同批提交**。
  - 别名组文件（`order-models.ts` 等 7 个）重生成后 **无 diff**，未见附带漂移。
- 🔴 **两条「已提交、却让服务一重启就崩溃循环」的缺陷（09-18 第三轮，均已修）**：
  - **`XxlJobWiringSelfCheck` 缺 `@Autowired`**：该类有两个构造器（一个 `@Value` 注入、一个供测试用的包私有），**均未标 `@Autowired`** ⇒ Spring 转而查找无参构造而失败，`BeanCreationException: No default constructor found`。缺陷一直潜伏，因为 `application.yml` 里 `XXL_JOB_ENABLED` 默认 **false**、且测试**全用 `new` 构造器**（整条测试链路**绕过该 bean 的创建**）；而 `infra/docker-compose.apps.yml:110` 把它设为 **true** ⇒ 生产/本地栈**一重启就崩溃循环**（实测 `Restarts=13`）。已加 `@Autowired`，重建镜像后 `healthy=True, Restarts=0`，日志 `xxl-job executor wiring self-check PASSED … 托管任务 30 个`。
  - **已执行迁移被改写**：zcode 给 `V202/V244/V252/V253/V254/V262` 加 `${seed_env}` 守卫，改动**已执行**迁移的 SQL 会让 `validate-on-migrate` 启动即拒服。已写 `Repair.java` 调 `flyway.repair()` 对齐（**恰好 6 条 checksum 变更、0 行丢失**；二次 repair 命中 0 条以证明已对齐）。新增的 `V277__site_rent_bill_uk_fix.sql` 随本次重启成功应用。
- 🔴 **zcode 提交前必须补的两处（否则一 commit CI 必红；09-18 第三轮实测）**：
  - **① 生成类型漂移** → `ci.yml:184`，见上一条（已重生成，须同批提交）。
  - **② `check:scheduled-task-seed` 现为红** —— 工作区在 `ReconciliationScheduler.java:16,84` 新增常量 `WITHDRAW_PAYING_TIMEOUT="withdraw-paying-timeout"` 并调用 `tryBegin(…, 600)`，但**既无 `scheduled_task` 种子行、也无 `ScheduledTaskRegistry` 注册**（**HEAD 版只有 `tryBegin(RECONCILIATION, 1800)` 一处**，`git status` 该文件为 `M`）⇒ 本机聚合链实测 `16 个门禁，失败 1 个`。按本项目既有规则须**三选一**：补种子行 / 注册 / 加入 `check-scheduled-task-seed.mjs` 的 `LOCK_ONLY_TASKS` 并写明理由（**属产品决策，勿凭印象改**；见 `MEMORY.md` §10.5–§10.7）。⚠️ 该失败**与本轮 A1/A2/A3 改动无关**。
- ✅ **本机可信门禁复核（09-18 第五轮，`node scripts/run-audit-gates.mjs`，不经 `pnpm run`）**：聚合链 **17 个门禁，失败 0 个**——第四轮那条 `check:scheduled-task-seed` 红已随 `withdraw-paying-timeout` 托管补齐而转绿（见 第五轮 ④）。容器终检 `{"status":"UP"}`、`restarts=0`。
  - ~~**（第四轮快照，已过期）** 聚合链已扩到 17 个门禁，仍只失败 1 个——即 `check:scheduled-task-seed`（zcode 在飞改动）。~~

- ✅ **`Repair.java` / `ValidateCheck.java` 已提拔进仓库**（`scripts/devops/flyway-repair/` + `README.md`），并已参数化（`-Ddb.url` / `-Dseed.env` / `-Dflyway.locations`，相对路径可在仓库根直跑）；实跑 `REPAIR_OK` + `VALIDATE=PASS`。⚠️ **更正**：原 `ValidateCheck.java` 用的 `ignorePendingMigrations(true)` 在 Flyway 9.22.3 的 `FluentConfiguration` 上**不存在** ⇒ 它**从未编译/运行过**，「用它证明 checksum 对齐」的说法此前**不成立**；正确 API 是 `ignoreMigrationPatterns("*:pending")`。
- ✅ **签到不校验任务状态 —— 09-18 第五轮已修 + 运行期 A/B 实证**（原记录：`ReplenishmentService.doCheckInTask` 只校验「有无履约明细 + 两个坐标闸」，末尾 `if (!IN_PROGRESS && !COMPLETED) setStatus(IN_PROGRESS)` ⇒ **`CANCELLED` 任务会被签到"复活"成 `IN_PROGRESS`**；对 `COMPLETED` 任务则覆盖 `check_in_at/lat/lng`。而 `DeviceValidationService.ensureRestockDoorAllowed` 明确拒这两个状态 ⇒ 两处口径不一致，**源码判读、未实测**）。
  **修法**：在 `doCheckInTask` 头部（`requireTaskForUpdate` 之后）加终态闸，`COMPLETED`/`CANCELLED` → 409 `ApiMessages.REPLENISHMENT_TASK_FINISHED`，与开门路径**共用同一套状态集合与文案**。
  **A/B 实证**（`scripts/e2e-checkin-contract.ps1`，19 用例，双通道：API 列表 + 直读 DB 行值）：
  - **修复前形态**（`doCheckInTask` 回退 → 用 `scripts/devops/patch-checkin-gate-prefix.py prefix` → 重建镜像）⇒ **红 9 个**：CANCELLED 被写成 `IN_PROGRESS`；COMPLETED 的 `check_in_at/lat/lng` 被覆盖（状态不变，只查「有没有值」的弱断言会漏，故改为**整行逐字段比对**）；柜机 `frozen_tasks` 从 0 变 2。
  - **修复后形态**（`…restore` 还原）⇒ **19/19 绿**；`task_rows.AFTER_FIXED.tsv` 与 `task_rows.BEFORE.tsv` **逐字段相同**（已还原运行期数据）。
  - 证据目录：`docs/evidence/2026-09-18-replenishment-checkin/`。
- ⚠️ **`scripts/e2e-replenishment.ps1` 第 3 步在本机跑不过（09-18 第四轮实测）**：`POST /ops/admin/replenishment/plan` 对两台柜机分别返回 `400 当前无补货缺口` / `500 系统繁忙（追踪号 d70af7c71542）`，`Prepare-E2eReplenishmentPlan` 恒输出 `no gaps`。**第 3 步与 `HEAD` 逐字节相同、与本轮改动无关**，但它**解释了脚本为什么会悄悄过期**——本机根本走不到第 5 步。
- 生产清单 P0：真实支付进件、真实短信、真实柜机协议联调、≥1000 次拿放识别测试、关停全部 mock 入口、密钥管理系统、备份恢复演练。

---

## 3. 竞品对标

### 3.1 对标对象

| 竞品 | 类型 | 关键能力 |
|------|------|----------|
| 友宝在线（UBOX） | 头部运营商 | 智能货柜/售货机/咖啡机矩阵、云平台运营、广告与营销服务、点位合伙人（购买/租用设备分成） |
| 丰e足食（顺丰） | 直营运营商 | 算法驱动近场景运营（单柜月均约 908 元）、**批量入驻美团获取线上流量**、办公室场景企业福利 |
| SandStar 视达 / 小麦便利 / 百度智能柜 | 视觉方案商 | 动态视觉识别（视频流、随取随放）、边缘算法、宣称识别率 ~99.5%、刷脸/扫码开门 |
| 映翰通 InVending Cloud 等 | 售货柜 SaaS | 设备监控告警、按运营数据调整商品组合与定价、动态定价/时段定价/临期清仓定价 |
| 美团生态 | 流量平台 | 智能零售终端入驻平台导流（丰e足食案例）、分润合作模式 |

行业盈利结构共识（东方财富《中国无人零售行业调研简报》）：**商品销售 + 设备销售/租赁 + 数据服务 + 广告营销** 四大板块——本项目第 1、3 板块已强，第 2、4 板块缺失。

### 3.2 功能差距矩阵

| 能力维度 | 头部竞品 | 本项目 | 结论 |
|----------|----------|--------|------|
| 开门方式 | 扫码 + 刷脸（蜻蜓/支付分） | 仅扫码 | **落后** |
| 识别技术 | 动态视频流、随取随放 | 端侧未落地、云端 mock | **最大差距** |
| 免密支付/先享后付 | 标配 | PayScore + 余额预授权 | **持平偏领先** |
| 余额/储值 | 储值卡/充值赠送营销 | 有余额充值，无储值营销（充值赠送/储值等级） | 小幅落后 |
| 会员积分 | 标配 | 等级/倍率/过期/兑换闭环 | 持平 |
| 营销玩法 | 拼团/秒杀/签到/裂变 | 仅券+活动+召回 | **落后**（且历史上有意删过，需产品决策） |
| 动态定价/临期折扣 | 时段定价/临期自动折扣 | 手工调价 | **落后**（有库存批次+临期告警底子） |
| 屏幕广告变现 | 四大盈利板块之一 | 有素材/投放/曝光上报，无广告主/计费/结算 | 半成品 |
| 平台流量接入 | 丰e足食入驻美团 | 无 | 空白（商务问题>技术） |
| 企业 B 端 | 办公室场景福利采购 | 无 | 空白 |
| 供应链/仓配 | 一般为进销存 | 采购+仓储库位+盘点调拨+批次+路线优化+智能补货 | **领先** |
| 分账/商户资金 | 基础分账 | 微信服务商分账全链路+钱包+对账 | **领先**（除提现打款 G1） |
| 设备租赁计费 | 设备销售/租赁盈利 | 无 | 空白 |
| 数据分析 | SaaS 标配报表 | 客流/热区/坪效/ROI/用户分析/选品诊断/AI 洞察 | **持平偏领先** |
| 可观测性 | SaaS 标配监控告警 | Prometheus/Grafana 有，Alertmanager/日志/APM 缺 | 小幅落后 |

### 3.3 点位坐标是主数据，不是备注（坐标必填 + 无坐标拒签 的依据）

补货 / 选址类竞品的能力链条**无一例外从「标点位」开始**：

| 环节 | 竞品做法 | 推论 |
|------|----------|------|
| 点位建档 | 新增点位时即登记经纬度 + 地址，作为后续一切计算的主键 | 坐标属**主数据**，与设备编号同级，不是可选备注 |
| 补货路线优化 | 先把「所有点位在地图上标好」，再按距离 / 路顺自动排线（本项目已有 `RoutePlanningService`） | 缺坐标的点位**进不了路线优化** |
| 签到 / 到店核验 | 到柜前一定范围内才允许签到（地理围栏） | 缺坐标 ⇒ 围栏**必然失效** |
| 选址评估 | 按点位周边步行 3 分钟竞品密度打分 | 坐标是评估的必要输入 |

因此本项目取：**坐标必填（建档时） + 无坐标拒签（签到时 fail-closed）**。
不取「无坐标就跳过校验」——那不是「宽松策略」，而是**静默失效**：谁在建柜时漏填坐标，谁的柜机就永久免定位，
而且**界面上看不出来**（唯一微弱信号是 `OpsDeviceAdminService.listDeviceMapPoints:506` 把该柜从设备地图里悄悄过滤掉）。
落地时还发现根因比预判更严重：`UpsertDeviceRequest` 原先**连经纬度字段都没有**，`createDevice` 自然也从不写入
⇒ 坐标不是「可能为空」，而是**经后台新建的柜机必然为空**（§2.5 有取证）。

---

## 4. 计划：P0 前置项（先于一切新功能）

> 这些不是竞品差距，而是「能不能真实收费上线」的问题。建议排在任何新功能之前。

| 项 | 内容 | 对应缺口 |
|----|------|----------|
| P0-1 | **端侧识别落地**：移远 OpenVending 阶段 A（`/internal/v1/vision/edge-results` 端侧结果接入结算）→ B（准确率监控/边缘盒监控）→ C（试点灰度）；≥1000 次拿放组合测试；低置信→人工审核规则定稿 | G0 生死线 |
| P0-2 | **提现打款闭环**：接入微信商家转账 API，**商户/线长两端成对**（`MerchantWithdrawPayoutService.java:47` + `LineWithdrawPayoutService.java:47`，同族缺陷各一处），打款失败回滚+重试+对账 | G1 |
| P0-3 | **支付/短信进件**：真实微信/支付宝商户号与回调、关闭全部 mock 支付/短信/识别/测试余额入口；PayScore 签约-解约全流程补全 | G9 + 生产清单 |
| P0-4 | **小程序发布合规（09-18 第二轮更新）**：① ✅ **构建期注入点已建** —— 新增 `scripts/inject-miniapp-env.mjs`：release 模式把 `MP_WEIXIN_APPID_{CONSUMER,MERCHANT}`（或 `MP_WEIXIN_APPID`）与 `urlCheck=true` 注入**构建产物** `dist/*/mp-weixin/project.config.json`；缺 appid、或显式 `MP_WEIXIN_URL_CHECK=false`，一律 `exit(1)`，并回读产物自检。`validate-miniapp-env.mjs` 相应改为「env appid 优先 + 只拦自相矛盾的 urlCheck」，**删除了 `AICABINET_ALLOW_URL_CHECK_OFF` 这个 bypass**。两端 `build:mp-weixin` / `build:mp-weixin:dev` 已接线 ⇒ **appid 申请下来后零改代码**；② ✅ 假 appid `wx5a5bc7b541b62a13` 已从 consumer 置空，`clients/admin-vue/project.config.json`（死文件）已删；③ ⏳ **appid 本体仍待申请**——未申请前 release 构建必然红灯，这是**正确的 fail-closed**，**不要误判为「构建坏了」**；④ ⏳ 隐私授权声明（补 `__usePrivacyCheck__`；弹窗组件已有，勿重做）；⑤ ⏳ 域名白名单。⚠️ 源码里的 `urlCheck` **必须长期保持 `false`**（开发者工具要连 localhost/内网），置 `true` 由注入步骤写进产物 | 三端审计 §26.6 |
| P0-5 | **三端审计 P0 残留（09-18 第二轮后）**：① ✅ **C-1/C-2/C-3 会话竞态三连已修**（`consumer-mp/index.vue`：`onShow` 轮询恢复 + 孤儿会话宽限接管 + 重入拦截，详见 §2.5）；② ✅ **A-1 演示口令已修**（`admin-vue/LoginView.vue`）；③ ✅ **坐标必填 + 无坐标拒签已落地**（`UpsertDeviceRequest` 补 `latitude/longitude/address` → `OpsDeviceAdminService.createDevice` 落库且「选商户=部署」必填 → `ReplenishmentService` fail-closed，含 3 条新单测做负向证明；**09-18 第三轮已端到端实证 8 用例**：回填前「设备无坐标」闸对**带合法坐标的请求也拒**，回填后 450m 通过 / 600m 拒签，任务态与 `check_in_lat/lng` 落库均已核）；④ ✅ **09-18 第四轮已把契约接上「消费点」**：新增静态门禁 `check:replenishment-checkin-contract`（已入聚合链，5 类漂移真注入全转红）+ 实跑脚本 `scripts/e2e-checkin-contract.ps1`（5/5 通过，含 2 个防假绿对照组），并修掉 `e2e-replenishment.ps1` 第 5 步的旧契约（原「设备无坐标 ⇒ 发空 body 放行」必红）；⑤ ✅ **已闭环** —— 客户端 `deviceHasCoords` 早已实现（`merchant-mp/src/composables/useReplenishmentFulfillment.ts:71-75` `isDeviceCoordsMissing`、`replenishment.vue:443` 按钮 `:disabled` + 指路提示、DTO 字段 + `generated/openapi.ts` 回填），「无坐标」分支已从「允许无定位签到」改为**明确提示缺坐标并终止**。🔴 **本条原标 ⏳ 属「假未决」——09-18 复核更正为本轮第二例过期自报状态** | 三端审计 §2.5 |
| P0-6 | **可观测性收口**：部署 Alertmanager 消费既有业务规则（09-18 复核：`infra/prometheus/alert_rules.yml` 已 **22 条**，且三份 prometheus 配置**都缺 `alerting:` 段** ⇒ 部署 Alertmanager 之外**还须补 `alerting:` 指向它**，并把 dev 用 `prometheus.yml` 也补上 `rule_files`），**另需给 Grafana 侧补 alert rule 或删掉其空转的 contact point/policy**（现为 0 条规则，且无 `GF_SMTP_*`）；`infra/monitoring/alerts.yml` 那 **14 条**已**定论为未实现草稿**（指标名与实现不符 + 依赖未部署的 exporter）⇒ **不挂，加 `# gate: draft-unimplemented` 标记**，由 `check:prometheus-metric-names` 的 R1 守着；Loki 日志聚合；OTLP 接收端（Tempo/Jaeger）；业务 KPI 看板（开门成功率、关门完整率、结算时长、识别准确率、争议率、MQTT 转发失败）。⚠️ 通知渠道凭据（SMTP / 机器人 webhook）需外部提供，在此之前"链路通、消息落日志" | §2.4 |
| P0-7 | edge 端基础测试与风险排除：`PrefsJsonQueue` 主线程 commit、MQTT 集成测、模拟器↔device↔trade 契约测试 | 三端审计 |

## 5. 计划：建议新增的功能（缩小增长差距，按优先级）

### P1（直接提升单柜产出，1-2 个迭代量级）

| # | 功能 | 说明 | 对标 |
|---|------|------|------|
| F1 | **动态定价/促销引擎** | 价格策略层：时段定价（如午高峰）、临期自动折扣（联动既有库存批次/临期告警 `ExpiryAlertScheduler`）、库存清仓折扣、策略版本与审计；复用商户定价乐观锁框架 | 映翰通/台湾案例：时段+临期+清仓定价是 AI 柜 SaaS 标配 |
| F2 | **刷脸开门** | 支付宝「蜻蜓」/微信刷脸 SDK 对接，作为扫码之外的免密开门方式；与既有免密代扣/预授权打通 | 视达/百度/友宝标配 |
| F3 | **裂变与分享** | 小程序分享（onShareAppMessage/Timeline）、邀请有礼（邀新得券）、分享领券；注意 V128 曾删 invite 模块，建议轻量重建（券激励，不做资金级裂变） | 竞品标配；本项目 0 命中 |
| F4 | **屏幕广告变现闭环** | 在素材库+投放计划+曝光上报基础上补：广告主/订单管理、CPM/CPC 计费、广告主投放报表、广告收入入账（可挂到 RevenueSplit 或独立账本） | 盈利板块之一 |
| F5 | **储值营销** | 充值赠送（充 100 送 10）、储值余额优先支付、储值等级；复用 BalanceLedger 幂等账本 | 竞品标配 |
| F6 | **消费者体验补齐** | 订单关键字搜索、结算页支付方式选择、商品浏览/详情页（开门前可看柜内商品）、券包入口前置 | 前端审计 |

### P2（构建壁垒与第二曲线，3+ 迭代）

| # | 功能 | 说明 | 对标 |
|---|------|------|------|
| F7 | **企业 B 端** | 企业团购/福利采购：企业账户、批量下单/开票、月结账期、对公支付；办公室场景竞品的核心打法 | 丰e足食 |
| F8 | **平台流量接入** | 美团/饿了么/京东到家类平台入驻适配层（商品/库存/订单/核销同步）；技术先做适配层抽象，商务另谈 | 丰e足食×美团 |
| F9 | **设备租赁计费** | 对商户的设备租赁/分期/押金管理（收费侧，区别于既有 SiteRent 付款侧），支撑「设备即服务」盈利 | 友宝点位合伙人 |
| F10 | **加盟/点位合伙人体系** | 评估重建（V130 曾删）：合伙人招募、点位分润、自助后台；先做产品决策再动代码 | 友宝 |
| F11 | **营销玩法扩展** | 限时秒杀（开门购物场景下的时段价+限量）、第二件半价/满减阶梯（价格引擎 F1 的上层玩法）、签到积分（游戏化，注意 V131 曾删的历史） | 竞品标配 |
| F12 | **发票真实开票** | 对接航信/百望/诺诺等开票服务商，自动开具 + 红冲 | G2 |

## 6. 计划：现有功能优化升级

| # | 优化项 | 现状 → 目标 |
|---|--------|-------------|
| O1 | **识别准确率保障体系** | 有 need_review/争议熔断 → 增加模型版本管理、灰度回滚、准确率看板（识别 P95、置信度分布、need_review 率、争议率按模型版本对比）；争议仲裁引入 DeepSeek 图片级辅助（现仅 OCR+文本） |
| O2 | **OTA 完整化** | 下载+SHA-256 → 静默安装（PackageInstaller）、分批灰度发布、失败回滚、升级进度上报 |
| O3 | **告警升级链** | 钉钉/企微/Webhook → 增加 P0 告警电话/SMS 升级链、值班表联动（配合争议 SLA 值班表 P1 项） |
| O4 | **协议治理** | `cabinet.proto` 与实际 JSON 消息漂移 → 以实际协议为准刷新 proto 或冻结 proto，加「协议契约测试」进 CI。**09-18 取证加严**：`proto/cabinet.proto` **不被任何构建引用**（根 pom / 各模块 pom / gradle 里 `proto\|protobuf\|protoc` 零命中）⇒ 它是**无代码生成、无消费**的纯文本契约（「没人调」形态）；实际协议是 `CabinetConstants` 的 5 个 JSON/MQTT 命令（`OPEN_DOOR`/`SET_TARGET_TEMP`/`LOCK`/`UNLOCK`/`REBOOT`），与 proto 的 3 个 oneof（open_door/force_close/ota_upgrade）**互不覆盖**。⚠️ 落地路径可行：`edge/device-simulator` 是**根 pom 的 Maven 模块**（`mvn -pl edge/device-simulator test` 可跑），契约测试有真实落点 |
| O5 | **商户经营分析可视化** | merchant-mp 纯数字 → 引入轻量图表（如 ucharts）补趋势/构成图 |
| O6 | **余额退款自动化** | 纯人工审核 → 小额（阈值可配）自动原路退回 + 风控联动（黑名单/新号限制），大额仍人工 |
| O7 | **测试资产还债** | trade 202 单测但 god service 分支不全、device 仅 2 测试、edge 0 测试 → 按 CODEBASE_FOUNDATION §10 优先级补开门竞态/结算置信度/回调幂等决策表测试；Testcontainers 进 CI 不跳过 |
| O8 | **性能基线** | 无压测数据 → jmeter 已在仓库根，做开门/结算/轮询三链路压测并入库容量基线 |
| O9 | **Flyway 治理** | 276 个迁移 → 种子/结构分离策略，防止继续膨胀（CODEBASE_FOUNDATION P2） |
| O10 | **小程序 mock 演示路径隔离** | ✅ **09-18 复核：已实现（原判「仅运行时开关」不成立）**。`packages/shared-uni/src/runtime-flags.ts` 的 `isDevBuild = import.meta.env.DEV \|\| MODE==='development'` 是 **Vite 编译期常量替换** ⇒ `resolveMockEnabled()` / `resolveSandboxRecharge()` / `resolveWechatRechargeVisible()` 的生产分支被**常量折叠 + DCE 消除**（`showDevTools()` 恒 false ⇒ 模板 `v-if` 分支一并消除）；其上是后端 `mockEnabled` **运行时**开关，构成**双层**防御而非二选一 |

## 7. 建议排期（供讨论）

- **第 1 阶段（上线硬阻塞，~4-6 周）**：P0-1 ~ P0-5；P0-6/P0-7 并行。
- **第 2 阶段（提升单柜产出，~4 周）**：F1 动态定价、F5 储值、F6 体验补齐、F3 分享裂变（轻量）；O1/O6/O10。
- **第 3 阶段（变现与壁垒，持续）**：F4 广告变现、F2 刷脸、F12 发票、O2/O3；产品决策后再启动 F7-F11。
- 每阶段遵循既有门禁：verify-production-readiness + 三端审计式复核。

---

## 附：竞品信息来源

- [友宝在线官网](https://www.uboxol.com/) / [产品中心](https://www.uboxol.com/company/products.html)
- [丰e足食官网](https://www.feng1.com/) / [丰e足食与美团战略合作（新京报）](https://m.bjnews.com.cn/detail/1769076196129359.html)
- [SandStar 视达智能货柜（中国日报网）](https://cn.chinadaily.com.cn/a/202206/07/WS629f194fa3101c3ee7ad9542.html)
- [百度 AI+智能货柜解决方案](https://ai.baidu.com/solution/cabinet)
- [小麦便利：AI 视觉识别售货柜工作原理](https://www.xiaomai24h.com/news-18/)
- [映翰通 InVending Cloud AI 售货柜 SaaS](https://www.inhand.com.cn/products/ai-vending-cloud)
- [中国无人零售行业调研简报（东方财富 PDF）](http://pdf.dfcfw.com/pdf/H3_AP202508141727476593_1.pdf)
- [AI 动态定价在自动贩卖机的应用（2026）](https://xn--mts593a6yk.com/blog/ai-dynamic-pricing-vending-machine-taiwan-2026)
- [丰e足食单柜产出分析（钛媒体）](https://www.tmtpost.com/8028086.html)
