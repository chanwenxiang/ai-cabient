# AI Cabinet 全局共享项目知识（Living Doc）

> **地位**：本仓 Agent / 人工协作的**唯一总入口**。细则仍散落在规则、Skill、专题文档里；本文件负责**索引 + 现状摘要 + 变更账本**。  
> **维护规则**：`.cursor/rules/project-knowledge-living.mdc`（每次相关对话必读、必补）。  
> **维护 Skill**：`.cursor/skills/project-knowledge/SKILL.md`  
> **已解决问题 Skill**：`.cursor/skills/solved-problems-playbook/SKILL.md`  
> **封装流程 Skill**：`.cursor/skills/encapsulate-solved-problem/SKILL.md`  
> **最后校准**：2026-09-24

---

## 0. 会话开工清单（Agent 必做）

```
1. Read 本文件（至少 §1–§4、§7、§9、§7.4）
2. 若任务涉及近期改动/未决项：Read `.workbuddy/memory/MEMORY.md` + 当日 `YYYY-MM-DD.md`
3. 按任务命中领域 → 读对应 .mdc / Skill / 专题 doc / PROJECT-REFERENCE §n
4. 改代码 → 实测 → 若有新坑：记 lessons + 补本文件 Changelog
5. 可复用流程 → encapsulate-solved-problem 升格为 Skill / 门禁
```

**禁止**：只靠训练记忆或旧会话结论宣称「项目现在如何」；规模与端口以本文件 + 实测为准。  
**禁止**：只采信他 AI 文档里的 `[x]` / 自报绿——须按 MEMORY 纪律重新取证。

---

## 1. 一句话定位

**AI 开门柜**：消费者扫码开门取货 → 视觉/端侧识别 → 自动结算扣款；运营后台管设备/SKU/争议/财务；商户端管补货/定价/钱包。独立于旧仓 `ego-automat`（只读参考）。

---

## 2. 仓库现状速览（2026-09-24 校准）

| 维度 | 当前值 | 备注 |
|------|--------|------|
| Flyway 迁移 | **286** 个脚本，最新约 **V286** | 合入前查重号；勿再写已占用版本号 |
| trade Controllers | ~**84** | `*Controller.java` |
| trade 单测 | ~**299** `*Test.java` | 含大量并发测 |
| admin-vue 业务视图 | ~**71** `.vue` | `src/views` |
| consumer-mp / merchant-mp | 独立 uni-app | 分包 + `preloadRule`；H5 `:3002` / `:3001` |
| shared packages | types / api / dict / rbac / uni | 改 API 后 `pnpm gen:api-types` |
| 踩坑总册条目 | **≥101** | `docs/engineering/lessons-learned.md` |
| 审计门禁 | `pnpm check:audit-gates` | 新建脚本须进 `ci.yml` |

更细文件级清单：[CODEBASE_INVENTORY.md](CODEBASE_INVENTORY.md)；测试底稿：[CODEBASE_FOUNDATION.md](CODEBASE_FOUNDATION.md)。

---

## 3. 模块地图

```
clients/admin-vue          运营控制台 → 构建进 trade static/admin
clients/consumer-mp        消费者小程序（扫码开门/订单/充值）
clients/merchant-mp        商户小程序（补货/定价/钱包/分账）
packages/shared-*          共享类型 / API / 字典 / RBAC / uni
services/trade-service     领域大脑（会话·结算·支付·RBAC·仓储）
services/device-service    MQTT 桥（不下业务库）
services/common/common-core 共享 DTO / 枚举 / 内部鉴权
vision-service             FastAPI mock 识别 + 争议辅助
edge/*                     柜机端 / 模拟器
infra/                     Compose、网关、监控
```

完整表：[MODULES.md](MODULES.md)。

---

## 4. 关键约定（不可破）

| 主题 | 约定 |
|------|------|
| 对外 API | `/api/v2/...`；成功 `ApiResponse.code=0` |
| 服务间 | `/internal/v1/...` + `X-Internal-Api-Key` |
| DB | Postgres `localhost:15433/aicabinet`；只许 Flyway |
| 鉴权 | 生产标准；本地 `dev` + mock，禁关安全边界图省事 |
| 文案 | 用户可见须中文（`ui-copy-zh`） |
| UI Token | 禁裸业务 hex；`z-index` 用 `--z-*`；表格列语义 class |
| 改 API | common-core DTO + 调用方 + `shared-types` 同步 |
| 密钥 | 环境变量；禁止提交 |

核心规则：`.cursor/rules/project-core.mdc`。

---

## 5. 本地两种模式（测试前先选）

| 模式 | 启动 | trade 端口 |
|------|------|------------|
| **A. IDEA + 仅 infra**（日常推荐） | `docker compose -p ai-cabinet -f infra/docker-compose.yml up -d` + IDEA + vision | **:8080** |
| **B. 全栈 Docker** | `.\docker-up.ps1` | **:18080** |

**禁止** A/B 混开双 trade。速查：[STARTUP_REFERENCE.md](STARTUP_REFERENCE.md)。

| 面 | URL / 账号 |
|----|------------|
| 运营后台 | `http://localhost/admin/index.html` 或 `:8080/admin/...`；`13900000001` / `123456` + 验证码 |
| 消费者 H5 | `:3002` |
| 商户 H5 | `:3001` |
| Admin 构建 | `node scripts/build-admin.mjs`（Cursor 下 `pnpm run build:admin` 可能因 script-shell 失败） |

---

## 6. 核心业务链路（金钱正确性最高优先级）

