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
| Flyway 迁移 | **287** 个脚本，最新约 **V287** | 合入前查重号；勿再写已占用版本号 |
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
| [THREE_END_FULL_REVIEW_2026-09-27.md](THREE_END_FULL_REVIEW_2026-09-27.md) | 三端全面审查结论（无 P0/11 P1）、mock 开关总表、接真前 checklist | 排修复计划 / 接小程序号·支付·硬件·识别前 |
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
| 2026-10-01 | **P1-2/P1-3 落地**：P1-2 盘点口径合一闸门——lot 账本设备整机盲调 409 指向货道盘点（旧实现直写汇总会被 syncAggregate 静默冲掉=丢账，全库核实零 UI 调用方；slot 口径确认真源，2 用例）。P1-3 在途单向化——receiveHandoverPair 唯一成对入口（收敛两处散写，漏一半即脏账）+ 一致性巡检 OUTBOUND_HANDOVER 两向漂移检测（孤儿在途/挂起交接），真库存量绿（36+10+2 测全绿） | `InventoryOpsService`、`ReplenishmentService`、`DataConsistencyService` |
| 2026-10-01 | **P2-4 审批单步直过落地（形态修正为缺陷修复）**：无启用审批定义时 review 直置 CREATED（原 isInstanceApproved 恒 false 会永久卡 PENDING_APPROVAL）；配置了定义走多节点链不变；8 单测全绿。P1/P2 全部收官，余 P3 评估级 | `ProcurementService` |
| 2026-10-01 | **P3-4 设计稿成文待评审**：识别超时自动免单——关键资金事实核实=超时单从未扣款+预授权已释放（resolveWaive 对未扣款单返 0「无需扣款」），自动 WAIVE=零资金移动纯结案；方案 XXL dispute-auto-waive（超时+未认领+72h 门控、默认 OFF、单轮/单用户防薅上限、复用 resolveTicket）；三个待拍板问题（阈值/防薅口径/灰度节奏）见 §6 | `docs/P3_4_DISPUTE_AUTO_WAIVE_DESIGN.md` |
| 2026-10-01 | **P3-4 争议超时自动免单已实施**（用户授权按建议判断：72h/7 天 ≤3 次/dev 先开）：DisputeAutoWaiveScheduler 每 15min 门控扫描（超时+未认领+cutoff）→ autoWaiveTicket 与人工 WAIVE 同链落账（跳权限/范围校验，AUTO_WAIVE 标记防薅统计）；单轮 ≤50、告警摘要、开关默认 OFF；七处接线+V297+seed id=133；4 用例+看护阈值+两道接线门禁绿 | `DisputeAutoWaiveScheduler`、`DisputeService.autoWaiveTicket`、V297 |
| 2026-10-01 | **P3-3 首页拆解切一**：落地页整块抽 `HomeLanding.vue`（模板+样式逐字搬移、landingError/showManual/deviceInput 用 defineModel 保内联赋值语义、7 个动作上抛编排不动），index.vue 3641→3167；vue-tsc+126 单测+四门禁+预检全绿；债表 C13 开单跟踪切二 | `clients/consumer-mp/src/components/HomeLanding.vue`、consumer-mp-debt-tracker C13 |
| 2026-10-01 | **P3-3 切二**：目录检索/分类/过滤逻辑抽 `use-home-catalog.ts`（datasetOf 收口，模板零改动），index.vue 3167→3120；新增 4 单测（原页内逻辑首次有覆盖；zh locale 拼音序断言一次修正）；130 单测+预检全绿；C13 待切三=购物车 UI+开门/轮询编排（涉资金设计先行） | `clients/consumer-mp/src/composables/use-home-catalog.ts` |
| 2026-10-01 | **P3-1/P3-2 评估完成**：双钱包 9 方法×2+表逐列同构属实但两域分化中→分级建议（余额公式巡检立即做/提现编排 P3-1b 下迭代/泛型账本待第三域）；营销三轨=三类业务**不收敛关闭**（仅预算管控按需另议预算闸）；全部 P1/P2/P3 清单收官 | `docs/P3_EVALUATIONS_WALLET_AND_MARKETING_2026-10-01.md` |
| 2026-10-01 | **P3-1b 第一步 + C13 切三**：P3-1b=F3 判据单点化 `WithdrawPayoutPolicy`（仅 MOCK 可 PAYING 超时自动失败），商户/线长两侧委托，成对测试 13/13+policy 2 用例绿（行为不变 A/B）；C13 切三=购物车条抽 `HomeCartBar.vue`（逐字搬移+6 动作上抛编排不动），index.vue 3120→2933，130 单测+门禁+预检全绿 | `WithdrawPayoutPolicy`、`clients/consumer-mp/src/components/HomeCartBar.vue` |
| 2026-10-01 | **C13 切四**：轮询调度壳抽 use-session-poll.ts（设计契约写明壳/onTick 边界：单窗口+C2 上限+防并发+失败连击上移，会话状态机与结算留页）；5 假定时器单测（调度时序/封顶不重燃/防重入/文案升级钉现状），index.vue →2868（四刀累计-21%）；切四即终刀：开门编排评估为一次性编排收益低于风险保留 | `clients/consumer-mp/src/composables/use-session-poll.ts` |
| 2026-10-01 | **P3-1b 第二步（收官）**：打款渠道决策单点化 WithdrawPayoutPolicy.channelFor（mockEnabled→MOCK/WECHAT 字面量唯一出口，与 F3 判据同族收口），两侧委托；policy 3 用例+成对 13 绿。P3-1b 完成：F3+渠道决策已单点化，剩余实体孪生=接受债务（继续收敛=抽象税） | `WithdrawPayoutPolicy` |
| 2026-10-01 | **全量推送+CI 绿**：22+3 提交推 dev（run 36855931468 全 job ✓）。推送途中连吃两红并修复：①收货误验 SKU 存在性（P1-1 波及漏跑的 ProcurementReceiveWarehouseTest）→收窄到下单路径；②P2-6 端点漏重生成 OpenAPI 类型→现抓补齐。lessons #242=改 Service 后本地跑模块全量、动端点即重生成类型 | run 36855931468、lessons #242 |
| 2026-10-01 | **P1-1 批次号后置到收货落地**：ProcurementService 建单批次/效期选填、收货必填可覆盖并回写订单行、matchLine 批次可空 sku 兜底（多行须 lineId）；admin 建单校验放宽+收货弹窗批次/到期日录入与提交校验；6 单测全绿。**P2-6 盘点「完成并过账」落地**：completeAndAdjust 同锁同事务（/complete-and-adjust 端点+弹窗按钮+2 用例 12/12 绿）。**P2-7/P2-5 核实关闭**（一键规划已等价覆盖/路线 UI 冗余可接受）；**P3-5 M9 tracker 收口**（easycom 已直指 shared-uni） | `ProcurementService`、`WarehouseStocktakeService`、merchant-mp-debt-tracker M9 |
| 2026-10-01 | **新旧系统全面对比成文+两项落地**：对比文档（旧弹簧柜 easygo=前海易购同一条产品线，含 M8=第一代开门柜血缘论证）Part I 仓储三域 P1/P2 清单、Part II 核心域、Part III 客户端、P1/P2/P3 分级建议与不抄清单。**①巡检安全网已落地**：DataConsistencyService 加 WAREHOUSE_LEDGER/DEVICE_LEDGER 两条「期初+Σ流水=余额」公式断言（59 单测+Testcontainers IT A/B 全绿、GUARDED 登记）；demo 播种补记期初流水（DemoDataService）+本地 6 组存量回填归零。**②P3-6 消费者广告轮播已落地**：V296 ad_campaign 加 channel（CABINET_SCREEN/MINI_PROGRAM）+link_url；投放范围复用 device_scope（banners?deviceId=，SPECIFIC 仅指定柜机上下文）；banners() 广告优先活动补位兼容兜底；打点端点 POST /marketing/ads/{id}/events（MP-U{userId} 60s 去重）；营销页图片模式+新组件 marketing-ad-banner 与设备位 DeviceAdBanner 互斥（fail-silent）；admin 投放表单渠道/深链/范围文案；发现 F4 设计与素材上传本已是真实现（§8.1 误判已修正）；static/admin 重建提交、shared-types 重生成、preflight 双端 type-check+39 门禁全绿 | `docs/SYSTEM_COMPARISON_EASYGO_VS_AICABINET_2026-10-01.md`、`V296`、`DataConsistencyService`、`marketing-ad-banner.vue` |
| 2026-09-30 | 全站列表移鼠标「抖」：表体 td 禁背景过渡（不限补货）；侧栏图标禁 scale；隐藏 tab 勿误加 `--h` | lessons #240、`admin-layout-anti-jitter` 表 K；27 页 hover 探针 |
| 2026-09-29 | milk 桥修复：模拟器镜像名笔误（ai-cabinet/device-simulator:local→ai-cabinet-device-simulator）致补货 409 两连败；docker run 后轮询 online_status=ONLINE ≤90s fail-loud（lessons #239）；昵称/身份展示上线（V293+PUT nickname+我的页+后台昵称列+wx 占位号显示「微信登录·未绑手机」） | `scripts/e2e-full-flow-milk.ps1`、milk 13/13 |
| 2026-09-29 | 修移鼠标整页微抖：侧栏宽按 DPR 对齐设备像素 + 去掉主列 margin-left:-1px + 壳高回滞 | lessons #238、`AdminLayout.vue`、repair-tickets@127.0.0.1 dpr≈1.815 |
| 2026-09-27 | 三端全面审查（6 路并行只读+主审复核）：产品代码**无 P0**；11 P1（资金 F1 竞态多付/F2 超时免单旁路/F3 提现盲置 FAILED、安全 S1 设备共享 MQTT 凭据/S2 GET token/S3 CSV 注入/S4 full 栈 Grafana、admin A1、consumer C1/C2、跨端 X1 preflight 盲区/X2 staging mock env）；mock 矩阵与 dev-only 面确认收口；文档漂移修正（admin 登录=密码+图形验证码+2FA 非短信码；286 迁移/85 Controller） | `docs/THREE_END_FULL_REVIEW_2026-09-27.md` |
| 2026-09-27 | 同日第二轮验证清零（原未验证项全部关闭）：preflight 全绿 47s、audit-gates 39/39、admin 84/84+consumer 121/121+merchant 130/130 单测、三端 type-check 绿、**实时 OpenAPI 与 generated 逐字节一致**、production compose `:?` fail-loud 实证；新 P0（full 栈 Grafana admin/admin 实测可登，环境级）、新 P1×3（RECON_SCHEDULED_ENABLED 默认关=对账与退款推进不执行、nginx 三份漂移、9999/13000 绑 0.0.0.0）、新 P2×22（含 shared-rbac/dict dist 陈旧、rbac dist 缺 B-13）；85 Controller RBAC 全表无漏保护写端点；lessons #230/#231 | 报告 §10、`lessons-learned.md` #230-231 |
| 2026-09-27 | 复查子代理「不可验」清单：4 项实为本机可验并补验——**新 P1：MinIO cabinet-videos 桶匿名可读可列举（含会话视频，媒体白名单被绕过）**、htpasswd 非弱口令、XXL 调度健康（30s 真实触发）、CORS dev 值 localhost；**撤回「xxl_job_log 2612 污染」误报**（DATETIME 口径+UTC 错位，lessons #232）；「开门全链不可验」改为已由模拟器 E2E 覆盖；§10.5 重分类收口 | 报告 §10.3·§10.5、`lessons-learned.md` #232 |
| 2026-09-27 | 可做未做执行+亲验对账：mvn verify **1344 用例全绿**；admin 侧 6 UAT 实浏览器全过（role-regression 2 数据态软挂）；**补偿链合成任务运行时实证**；并发冒烟 30/0（并发开门锁生效、零重复开门）；第一轮 11 P1 全亲验、第二轮修正确认 2 处（xxl 污染撤回、rbac:assign:device 降级为双轨不一致）；**口径确认：mp-weixin 验收权威，H5 不作 mp 验收** | 报告 §10.1/§10.5、`lessons-learned.md` #230-232 |
| 2026-09-28 | **第一层+第二层落地**：运行栈对齐最新代码（trade/device 重建+redis 密码化协调重启，Flyway V288 已应用）；**L2-1** CHARGE_PENDING>60min 告警（搭 unpaidCancel 车辆）；**L2-3** TCC 死代码下线（删三件套+XML、V288 DROP 死表、XXL 五处同步、live compensationRetryJob 已删）；**L2-2** 密码登录失败≥2 次升级图形验证码（双端按需 UI，脚本零破坏）；L2-4 Redis 密码（dev devredis/生产 :? 强制）；L2-6 BigScreen/PrintView 分页拉全；L2-10 merchant dev 构建链。门禁演进：scheduled-task-seed 识别迁移内显式 DELETE。插件：删 Mapper 漏删 XML 致启动崩（lessons #233），fix-forward 收口 | 报告 §12、`lessons-learned.md` #233 |
| 2026-09-27 | **修复轮**（§11）：P0 Grafana（强口令+端口回环+网关 Basic，实测 401/200）✅；P1 MinIO 匿名桶收紧（匿名 403）✅；V1 RECON 默认 true+production 钉死；X2 staging 钉四 mock+RECOGNIZER；**F2 免单旁路**（状态机加 SHOPPING→DISPUTED+C09 对齐争议单）；F3 提现 sweep 仅 MOCK+转人工+PAYING 60 分钟语义；X1 preflight 结构档默认强制；S2 删 GET query token（全仓无消费方）；P2：CsvCells 中和、MISMATCH 告警、A1 充值契约、三端门禁扫描根、C1/C2、WalletPage 前置校验、BigScreen 重入、shared dist 重建。**三端 tsc+84/121/130 单测全绿；预检 47✓+1 待提交**。未修：F1/S1/PII 尾巴（§11.3） | 报告 §11、`SessionState/SessionExpireService/WithdrawService/CsvCells` 等 |
| 2026-09-27 | 收 S3 PARTIAL：公开 `GET /api/v2/public/help` `/policies`；网关 `/consumer/` `/merchant/` 不再 302 后台；G5/G6 对齐债表 | `PublicLegalService`、`nginx-full.conf`、`mp-h5/*/index.html` |
| 2026-09-27 | S0–S3+整轮回归收口：`08fca633` push；CI `36316095037` 绿；会话提示词标 DONE | `SESSION_PROMPT_S3_CONTINUE.md`、`full-regression/FINDINGS.md` |
| 2026-09-27 | 整轮回归：KeepPlatform→milk PASS=10/FAIL=1（对账假红）→修 `@()`+run；finance 复验绿；#228 | `full-regression/FINDINGS.md`、`e2e-full-flow-milk.ps1` |
| 2026-09-27 | S3 后台子复检：mp-seed-gate pass（柜 1668… ONLINE、余额 19750、钱包 50440；未 Wipe） | `s3-mp/GATE_CHECK.md`、`.tmp/mp-seed-gate.json` |
| 2026-09-27 | S3 缺口收口：device-detail `?id=`；采购单4/调拨号纯数字 cancel；兑换#4 须 couponDefId；#227 | `s3-mp/FINDINGS.md`、`s3-wh-*-soft.png`、`s3-b2-points-redeem-soft.png` |
| 2026-09-27 | S3 附录 A mp 页矩阵：消费/商户各 22 路由 L1 PASS（多页 L2）；余额 ¥197.50；商户「补货与运营」 | `s3-mp/FINDINGS.md`、`s3-c-*.png`、`s3-m-*.png` |
| 2026-09-27 | S3 mp：DevTools 超时打到死局域网 IP（像没样式）；sync 优先 127.0.0.1；lesson #226 | `sync-consumer-mp-api.mjs`、`s3-mp/FINDINGS.md`、`s3-c-mine-api-fix.png` |
| 2026-09-27 | S3 续测：用户余额 L3 ¥197.50↔19750；积分兑换/线长/公告新建→取消；C5 待审已驳回解冻 | `s3-users-balance-l3.png` · `s3b2-points-redeem.png` · `s3-line-managers-empty.png` |
| 2026-09-27 | S3 收口：A6 开票空壳；C1/C5 驳回→取消软写（¥500 待审）；D3 帮助/条款为 mp 静态、公开 API 404 | `s3a6-invoices-empty.png` · `s3c5-reject-cancel.png` |
| 2026-09-27 | S3 缺口补测：A3 驳回→取消仍待审；A5 账户 payscore 就绪（H5 SKIP）；B2 积分35+会员等级；B3 公告空；仓配盘点号 `1790502046806410365000` 纯数字后 cancel | `s3-recharge/` · `s3-coupon/` · `s3-wh-stocktake-digits.png` |
| 2026-09-27 | UAT 复检：商户提现业务单号 `4145309934143`、余额退款 `157961441034125857` 均为纯数字；CI build 绿（Emqx IT 曾抖后 rerun 全绿） | `s3c-05c-withdraw-bizno-recheck.png`、`s3a3-balance-refund-digits.png` |
| 2026-09-27 | fix：渠道幂等号勿改纯数字（还原 BR/PSR）；STK 单测改数字断言；lesson #225 | `BalanceRefundService`、`RevenueSplitService`、`WarehouseStocktakeServiceTest` |
| 2026-09-27 | fix(test)：V287 后 Java E2E/IT 禁写死 CAB-001；`DemoFixture` + Consumer/Merchant/Reconciliation；lesson #224 | `DemoFixture.java`、三测类、`lessons-learned.md` |
| 2026-09-27 | 业务单号纯数字扫尾：支付退款/调账/仓配/盘点/分账批次/报废等发号 + Admin 漏列 displayBizNo；SKU/批次/协议号不动；#223 | `BizIds`、`PaymentService`、仓配/分账视图 |
| 2026-09-27 | S3-D1 软写：反馈#13 PENDING + 报修→异常中心待处理；D2/D3 SKIP/PARTIAL；H5 未挂 | `s3-ops/FINDINGS.md` |
| 2026-09-27 | S3-C 商户旁路：要货软写待审 / 分析 / 改价 version / 团队 / 提现阈值自动 PAID / 临期空；Wipe 后须重绑 ops_user_merchant（#221/#222） | `s3-merchant/FINDINGS.md` |
| 2026-09-27 | 订单列表「商品 / 货道」分列：从 lineSummary 解析 `·货道A1`，商品只留名×量 | `OrderListView.vue`、`order-line-summary.ts` |
| 2026-09-27 | lessons #219 sim 门卡 OPEN 须 `/close`；#220 recharge mock 路径须 `/dev/` | `lessons-learned.md` |
| 2026-09-27 | S3-B1 发券抵扣 PASS：AMOUNT_OFF ¥1 发至用户→二次购物原¥3.50实付¥2.50；券 USED；sim 门卡 OPEN 需 `/close` | `s3-coupon/FINDINGS.md` |
| 2026-09-27 | S3-A1..A4：充值+¥10 / 流水 API / 退余额软写待审 / 支付分开通后优先渠道可改回；H5 consumer 未挂 | `s3-recharge/FINDINGS.md`、`e2e-consumer-marketing-recharge.ps1` |
| 2026-09-27 | S3-A1 充值 PASS：mock +¥10→20650；Admin 充值管理同单；H5 `/consumer/` 未挂载 SKIP；修 marketing-recharge mock 路径 `/dev/` | `s3-recharge/FINDINGS.md`、`e2e-consumer-marketing-recharge.ps1` |
| 2026-09-27 | S2 购物主链 PASS：BALANCE 出单 ¥3.50→分账仅记账商户¥3.15→钱包 3.15；A1 6→5；Admin 订单/分账/钱包/财务/工作台截图 | `s2-shop/FINDINGS.md`、`.tmp/s2-order.json` |
| 2026-09-27 | S1 空台搭完：商户/仓/供/SKU/柜+A1 库存6；模拟器用运行时柜号上线（禁 compose 写死号）；`.tmp/s1-merchant.json` | `s1-stage/`、device-simulator |
| 2026-09-27 | 严格空台→S1：Wipe 后勿重启 trade（DemoDataBootstrap 会回种）；浏览器搭商户/仓/SKU/柜；lessons #218 | `DemoDataBootstrap`、`.tmp/s1-merchant.json`、`s1-stage/` |
| 2026-09-27 | WipePlatform 再扩：补货路线/券定义/对账/一致性/公告；清前停模拟器防柜机复活；仓库角标标明「个列表」非条数 | `cleanup-test-data.ps1`、`WarehouseView.vue` |
| 2026-09-27 | S0 默认 WipePlatform：清柜/商户/SKU/仓/供应商+余额归零，从空台子进 S1；`-KeepPlatform` 保留旧行为 | `cleanup-test-data.ps1`、场景真源 S0 |
| 2026-09-27 | FullBusiness 清数扩围：支付流水/事件/库存流水/仓批/柜库存清零（台子主数据保留） | `cleanup-test-data.ps1`、场景真源 S0 |
| 2026-09-27 | PS 购物车 `"$E2eSku:1"` 作用域坑 → `"${E2eSku}:1"`；lessons #217 | `e2e-shopping` 等 set-simulator-cart 调用点 |
| 2026-09-27 | 脚本漏网清完：three-end/SKU、run-api-tests/柜号、security 并发幂等、gray 灰度柜均走 Resolve | `e2e-three-end`、`run-api-tests`、`security-concurrency-recon-test`、`phase-f-gray-launch` |
| 2026-09-27 | E2E SKU/批次运行时解析：`Resolve-E2eTestSku/Batch`；脚本去 SKU-DEMO；Admin CSV 样例改 EXAMPLE；lessons #216 | `e2e-lib`、`e2e-*.ps1`、`SkuVisionEnrollView`、`DemoDataService` |
| 2026-09-27 | E2E/完整轮去写死默认柜商户仓：Resolve + 删 WH 废弃常量；Admin 占位清 CAB/上海坐标；lessons #215 | `e2e-lib`、`e2e-*.ps1`、`full-round-*.mjs`、Admin 设备/视觉页 |
| 2026-09-27 | 去写死点位/仓/供应商/货道 SKU：Demo 不填上海地址；仓供 12 位发号；货道模板空陈列；lessons #214 | `DemoDataService`、`WarehouseSupplierIdService`、`PlanogramTemplateService` |
| 2026-09-27 | 商户号对齐柜机：系统 12 位发号、新建禁手填；Demo/脚本动态解析；lessons #213 | `MerchantIdService`、`MerchantService`、`MerchantSplitsView`、`e2e-lib` |
| 2026-09-27 | 柜机号禁止写死：DemoData 选库内柜否则 `allocateRandomDeviceId`；模拟器/脚本走 resolve；V287 剔孤儿 CAB-001；lessons #212 | `DemoDataService`、`DeviceIdService`、`start-local.ps1`、`V287` |
| 2026-09-27 | 清数默认 FullBusiness；柜机 `Resolve-E2eTestDevice` 不写死；闸门读动态 deviceId | `cleanup-test-data.ps1`、`e2e-lib`、`mp-seed-gate.ps1`、场景真源 S0 |
| 2026-09-27 | 场景真源改价值链：S0清数→S1台子→S2主链→S3旁路 + 页矩阵附录；闸门支持 `-CleanupFirst` | `MP_THREE_END_SCENARIOS.md`、`mp-seed-gate.ps1`、`cleanup-test-data.ps1` |
| 2026-09-27 | 三端场景清单 P0/P1/P2（46）+ 造数闸门 `mp-seed-gate.ps1`；自动化前须闸门绿 | `MP_THREE_END_SCENARIOS.md`、`scripts/mp-seed-gate.ps1` |
| 2026-09-27 | 双端 mp 基础烟测 PASS（各 19 页 L1+多页 L2；软写；未启 H5）；视频 404 诚实失败；首页首帧白屏记 #211 | `mp-smoke/FINDINGS.md`、`MINIPROGRAM_BACKLOG` PARTIAL 回填 |
| 2026-09-27 | 新会话提示词：先双端 mp 烟测再写三端脚本；L1/L2/L3 + 软写约定 | `docs/uat/MP_SMOKE_SESSION_PROMPT.md` |
| 2026-09-27 | 小程序回补 P0#1：消费者余额/充值 ¥193.00↔Admin；充值单号一致；未硬充 | `recharges/MP_BACKFILL.md`、`mp-c-p0-*.png`、`MINIPROGRAM_BACKLOG` |
| 2026-09-27 | 观测栈在线：Grafana/Prom iframe 实嵌；DevOps hint 改为需登录一次；SYS-DEVOPS/OBS-03 | `OBS_STACK_ONLINE.md`、`dv-online-*`/`ob-online-*`、`DevOpsHubView.vue` |
| 2026-09-27 | 日志中心 hint「5→6」已修 + admin-static 复核；Phase7 十四入口单页深测收口 | `ObservabilityView.vue`、`ob-fix-hint6.png`、`UAT_CLOSEOUT` |
| 2026-09-27 | 日志中心单页深测：六签/Grafana 空；SYS-OBS-02（F1 后闭） | `OBSERVABILITY_FULL_BROWSER_UAT.md`、`observability/BUTTONS`/`FINDINGS` |
| 2026-09-27 | DevOps 中心单页深测：四卡/PromQL stub/Grafana 空/Sonar 禁用；SYS-DEVOPS-02 | `DEVOPS_FULL_BROWSER_UAT.md`、`devops/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 审计日志单页深测：共244/筛/仅看我的98/导出；SYS-AUD-02 | `AUDIT_FULL_BROWSER_UAT.md`、`audit/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 通知公告单页深测：共1/发布编辑归档取消/导出；SYS-ANN-02 | `ANNOUNCEMENTS_FULL_BROWSER_UAT.md`、`announcements/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 组织与点位单页深测：三Tab/组织写取消/合同空/账单出账取消；SYS-ORG-02 | `ORG_SITES_FULL_BROWSER_UAT.md`、`org-sites/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 定时任务单页深测：共32/新建编辑执行批取消/开关未拨；SYS-TASK-02 | `SCHEDULED_TASKS_FULL_BROWSER_UAT.md`、`scheduled-tasks/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 告警规则单页深测：共25/新增toast/编辑删取消/测试SKIP；SYS-ALERT-02 | `ALERT_RULES_FULL_BROWSER_UAT.md`、`alert-rules/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 参数配置单页深测：共76/品牌未硬写/三写取消/历史空态/导出；SYS-CFG-02 | `SYSTEM_CONFIGS_FULL_BROWSER_UAT.md`、`system-configs/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 字典管理单页深测：类型89/六写取消/导出模板/导入SKIP；SYS-DICT-02 | `DICTS_FULL_BROWSER_UAT.md`、`dicts/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 菜单管理单页深测：运营194/商户39/四写取消/导出；SYS-MENU-02 | `MENUS_FULL_BROWSER_UAT.md`、`menus/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 审批流配置单页深测：共7/展开/新建编辑流程图删除取消；SYS-APR-02 | `APPROVALS_FULL_BROWSER_UAT.md`、`approvals/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 部门管理单页深测：共6/新建编辑成员批停取消；SYS-DEPT-02 | `DEPARTMENTS_FULL_BROWSER_UAT.md`、`departments/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 角色管理单页深测：共12/筛/编辑权限抽屉停用取消/导出模板；SYS-ROLE-02 | `ROLES_FULL_BROWSER_UAT.md`、`roles/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 运营账号单页深测：共11/手机脱敏筛/六写取消/导出模板；SYS-OP-02；系统册开测 | `OPERATORS_FULL_BROWSER_UAT.md`、`operators/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 用户反馈单页深测：共8/状态筛深链/回复删取消/用户设备链/导出；GR-FB-02；增长风控十一页深测收口 | `FEEDBACK_FULL_BROWSER_UAT.md`、`feedback/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 消息记录单页深测：共2/发送编辑删批删取消/导出/受众OPS裸码·业务未知FINDING；GR-NTF-02 | `NOTIFICATIONS_FULL_BROWSER_UAT.md`、`notifications/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 活动效果分析单页深测：空态0/近7·30·90切档/导出空toast；GR-ROI-02 | `MARKETING_ROI_FULL_BROWSER_UAT.md`、`marketing-roi/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 会员等级规则单页深测：共4/新建编辑取消/批停取消/行启停无确认FINDING；GR-ML-02 | `MEMBER_LEVELS_FULL_BROWSER_UAT.md`、`member-levels/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 积分兑换管理单页深测：空态0/新建取消/行启停无确认FINDING；GR-PTS-02 | `POINTS_REDEEM_FULL_BROWSER_UAT.md`、`points-redeem/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 投放计划单页深测：空态0/新建取消/行上线停止无确认FINDING；GR-CAMP-02 | `AD_CAMPAIGNS_FULL_BROWSER_UAT.md`、`ad-campaigns/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 素材库单页深测：空态0/上传面板取消(stub文件框)/批停无确认FINDING；GR-AD-02 | `AD_ASSETS_FULL_BROWSER_UAT.md`、`ad-assets/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 优惠券单页深测：共1完整轮满减券/新建手动批量发券编辑停用批停均取消/深链；GR-CPN-02 | `COUPONS_FULL_BROWSER_UAT.md`、`coupons/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 营销活动单页深测：空态0/状态中文/新建取消/深链/下载模板/导入SKIP；GR-PROMO-02 | `PROMOTIONS_FULL_BROWSER_UAT.md`、`promotions/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 风控单页深测：事件24/用户链/黑名单空/加黑取消(userId未预填)/深链/双导出；GR-RISK-02 | `RISK_FULL_BROWSER_UAT.md`、`risk/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 手机验证流水单页深测：共81/渠道中文四档/登记编辑删除取消/导出；GR-PV-02 | `PHONE_VERIFY_FULL_BROWSER_UAT.md`、`phone-verify/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 用户余额单页深测：共15/关键词空命中/调账取消/核验取消/深链/导出；FIN-USR-02 | `USERS_FULL_BROWSER_UAT.md`、`users/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 开票申请单页深测：空态0/仅状态Alert/状态中文筛/批量disabled/行写SKIP | `INVOICES_FULL_BROWSER_UAT.md`、`invoices/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 余额退款单页深测：五Tab空态0/批量disabled/行审核SKIP | `BALANCE_REFUNDS_FULL_BROWSER_UAT.md`、`balance-refunds/BUTTONS`/`FINDINGS` |
| 2026-09-27 | 开单小程序回补待办：Admin 已深测模块按 P0/SKIP 分类；测 mp 时按表回填 | `docs/uat/MINIPROGRAM_BACKLOG.md`、OVERVIEW §8 链、FINANCE 元信息 |
| 2026-09-26 | 充值管理单页深测：共3/状态筛深链/退款取消/userId正整数校验/导出 | `RECHARGES_FULL_BROWSER_UAT.md`、`recharges/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 数据一致性单页深测：空态0/类型21中文/关键词前端滤/立即巡检全部通过 | `CONSISTENCY_FULL_BROWSER_UAT.md`、`consistency/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 对账单页深测：2批/执行取消/渠道筛OK；status·keyword后端未滤FINDING | `RECONCILIATION_FULL_BROWSER_UAT.md`、`reconciliation/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 商户提现单页深测：钱包2/提现1已打款/调账代提现取消/批量disabled；`?tab=`未切FINDING | `MERCHANT_WITHDRAW_FULL_BROWSER_UAT.md`、`merchant-withdraw/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 线长钱包单页深测：三Tab空态/新建取消/地推toast门控/批量disabled；`?tab=`未切FINDING | `LINE_MANAGERS_FULL_BROWSER_UAT.md`、`line-managers/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 进件工作台单页深测：空态0/仅登记hints/新建取消/渠道状态中文筛/批量disabled | `MERCHANT_ONBOARDING_FULL_BROWSER_UAT.md`、`merchant-onboarding/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 商户与分账单页深测：四Tab/新建编辑挂载取消/确认完结取消/功能包未拨/深链splits | `MERCHANTS_FULL_BROWSER_UAT.md`、`merchants/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 资金账单单页深测：日5/明细62/¥/90天门控/关键词hint/双导出 | `FUND_BILLS_FULL_BROWSER_UAT.md`、`fund-bills/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 补货员效率单页深测：7/30/90、关键词total诚实、导出CSV、无硬写 | `REPLENISHMENT_STAFF_FULL_BROWSER_UAT.md`、`replenishment-staff/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 服务时限单页深测：8KPI↔API/#198投放分母/时长可读/实时六项/仅刷新 | `SLA_FULL_BROWSER_UAT.md`、`sla/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 固件版本单页深测：双空态/发布确认取消/进度中文筛/窄视口 | `OTA_FULL_BROWSER_UAT.md`、`ota/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 仓库单页深测：四分组13Tab/新建弹层取消/清理空草稿取消/在途overdue深链 | `WAREHOUSE_VIEW_FULL_BROWSER_UAT.md`、`warehouse-view/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 补货调度单页深测：KPI↔summary/五Tab/规划·空路线取消/#203深链弹层/要货筛 | `REPLENISHMENT_FULL_BROWSER_UAT.md`、`replenishment/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 录像上传单页深测：WAITING_UPLOAD 空态/仅滞留/状态下拉中文/device·session 深链/videos 重定向 | `UPLOAD_QUEUE_FULL_BROWSER_UAT.md`、`upload-queue/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 识别映射单页深测：关键词q/深链/YOLO新增·编辑·删除取消/阿里云空态 | `VISION_MAPPINGS_FULL_BROWSER_UAT.md`、`vision-mappings/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 识别入驻单页深测：Chip/Tab/关键词q/配置·编辑·批量下架取消/识别测试关闭 | `SKU_VISION_FULL_BROWSER_UAT.md`、`sku-vision/BUTTONS`/`FINDINGS` |
| 2026-09-26 | 选品诊断深测：关键词 total 假绿→#210 修复；批量下架/保留取消 | `SKU_REVIEW_FULL_BROWSER_UAT.md`、`sku-review/`、`SkuReviewView`、lessons #210 |
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
| G5 | consumer-mp 技术债 | C1–C12 **done**（C9 deferred）；余 **C10b** easycom→package（mp 风险知情延后） |
| G6 | merchant-mp 技术债 | M1–M12 **done**（除 M9）；余 **M9** 与 C10b 对齐 |
| G7 | 灰度真金 vision | mock 仍开；关 `VISION_MOCK_ENABLED` 须边侧识别就绪，禁止本机 UAT 假关 |

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