```
扫码/登录 → POST /api/v2/sessions → 预授权 → MQTT 开门
  → SHOPPING → 关门 + 视频 → Settlement → Vision/端侧识别
  → 高置信：出单 + 扣库存 + 扣款
  → 低置信/失败：DISPUTED（通常先不扣款）
```

会话状态机：`CREATED → OPENING → SHOPPING → WAITING_UPLOAD → RECOGNIZING → SETTLING → COMPLETED`（旁路禁 `setState`，走 `SessionService.transition`）。

分布式锁前缀 `aicabinet:lock:`（开门 / 会话 / 结算 / 支付等），详见 [CODEBASE_FOUNDATION.md](CODEBASE_FOUNDATION.md) §3。

---

## 7. 文档与规则索引

### 7.1 必读专题

| 文档 | 何时读 |
|------|--------|
| [STARTUP_REFERENCE.md](STARTUP_REFERENCE.md) | 起服务 / 端口 / 账号 |
| [uat/OVERVIEW_FULL_BROWSER_UAT.md](uat/OVERVIEW_FULL_BROWSER_UAT.md) | 概览 9 页全量浏览器 UAT：数据/排版/UX/三端口径 + 全按钮生效 |
| [LOCAL_SETUP.md](LOCAL_SETUP.md) | 完整联调 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 服务边界 / 识别链路 |
| [FRONTEND_PRODUCT_DECISIONS.md](FRONTEND_PRODUCT_DECISIONS.md) | 三端产品边界 |
| [engineering/lessons-learned.md](engineering/lessons-learned.md) | 踩坑总册 |
| [engineering/admin-vue-debt-tracker.md](engineering/admin-vue-debt-tracker.md) | 运营后台技术债进度（D1–D25 已清） |
| [engineering/consumer-mp-debt-tracker.md](engineering/consumer-mp-debt-tracker.md) | 消费端小程序技术债（C1–C12，mp-weixin 权威） |
| [engineering/merchant-mp-debt-tracker.md](engineering/merchant-mp-debt-tracker.md) | 商户端小程序技术债（M1–M12，mp-weixin 权威） |
| [CODE_FIX_CHECKLIST.md](CODE_FIX_CHECKLIST.md) | 改完自检 |
| [TROUBLESHOOTING_GUIDE.md](TROUBLESHOOTING_GUIDE.md) | 联调排障 |

### 7.2 Cursor 规则（`.cursor/rules/`）

| 规则 | 作用 |
|------|------|
| `project-knowledge-living` | **本活文档**：必读必补 |
| `project-core` | 模块 / API / Flyway / 文案 |
| `record-lessons-learned` | 踩坑三列表 |
| `use-skills-and-mcp` / `dev-test-toolchain` | Skill+MCP 路由 |
| `playwright-ui-testing` | UI 验收优先 Playwright |
| `admin-layout-anti-jitter` | 后台布局防抖硬约束 |
| `admin-vue` / `uni-app` / `java-backend` | 分端约定 |
| `ui-token-conventions` / `ui-copy-zh` | Token 与中文文案 |
| `iot-cabinet-business-standards` | 开门柜并发 / 支付 / 硬件 |

### 7.3 本仓 Skills（`.cursor/skills/`）

| Skill | Trigger |
|-------|---------|
| `pre-push-ci-preflight` | **git push 前** format/lint/门禁预检 |
| `ai-cabinet-dev-test` | 写代码 / 测试总路由 |
| `project-knowledge` | 读/更新本活文档 |
| `solved-problems-playbook` | 套用已解决问题配方 |
| `encapsulate-solved-problem` | 修完后封装为记录+Skill |
| `browser-real-testing` | UI 真机验收 |
| `verification-before-completion` | 宣称完成前验证 |
| 其余 | 见 `ai-cabinet-dev-test` 路由表 |

### 7.4 WorkBuddy 全量落点（其他 AI 总结 — **最新在这里**）

> WorkBuddy 写入；**`.gitignore` 含 `.workbuddy/`**（不进 git，但本机可读）。  
> Cursor **不会自动注入**这些文件，须主动 Read。  
> 纪律：不采信文档 `[x]` / 自报绿——只当线索，结论靠源码+实跑。

#### A. 本仓库（日常最新 · 优先）

路径根：`ai-cabinet/.workbuddy/`

| 路径 | 内容 | 何时读 |
|------|------|--------|
| `memory/MEMORY.md` | 项目长期铁律索引（门禁/测试/提交/XXL…，`§n`→REFERENCE） | **每会话开工** |
| `memory/PROJECT-REFERENCE.md` | 外置详情大手册（环境/构建/OpenAPI/取证 §11…） | MEMORY 指到 `§n` 或构建/门禁细节 |
| `memory/YYYY-MM-DD.md` | **按日工作日志（最新会话总结）** | 查近期改动/未决；**优先最新日期文件** |
| `memory/EDGE-ANDROID-GRADLE.md` | 边缘端 Gradle | 动 `edge/android-app` |
| `handoff-prompt-*.md` | 交接开场提示词模板 | 开新会话接手时 |
| `cleanup-*.md` | 清理/划界笔记 | 清 scratch 前 |

**读序**：`MEMORY.md` → 最新 `YYYY-MM-DD.md` →（按指针）`PROJECT-REFERENCE.md` §n。

#### B. 用户主目录（跨项目 · 工具链）

路径根：`C:\Users\cwx\.workbuddy\`（`~/.workbuddy/`）

| 路径 | 内容 | 何时读 |
|------|------|--------|
| `MEMORY.md` | **跨项目**铁律（pnpm 假绿、Maven/PS、沙箱…） | Windows 工具链异常、门禁假绿/假红 |
| `CROSS-PROJECT-REFERENCE.md` | 跨项目细节层（`§n` 展开） | 主 MEMORY 指到细节层时 |
| `USER.md` / `SOUL.md` / `IDENTITY.md` | 用户偏好 / 人格 | 极少；非业务 |
| `memory/<uuid>_memory.md` | **云端托管缓存** | ⚠️ **禁止本地当下沉目标**（会被覆盖） |

#### C. WorkBuddy 工作区快照（早期成文报告）

路径根：`C:\Users\cwx\WorkBuddy\`（按会话时间戳分子目录）

| 路径 | 内容 | 何时读 |
|------|------|--------|
| `2026-09-03-11-04-19/ai-cabinet*.md` | 审查/整改/Checklist/测试设计等成批报告 | 查 09-03～09-06 历史结论（**易过期**） |
| `2026-09-03-11-04-19/执行台账-*.md` | 执行/复测台账 | 对照当时是否跑过 |
| `2026-09-18-14-27-59/.workbuddy/memory/` | 该会话副本记忆 | 仅追溯 09-18 会话 |
| 其它 `2026-09-*-*/` | 会话工作副本 | 一般**不必读**；以本仓 `.workbuddy/memory` 为准 |

代表报告（均在 `WorkBuddy\2026-09-03-11-04-19\`）：  
`ai-cabinet代码审查报告` / `管理后台前端` / `小程序端` / `三端之外` / `业务流程审查` / `最终整改报告` / `已修未修三态总账` / `修复核查结论` / `上线前Checklist` / `真实测试设计` / `开发环境可执行测试手册` / `最终测试执行文档` / `清库重置与浏览器实测方案` 等。

#### D. 仓内沉淀（可 git / 对外）

| 路径 | 何时读 |
|------|--------|
| `docs/CONSUMER_MP_OPTIMIZATION_REPORT_2026-09-24.md` | consumer-mp UI |
| `docs/pass-notes/PASS_3A`～`3F` | 金钱/争议/MQTT/库存/钱包/运营 |
| `docs/evidence/YYYY-MM-DD-*/` | 单项留证 |
| `docs/README_DOCS.md` | 仓内文档总索引 |

#### E. 2026-09-24 最新（mtime 校准）

- 本仓：`memory/2026-09-24.md`、`MEMORY.md`、`PROJECT-REFERENCE.md`（同日）
- 跨项目：`~/.workbuddy/MEMORY.md`（09-23）、`CROSS-PROJECT-REFERENCE.md`（09-23）
- 仓内报告：`docs/CONSUMER_MP_OPTIMIZATION_REPORT_2026-09-24.md`

---

## 8. 已解决问题领域地图（→ Skill / 规则）

| 领域 | 代表问题（总册 #） | 落地处 |
|------|-------------------|--------|
| admin 布局防抖 | 操作列 sticky、抽屉弹宽、滚动条挤内容、tooltip 盖列 (#1–5,33,38,46) | `admin-layout-anti-jitter.mdc` + `check:admin-anti-jitter` |
| admin 鉴权/RBAC | 双 toast、菜单 fail-open、JWT storage、端点字面量 (#9,14,36–37,41,54–55,72–87) | `AdminEndpoints`、`check:admin-*` |
| 结算/会话拆分 | Settlement / Session 上帝类 (#28,31) | 各 `*Service` 委托 |
| MQ / 视觉 | 吞异常丢消息、auto-commit、DLT、超时 (#19,22,45,75,95) | Kafka listener + vision worker |
| 资金/幂等 | 渠道事务顺序、幂等键可变、限额 (#17,96–97) | Payment / Refund 服务 |
| 小程序性能 | 分包、N+1、列表 pageSize (#18,27,29,60,63) | pages.json + API 聚合 |
| CI / DevOps | OpenAPI 过期、门禁未进 CI、runner/Sonar (#12–13,89,99–101) | `ci.yml` + devops 脚本 |
| Edge | MQTT 队列丢事件、TLS truststore、Prefs apply (#88,91,94) | android-app |

**配方级复用**：优先 `solved-problems-playbook` Skill，再下钻总册行号。

---

## 9. Changelog（只追加，新在上）

| 日期 | 变更摘要 | 证据 / PR / 会话 |
|------|----------|------------------|
| 2026-09-26 | 商品管理单页深测：关键词q/深链/编辑·下架取消/识别入驻 | `SKUS_FULL_BROWSER_UAT.md`、`skus/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 维修工单单页深测：筛选/深链/详情流转/新建·指派取消；lesson #209 EP option | `REPAIR_TICKETS_FULL_BROWSER_UAT.md`、`repair-tickets/BUTTONS`/`FINDINGS`、`lessons-learned` #209 |
| 2026-09-26 | 设备可用性单页深测：9卡/日期快照/暂无样本·无解锁空态 | `DEVICE_KPI_FULL_BROWSER_UAT.md`、`device-kpi/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 投放地图单页深测：默认ALL/投放筛/在线自营地区/详情深链 | `DEVICE_MAP_FULL_BROWSER_UAT.md`、`device-map/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 设备管理单页深测：看板/六态Tab/深链/批量取消/详情/导出 | `DEVICES_FULL_BROWSER_UAT.md`、`devices/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 设备运维单页深测：筛选/导出/中文码；修关键词空表 total #208 | `DEVICE_OPS_FULL_BROWSER_UAT.md`、`device-ops/BUTTONS`/`FINDINGS`、lessons #208 |
| 2026-09-26 | 异常中心单页深测：OPEN/ALL/深链/五类写取消；修「全部」回弹 OPEN #207 | `EXCEPTIONS_FULL_BROWSER_UAT.md`、`exceptions/BUTTONS`/`FINDINGS`、lessons #207 |
| 2026-09-26 | 争议审核单页深测：OPEN中文/认领/免单取消/深链；视口钉 1366（窄抽检禁 goto 冲掉） | `DISPUTES_FULL_BROWSER_UAT.md`、`disputes/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 开门记录单页深测：十一Tab/滞留#195/导出/时间线链；修类型筛选 total 空表矛盾 #205 | `SESSIONS_FULL_BROWSER_UAT.md`、`sessions/BUTTONS`/`FINDINGS`、lessons #205 |
| 2026-09-26 | 订单管理单页深测：Tab/筛选/双导出/抽屉退款取消/会话设备链；修已退款+隐藏零元滤空 #204 | `ORDERS_FULL_BROWSER_UAT.md`、`orders/BUTTONS`/`FINDINGS`、lessons #204 |
| 2026-09-26 | 用户分析单页深测：KPI=API、days/双导出、复购TOP、沉睡空态+召回disabled门控 | `USER_ANALYSIS_FULL_BROWSER_UAT.md`、`user-analysis/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 修补货深链不弹「规划补货路线」：onMounted 须先 maybeAutoPlan 再 syncRouteQuery；五入口弹层→取消复测 | lessons #203、`ReplenishmentView`、`stock-health/sh-plan-*` |
| 2026-09-26 | 库存健康单页深测：默认投放空态 vs ALL(断货6/低1)、一键规划2台、双导出、行设备/补货 | `STOCK_HEALTH_FULL_BROWSER_UAT.md`、`stock-health/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 销售报表单页深测：五维/快捷/柜机筛选¥7→¥3.50、双路径导出、深链 skus/devices/merchants | `SALES_REPORTS_FULL_BROWSER_UAT.md`、`sales-reports/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 财务毛利单页深测：KPI×6=API(350¢)、days/固化取消、图型、导出CSV、深链 analytics/orders/skus | `FINANCE_FULL_BROWSER_UAT.md`、`finance/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 设备详情按钮 100% 清点：保存资产/退款/策略拨回/温控全按钮/一键规划(造缺货还原)/货道弹窗/解锁营业 | `device-detail/BUTTONS` 全表、`dd-G-*`、FINDINGS |
| 2026-09-26 | 设备详情单页深测：概览/QR/资产生命周期/远程指令/四Tab/货道/导航；硬写确认取消；DPR=1 | `DEVICE_DETAIL_FULL_BROWSER_UAT.md`、`device-detail/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 设备报表单页深测：关键词/重置、URL·KPI 离线筛选、详情深链、今日营收¥3.50 | `DEVICE_REPORT_FULL_BROWSER_UAT.md`、`device-report/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 客流坪效单页深测：days/刷新、KPI×6=API、转化「—」、窄视口无整页横滚 | `FOOTFALL_FULL_BROWSER_UAT.md`、`footfall/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 数据分析单页深测：KPI×4 深链、days/图型、侧栏离线/争议/毛利、三端营收350¢ | `ANALYTICS_FULL_BROWSER_UAT.md`、`analytics/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 运营大屏单页深测：刷新/全屏/返回、KPI×6 口径、三端营收350¢；排行仅投放说明 | `BIG_SCREEN_FULL_BROWSER_UAT.md`、`big-screen/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 运营工作台缺口收口：§2.6 全7条、零计数UI点击、KPI键盘、分账行查看、诚实空态 | `workbench/BUTTONS` B.3～B.7、`wb-x02`～`wb-x07` 截图 |
| 2026-09-26 | 造单 ¥3.50 三端对齐 + admin 静态复测：分页共15/10可翻页、履约角标20=12+8 | `workbench/FINDINGS` C2、`wb-dash-page2-badge.png`、`wb-new-order-three-end.json` |
| 2026-09-26 | 运营工作台单页深测：按钮/深链/三端 mp 口径；修分页 total 与履约角标双计；禁 PowerShell 改 vendor.js | `WORKBENCH_FULL_BROWSER_UAT.md`、`workbench/FINDINGS.md`、lessons #201/#202 |
| 2026-09-26 | DevOps/日志中心按钮级：无观测栈诚实空态；七册+一致性+devops 收口总览 | `system/DEVOPS_BUTTONS.md`、`UAT_CLOSEOUT.md` |
| 2026-09-26 | 一致性脏数据清零：UI 修复库存/积分 + DB 退款对齐/删孤儿争议/补 SALE 流水；巡检全部通过 | finance-merchant/FINDINGS、consistency 回归 |
| 2026-09-26 | 系统侧栏十二页+DevOps/日志按概览同款复测；七册侧栏复测收口 | `system/BUTTONS.md`、FINDINGS、UAT_CLOSEOUT、SYSTEM v1.1 |
| 2026-09-26 | 增长风控侧栏十一页按概览同款复测；发券/加黑/回复等均取消 | `growth-risk/BUTTONS.md`、FINDINGS、GROWTH_RISK v1.1 |
| 2026-09-26 | 财务商户侧栏十一页按概览同款复测；一致性巡检全部通过；写路径均取消 | `finance-merchant/BUTTONS.md`、FINDINGS、FINANCE_MERCHANT v1.1 |
| 2026-09-26 | 履约仓储侧栏五页按概览同款复测（含补货员效率）；写路径均取消 | `warehouse/BUTTONS.md`、FINDINGS、WAREHOUSE v1.1 |
| 2026-09-26 | 设备商品侧栏十页按概览同款复测（含识别映射）；写路径均取消 | `device-sku/BUTTONS.md`、FINDINGS、DEVICE_SKU v1.1 |
| 2026-09-26 | 履约侧栏四页按概览同款复测：附录 B 编号 + FINDINGS + `ff-*.png`；资金确认均取消 | `fulfillment/BUTTONS.md`、FINDINGS、FULFILLMENT §0/§5 |
| 2026-09-26 | Phase7 系统按钮级清点 Done；七册按钮级全收口 | `system/BUTTONS.md`、各分册 BUTTONS |
| 2026-09-26 | Phase6 增长风控按钮级清点 Done（发券/加黑/回复均取消） | `growth-risk/BUTTONS.md` |
| 2026-09-26 | Phase5 财务商户按钮级清点 Done（调账/对账均取消） | `finance-merchant/BUTTONS.md` |
| 2026-09-26 | Phase4 仓储按钮级清点 Done；弹窗取消须等 enter 动画（#200） | `warehouse/BUTTONS.md`、lessons #200 |
| 2026-09-26 | SLA 在线率仅投放柜 + 开门时长可读；Phase4 仓储冒烟 PASS | lessons #198、SlaMetricsService/SlaView、`warehouse/FINDINGS.md` |
| 2026-09-26 | Phase5 财务商户分册 + 11 页冒烟；一致性见退款/争议脏数据 | `FINANCE_MERCHANT_FULL_BROWSER_UAT.md`、finance-merchant/FINDINGS |
| 2026-09-26 | Phase6 增长风控分册 + 11 页冒烟 | `GROWTH_RISK_FULL_BROWSER_UAT.md`、growth-risk/FINDINGS |
| 2026-09-26 | Phase7 系统分册 + 12 页冒烟（devops SKIP） | `SYSTEM_FULL_BROWSER_UAT.md`、system/FINDINGS |
| 2026-09-26 | 概览 Phase1 按钮级清点结案（附录 B / Done 勾选） | `overview/BUTTONS.md`、OVERVIEW UAT §0/§10 |
| 2026-09-26 | 履约 Phase2 按钮级清点结案（资金路径均取消） | `fulfillment/BUTTONS.md`、FULFILLMENT Done |
| 2026-09-26 | 大屏口径对齐：排行仅投放柜；待办显示「前 n · 共 N」 | lessons #199、BigScreenView |
| 2026-09-26 | 设备商品 Phase3 按钮级清点结案（锁机/下架均取消） | `device-sku/BUTTONS.md`、DEVICE_SKU Done |
| 2026-09-26 | 审债务补钉：冻结类型三处门禁 + 货道可售 SQL IT + color-mix PENDING 到期 | lessons #188–#190；`DeviceSkuLotEnabledSlotSqlIT` 2/0；A/B GHOST/过期均红 |
| 2026-09-26 | 防回归：客流页主区 `overflow-y` 从 auto 改回 scroll（点滚动条挤内容） | lessons #187、`admin-layout-anti-jitter` 表 D、`check:admin-anti-jitter` |
| 2026-09-26 | 修 CI：business `num` 别名 + 隐私弹层 package import + C5c/C6c 类型 | lessons #184/#185、Actions mini-programs/e2e-h5 |
| 2026-09-26 | merchant M10c：要货提交门闩；M6d：经营 load 决策 | debt-tracker M10/M6、lessons #182/#183 |
| 2026-09-26 | merchant M4b：补货证据映射；consumer C12d：登录落盘规划 | debt-tracker M4/C12、lessons #180/#181 |
| 2026-09-26 | 按行退款补二次确认 + append-to-body；Phase3 设备商品冒烟 | lessons #197、OrderListView、DEVICE_SKU UAT |
| 2026-09-26 | 订单详情 lines 空 `{}`：OrderLineDto 补 @JsonView(Public) | lessons #196、OrderLineDto |
| 2026-09-26 | Phase2 履约分册 + 订单/开门/异常/上传队列浏览器冒烟 | `FULFILLMENT_FULL_BROWSER_UAT.md`、fulfillment/FINDINGS |
| 2026-09-26 | 「仅滞留」须活跃态+超时，禁止只按 updatedBefore | lessons #195、OpsSessionOrderQueryService |
| 2026-09-26 | 工作台在线率仅投放柜（0/1 非 1/3）；深链带 lifecycleStatus=DEPLOYED | lessons #194、OpsWorkbenchQueryService、DashboardView |
| 2026-09-26 | 工作台离线仅投放柜（剔 CAB-001）；争议操作列安全边距 | lessons #193、OpsWorkbenchQueryService、DisputeListView/main.css |
| 2026-09-26 | 概览 UX：大屏图例点位口径；客流有单无开门转化「—」；去掉告警头重复进件按钮 | lessons #191/#192、BigScreen/Footfall/Dashboard |
| 2026-09-26 | 概览 UAT 三端口径：订单 1790…3757 Admin/DB/消费 mp/商户 mp 均为 ¥3.50；H5≠mp 验收权威 | FINDINGS、微信开发者工具 automator |
| 2026-09-26 | 修分析口径 P1：normalizeTrendDays(1) 不再抬成 7；销售默认近7天；进件空态「暂无待办」 | lessons #190、Analytics/Sales/Dashboard |
| 2026-09-26 | 修 P0 缺货口径：countLowStock 仅投放柜 + 工作台深链带 lifecycleStatus=DEPLOYED | lessons #186、`DeviceSkuInventoryMapper.xml`、`DashboardView.vue` |
| 2026-09-26 | 概览 UAT 开跑（Playwright）：工作台深链多数生效；P0 缺货 4→库存健康空页（lifecycle=投放 vs CAB-001）；分析今日营收0 vs 近1天渠道有额 | `docs/uat-screenshots/2026-09-26/overview/FINDINGS.md` |
| 2026-09-26 | 概览 UAT v1.1：补数据三角对照、排版/UX、三端口径、全按钮生效协议与附录 B/C | `docs/uat/OVERVIEW_FULL_BROWSER_UAT.md` |
| 2026-09-26 | 开写「概览」全量浏览器 UAT 真源：侧栏 9 页按钮/深链/小程序挂钩；强制 Playwright 实测 | `docs/uat/OVERVIEW_FULL_BROWSER_UAT.md` |
| 2026-09-26 | merchant M7c：争议结案门闩；consumer C5c：开门/轮询编排决策 | debt-tracker M7/C5、lessons #178/#179 |
| 2026-09-26 | merchant M6c：business 样式外置 + 税档纯函数；consumer C6c：OrderAppealSheet | debt-tracker M6/C6、lessons #176/#177 |
| 2026-09-26 | merchant M3e：扫完 merchant-api 字面量；consumer C12c：鉴权下载纯函数 | debt-tracker M3/C12、lessons #174/#175 |
| 2026-09-26 | merchant M3d：补货/分析迁 Endpoints；consumer C12b：open-attempt 拆出 | debt-tracker M3/C12、lessons #172/#173 |
| 2026-09-26 | merchant M6b：business-display；consumer C5b：开门可用性/超时纯函数 | debt-tracker M6/C5、lessons #170/#171 |
| 2026-09-26 | merchant M7b：争议分页/详情纯函数；consumer C6b：申诉弹层文案收口 | debt-tracker M7/C6、lessons #168/#169 |
| 2026-09-26 | consumer C2c：扫完 consumer-api 字面量；merchant M10b：request-draft 纯函数 | debt-tracker C2/M10、lessons #166/#167 |
| 2026-09-26 | consumer C2b：auth/account/orders/sessions 迁 Endpoints；merchant M3c：orders/disputes/wallet | debt-tracker C2/M3、lessons #164/#165 |
| 2026-09-26 | consumer C12 首刀：orderVideoUrl；merchant M3b：证据/导出/me 迁 Endpoints | debt-tracker C12/M3、lessons #162/#163 |
| 2026-09-26 | consumer C11：settleWithin 语义钉死；merchant M12：orderVideoUrl 收口 | debt-tracker C11/M12、lessons #160/#161 |
| 2026-09-26 | merchant M11：金钱展示统一 fmtMoney；consumer C8：verify 去 as any | debt-tracker M11/C8、lessons #158/#159 |
| 2026-09-26 | consumer C7b：orders/login/recharge 样式外置；merchant M10：request 去空 catch+样式 | debt-tracker C7/M10、lessons #156/#157 |
| 2026-09-26 | consumer C10：共享组件同步脚本+门禁；merchant M8：deviceSettings OpenAPI + merchantId 解析 | debt-tracker C10/M8、lessons #154/#155 |
| 2026-09-26 | consumer C7 首刀：mine 充值文案+样式外置；merchant M7：争议 PAGE_SIZE=50+样式 | debt-tracker C7/M7、lessons #152/#153 |
| 2026-09-26 | consumer C6 首刀：`order-appeal`；merchant M6 首刀：business `fmtMoney` | debt-tracker C6/M6、lessons #150/#151 |
| 2026-09-25 | consumer C5 首刀：`landing-session`；merchant M5：补货类型+样式外置 | debt-tracker C5/M5、lessons #148/#149 |
| 2026-09-25 | consumer C4：金钱 UI 契约；merchant M4：首页异常 `maxPages=1` | debt-tracker C4/M4、lessons #146/#147 |
| 2026-09-25 | consumer C3：Bearer expires 读校验；merchant M3：`MerchantEndpoints` + 门禁 | debt-tracker C3/M3、lessons #144/#145 |
| 2026-09-25 | consumer C2：`ConsumerEndpoints` + `check-consumer-endpoints`；merchant M2：`money-ui-contracts` | debt-tracker C2/M2、lessons #142/#143 |
| 2026-09-25 | consumer C1 + merchant M1：soft-fail 可见化（label+toast）；补货主列表硬失败 | debt-tracker C1/M1、lessons #141、`utils/soft-fallback.ts` |
| 2026-09-25 | 双端小程序技术债开单：`consumer-mp-debt-tracker`（C1–C12）+ `merchant-mp-debt-tracker`（M1–M12）；验收权威 mp-weixin，H5 不参与 | 源码审计会话 |
| 2026-09-25 | e2e-h5：`adminPageState` 认 CrudTable `.crud-empty`，修 T-A02/T-A04 假红 | lessons #140、`scripts/lib/ui-assert.mjs` |
| 2026-09-25 | e2e-h5：落地页 `uni.setBackgroundColor` 加能力检测，修 TC-QUAL-001（H5 无此 API） | lessons #139、`pages/index/index.vue` |
| 2026-09-25 | admin-vue D25：恢复补货 D13 三 composable 接线 + 规划对话框外置；ReplenishmentView ~3378→~2467 | debt-tracker D25、lessons #138 |
| 2026-09-25 | admin-vue D25 开单：恢复 ReplenishmentView D13 接线 + 规划对话框（误 checkout 回退） | debt-tracker D25、lessons #138 |
| 2026-09-24 | admin-vue D24：温控 Tab UI→`DeviceTempEnvTab`；DeviceDetail ~1351→~1255 | debt-tracker D24、lessons #137 |
| 2026-09-24 | admin-vue D23：远程运维→`useDeviceRemoteOps` + `DeviceRemoteOpsCard`；DeviceDetail ~1767→~1351 | debt-tracker D23、lessons #136 |
| 2026-09-24 | admin-vue D22：关联单据→`useDeviceRelatedRecords` + `DeviceRelatedRecordsTab`；DeviceDetail ~2004→~1767 | debt-tracker D22、lessons #135 |
| 2026-09-24 | admin-vue D21：资产与投放→`useDeviceAsset` + `DeviceAssetDeploymentCard`；DeviceDetail ~2412→~2004 | debt-tracker D21、lessons #134 |
| 2026-09-24 | admin-vue D20：概览/供应商/在途/批次/流水→`WarehouseOverviewTab` 等五组件；WarehouseView ~2070→~1665；仓配 Tab 表格清完 | debt-tracker D20、lessons #133 |
| 2026-09-24 | admin-vue D19：调拨/退货/盘点/货位→`WarehouseTransfersTab` 等四组件；WarehouseView ~2550→~2070；六期清完 | debt-tracker D19、lessons #132 |
| 2026-09-24 | admin-vue D18：采购单/出库单→`WarehousePurchaseOrdersTab`/`WarehouseOutboundsTab`；WarehouseView ~2929→~2550；五期清完 | debt-tracker D18、lessons #131 |
| 2026-09-24 | admin-vue 五期开单 D18（仓配采购单/出库单 pane 拆子组件）；建议顺序 采购单→出库单 | debt-tracker 五期节 |
| 2026-09-24 | admin-vue D17：仓配采购建议/应付→`WarehouseSuggestionsTab`/`WarehousePayablesTab`；WarehouseView ~3242→~2929 | debt-tracker D17、lessons #130 |
| 2026-09-24 | admin-vue D16：货道套模板/编辑/盘点/保存→`useDeviceSlotActions`；DeviceDetail ~2570→~2413；三期开单并清完 | debt-tracker D16、lessons #129 |
| 2026-09-24 | admin-vue D14：设备温控/环境→`useDeviceTempEnv`；DeviceDetail ~2662→~2570；二期 D13–D15 清完 | debt-tracker D14、lessons #128 |
| 2026-09-24 | admin-vue D13：补货要货流→`useReplenishmentRequestFlow`；理货明细/货道/证→`useReplenishmentTaskLines`；View ~3016→~2764 | debt-tracker D13、lessons #127 |
| 2026-09-24 | admin-vue D15：仓配/补货去散落 `any`→`AdminDynamicRow`；补货柜门写路径接 OpenAPI Task/Route；恢复 tabLoader softFallback | debt-tracker D15、lessons #126 |
| 2026-09-24 | admin-vue 二期开单 D13–D15（补货再拆 / 仓配·设备详情续拆 / 仓配补货去 any）；建议顺序 D15→D13→D14 | debt-tracker 二期节 |
| 2026-09-24 | admin-vue D12：`endpoints.ts` 文件头文档化 API 前缀分裂；D1–D12 清单清完 | debt-tracker D12、lessons #125 |
| 2026-09-24 | admin-vue D11：删无调用方 dataTables/schema/row/create/update 镜像端点 | debt-tracker D11、lessons #124 |
| 2026-09-24 | admin-vue D10：停写 `admin_permissions`/`admin_active_nav` 死缓存，只清遗留 | debt-tracker D10、lessons #123 |
| 2026-09-24 | admin-vue D9：2FA challenge 改 `sessionStorage`，清 localStorage 遗留 | debt-tracker D9、lessons #122 |
| 2026-09-24 | admin-vue D8：Feedback/Risk 列表行去 `any`，接 `UserFeedbackDto`/`OpenApiRiskEventDto`/`OpenApiUserBlacklistDto` | debt-tracker D8、lessons #121 |
| 2026-09-24 | admin-vue D7：下拉伪全量收口 `admin-catalog-query` + `merchantsCatalog`/`devicesOptions`；views 去 size 魔法数 | debt-tracker D7、lessons #120 |
| 2026-09-24 | admin-vue D6：设备生命周期抽出 `useDeviceLifecycleActions` + `device-lifecycle-guards`（5 测）；DeviceDetail ~2750→~2580 | debt-tracker D6、lessons #119 |
| 2026-09-24 | admin-vue D5：金钱写路径契约 `money-ui-contracts` + 8 测；Order/Dispute/MerchantWithdraw 复用 | debt-tracker D5、lessons #118 |
| 2026-09-24 | admin-vue D4：补货柜门写路径抽出 `useReplenishmentTaskActions`；View script ~1720→~1560；`vue-tsc` 绿 | debt-tracker D4、lessons #117 |
| 2026-09-24 | admin-vue D3：可选依赖禁静默空列表；`softFallback`/`createSoftFailCollector` + 单测；仓配/设备详情/大屏/打印可见 warning | debt-tracker D3、lessons #116、`soft-fallback.ts` |
| 2026-09-24 | admin-vue D2：券/活动/公告/反馈迁 `AdminEndpoints`，门禁 70 literals；`check-admin-endpoints` 绿 | debt-tracker D2、lessons #115 |
| 2026-09-24 | admin-vue D1：争议+补货开门迁 `AdminEndpoints`，门禁扩至非 admin 前缀（66 literals）；`check-admin-endpoints` 绿 | debt-tracker D1、lessons #115 |
| 2026-09-24 | admin-vue 细审债清单落盘：`docs/engineering/admin-vue-debt-tracker.md`（D1–D12）；总册 #115–117（门禁盲区 / soft-fail 空列表 / 补货上帝页）；§10 增 G4 | 源码审计会话 |
| 2026-09-24 | 消费者小程序首页对齐竞品：删「继续在本柜购物」白底卡与「附近找柜」（landing 入口 + help 入口 + nearby 分包页 + `nearbyDevices` 客户端封装 + e2e-nearby.ps1 + manifest 定位声明）；扫码盘下移 16vh；落地页 `uni.setBackgroundColor` 品牌深色消底部白条。`last_device_id` storage 保留（feedback/mine/orders/report 仍读） | `pages/index/index.vue`、`pages.json`、`manifest.json` |
| 2026-09-24 | 修 NProgress 拆除后仍残留的硬刷新彩线：`router.afterEach` 程序化聚焦 `#main-content` 在无交互时命中 `:focus-visible` ⇒ UA 焦点环顶边露出；对 `tabindex="-1"` 主区去 outline（Playwright 实测 `fv=true outline=auto`） | lessons #103、`main.css`、`router/index.ts:551` |
| 2026-09-24 | 拆除 admin NProgress（硬刷顶栏绿线根因）；验收用 `localhost/admin`（nginx 挂载），勿用 `:18080` trade JAR 旧静态 | lessons #102、`router/index.ts` |
| 2026-09-24 | 修 admin 硬刷新顶栏绿线：首屏不启 NProgress + `done(true)` 后摘 `#nprogress` DOM（Playwright 硬刷 analytics 全程 0 次 `#nprogress`） | lessons #102、`router/index.ts`、`static/admin` `index-Bx_grWOr.js` |
| 2026-09-24 | 修 admin 顶栏 NProgress 绿线刷新残留：`finishRouteProgress` + 同路径重定向先收条 | lessons #102、`router/index.ts` |
| 2026-09-24 | 推送前强制预检：`scripts/pre-push-ci-preflight.mjs` + 规则 `pre-push-ci-green` + Skill；覆盖 format/lint/audit-gates/migration；不谎称含 e2e-h5 | alwaysApply |
| 2026-09-24 | 规则 `critical-judgment`：用户要求先判断合理性并建议，禁止盲目服从；冲突优先级改为铁律 > 知情覆盖 | alwaysApply |
| 2026-09-24 | 加仓库根 `AGENTS.md`；加固 `project-knowledge-living`（跨会话 alwaysApply 开工三连） | 保证新会话注入 |
| 2026-09-24 | 补全 WorkBuddy 三层落点：本仓 `.workbuddy/`、用户 `~/.workbuddy/`、工作区 `~/WorkBuddy/` 历史报告 | §7.4 A–E |
| 2026-09-24 | 索引 WorkBuddy 总结落点：`.workbuddy/memory/`（MEMORY / PROJECT-REFERENCE / 按日日志）+ 仓内 REPORT/pass-notes/evidence | §7.4 |
| 2026-09-24 | 建立本活文档 + `project-knowledge-living` 规则 + 三个维护/封装 Skill；校准 Flyway≈V286、Controllers≈84、单测≈299、视图≈71 | 本提交 |
| 2026-09-24 | admin UI round3 / 设备地图相关留证目录存在 | `docs/evidence/2026-09-24-*` |
| 2026-09-24 | 消费者小程序 UI 三处（真机逐像素取证）：① help 导航条移出带 `padding:0 24rpx` 的根容器 ⇒ 绿条全出血不再漏白；② FAQ 展开箭头改 SVG 遮罩画法（旋转方盒的 v 视觉宽是 > 的 2 倍，永远不同形，lessons #105）；③ 多个 app-button 的间距一律包块级 `<view>`（页面 scoped WXSS 进不去组件内部 + 组件标签 wrapper 是 inline，lessons #104） | `pages/help/help.vue`、`pages/marketing/index.vue`、`pages/coupons/coupons.vue`、lessons #104–105 |
| 2026-09 | 踩坑总册累计至 #101（Sonar 凭据、GHA runner、Windows pathconv 等） | `lessons-learned.md` |

### 追加模板

```md
| YYYY-MM-DD | 一句话：改了什么 / 学到什么 | 链接到 lessons #N / evidence / Skill |
```

---

## 10. 待完善 / 已知缺口（主动补全区）

> Agent 发现过时或空白时**必须**改本节或升级为 Changelog 已解决项。

| ID | 缺口 | 建议动作 |
|----|------|----------|
| G1 | `CODEBASE_FOUNDATION` 仍写 Flyway V265 / Controllers~69 | 下次大盘点时同步数字，或以本文件 §2 为准 |
| G2 | 设备地图抖动若已修，总册尚无专行 | 确认根因后写入 lessons + 本 Changelog |
| G3 | 部分 evidence 未回链到总册行号 | 修相关域时顺手补「门禁/文件」列 |
| G4 | admin-vue 技术债 | D1–D25 **已清**（见 `admin-vue-debt-tracker`） |
| G5 | consumer-mp 技术债 | C1–C5 **done**（C5 首刀）；建议顺序 C6→C7→C5b |
| G6 | merchant-mp 技术债 | M1–M5 **done**；建议顺序 M6→M7→M8 |

---

## 11. 与其它文档的关系

| 文档 | 关系 |
|------|------|
| 本文件 | **总入口 / 活索引 / 变更账本** |
| `AGENTS.md` | **跨会话一页纸**（新对话先读） |
| `.cursor/rules/project-knowledge-living.mdc` | alwaysApply：强制读本文件 + WorkBuddy |
| `lessons-learned.md` | 现象→根因→必须怎么做 **明细表** |
| `CODEBASE_FOUNDATION.md` | 测试设计底稿（可滞后，以本文件校准为准） |
| `.cursor/rules/*.mdc` | 可执行硬约束 |
| `.cursor/skills/*/SKILL.md` | 可执行流程 |

冲突时：**安全/资金/门禁铁律 > 用户知情后的明确覆盖 > 用户一般偏好 > 本活文档最新 Changelog > WorkBuddy > 旧专题数字**。  
用户要求若不合理：按 `critical-judgment` 先谏言，勿盲目执行。
