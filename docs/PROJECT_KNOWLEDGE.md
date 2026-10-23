# AI Cabinet 全局共享项目知识（Living Doc）

> **地位**：本仓 Agent / 人工协作的**唯一总入口**。细则仍散落在规则、Skill、专题文档里；本文件负责**索引 + 现状摘要 + 变更账本**。  
> **维护规则**：`.cursor/rules/project-knowledge-living.mdc`（每次相关对话必读、必补）。  
> **维护 Skill**：`.cursor/skills/project-knowledge/SKILL.md`  
> **已解决问题 Skill**：`.cursor/skills/solved-problems-playbook/SKILL.md`  
> **封装流程 Skill**：`.cursor/skills/encapsulate-solved-problem/SKILL.md`  
> **最后校准**：2026-10-07（§2 规模数字已按当日实测回填）

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

**AI 开门柜**：消费者扫码开门取货 → 将邑端侧识别（柜机 rknn 推理）→ 自动结算扣款；运营后台管设备/SKU/争议/财务；商户端管补货/定价/钱包。独立于旧仓 `ego-automat`（只读参考）。

---

## 2. 仓库现状速览（2026-09-24 校准）

| 维度 | 当前值 | 备注 |
|------|--------|------|
| Flyway 迁移 | **379** 个脚本（仅 `services/trade-service`），最新 **V385** | 合入前 `ls V*` 查重号；门禁拦同号；勿再写已占用版本号 |
| trade Controllers | **93** | `*Controller.java` |
| trade 单测 | **380** `*Test.java` | 含大量并发测；全量 `mvn -o -pl services/trade-service -am test` |
| admin-vue 业务视图 | **83** `.vue` | `src/views` |
| consumer-mp / merchant-mp | 独立 uni-app | 分包 + `preloadRule`；H5 `:3002` / `:3001` |
| shared packages | types / api / dict / rbac / uni | 改 API 后 `pnpm gen:api-types` |
| 踩坑总册条目 | **319 号 / 310 行**（编号不连续） | `docs/engineering/lessons-learned.md` |
| 竞品对照台账 | **18 条 / 96 行含 URL** | `docs/COMPETITOR_BENCHMARK.md`；门禁 `check:competitor-benchmark` |
| 审计门禁 | `pnpm check:audit-gates` | 新建脚本须进 `ci.yml` |
| 将邑开门柜接入 | `services/jiangyi-gateway` **:8083**（全栈 Docker `127.0.0.1:18084`） | 模式一外采套件，CB-022；方案+现状速查 `docs/JIANGYI_GATEWAY_DESIGN.md`；`JIANGYI_JWT_SECRET` 未配置则 fail-closed 503 |

> **最后校准**：2026-10-17（Flyway/Controllers/单测/视图/总册 五行为**当日实测**，其余行沿用上次校准）。
> ⚠️ **规模数字会漂移** —— 读到过期的数字时，先按上面的命令实测再下结论，不要引用历史值。

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
services/jiangyi-gateway   将邑外采柜接入层（设备 token/WS/上报归一，CB-022）
services/common/common-core 共享 DTO / 枚举 / 内部鉴权
edge/*                     柜机端 / 模拟器
infra/                     Compose、网关、监控
```

完整表：[MODULES.md](MODULES.md)。

---

## 4. 关键约定（不可破）

- 🔴 **不明白的逻辑/情况先停下问用户，禁止自己猜**（2026-10-14 新规）：业务语义、口径、形态不确定时先问（如：结算=日结 T+1 而非月度、收益下单即入账商户自行提现而非打款、无柜机屏硬件），猜错返工成本 > 一次提问。

| 主题 | 约定 |
|------|------|
| 对外 API | `/api/v2/...`；成功 `ApiResponse.code=0` |
| 服务间 | `/internal/v1/...` + `X-Internal-Api-Key` |
| DB | Postgres `localhost:15433/aicabinet`；只许 Flyway |
| 鉴权 | 生产标准；本地 `dev` + mock，禁关安全边界图省事 |
| 文案 | 用户可见须中文（`ui-copy-zh`） |
| 验收口径 | **mp-weixin 是双小程序唯一验收权威**（2026-09-26 定）；2026-10-05 收紧：**小程序 H5 不作验收面、不再为它投入修复**（dev:h5 起不来/双 vue 错配均不修）。注意：CI `e2e-h5` job 混装——consumer/merchant H5 两步可按冒烟看待，但同 job 内 three-end business / admin console / role-regression UAT 跑在**运营后台**，不受此口径影响、仍必须绿 |
| UI Token | 禁裸业务 hex；`z-index` 用 `--z-*`；表格列语义 class |
| 金额单位 | **存储 + 接口 JSON 一律「分」（整数、字段名必带 `Cents`）；展示一律走各端唯一认可的「分→元」函数；表单输入按元、写回前 `yuanToCents` 转分**。两处「元」例外见 §6.1 |
| 改 API | common-core DTO + 调用方 + `shared-types` 同步 |
| 密钥 | 环境变量；禁止提交 |

核心规则：`.cursor/rules/project-core.mdc`。

---

## 5. 本地两种模式（测试前先选）

| 模式 | 启动 | trade 端口 |
|------|------|------------|
| **A. IDEA + 仅 infra**（日常推荐） | `docker compose -p ai-cabinet -f infra/docker-compose.yml up -d` + IDEA（trade/device；将邑柜联调另起 jiangyi-gateway） | **:8080** |
| **B. 全栈 Docker** | `.\docker-up.ps1` | **:18080** |

**禁止** A/B 混开双 trade。速查：[STARTUP_REFERENCE.md](STARTUP_REFERENCE.md)。
jiangyi-gateway 独立 Spring Boot（模式 A 下 IDEA 起 :8083；模式 B 下 compose 容器 `127.0.0.1:18084`），nginx `/jiangyi/` 与 `/websocket/` 均转发至该服务。

| 面 | URL / 账号 |
|----|------------|
| 运营后台 | `http://localhost/admin/index.html` 或 `:8080/admin/...`；`13900000001` / `123456` + 验证码 |
| 消费者 H5 | `:3002` |
| 商户 H5 | `:3001` |
| Admin 构建 | `node scripts/build-admin.mjs`（Cursor 下 `pnpm run build:admin` 可能因 script-shell 失败） |

---

## 6. 核心业务链路（金钱正确性最高优先级）

```
扫码/登录 → POST /api/v2/sessions → 预授权 → 开门（生产仅端侧识别柜可购物，mock 豁免）
  → SHOPPING → 关门 → RECOGNIZING（端侧识别柜停在此等上报，不本地结案）
  → POST /internal/v1/vision/edge-results（jiangyi-gateway 归一 + 合成 CLOSED 后转发）→ Settlement
  → 高置信：出单 + 扣库存 + 扣款
  → need_review / 低置信 / 无结果：DISPUTED（先不扣款）
```

> 识别**只有端侧上报一种来源**（2026-10-11 拍板、2026-10-17 实删）：云端 `vision-service` 与重力兜底/dev-cart 结算短路已整体退役（`settlement.recognition_mode`、`aicabinet.gravity-fallback-settle` 两开关不存在）。重力信号只作取证与展示（`shopping_session.gravity_deltas` → 实时购物车、补货货道快照、争议分类）。CI 与本地演示的「结算绿」同样必须由「柜机登记 `jiangyi_device` BOUND + edge-results 上报」挣得，见 `ConsumerE2ETest` 步骤 5–10。

会话状态机：`CREATED → OPENING → SHOPPING → WAITING_UPLOAD → RECOGNIZING → SETTLING → COMPLETED`（旁路禁 `setState`，走 `SessionService.transition`）。

分布式锁前缀 `aicabinet:lock:`（开门 / 会话 / 结算 / 支付等），详见 [CODEBASE_FOUNDATION.md](CODEBASE_FOUNDATION.md) §3。

### 6.1 金额单位口径：全链路「分」+ 两处「元」例外（2026-10-16 三端审计）

**不变式**：DB 列、Java/DTO 字段、HTTP JSON 载荷 = **分（整数）**，字段名必带 `Cents`；前端展示 = 元，且只能经各端**唯一认可**的格式化函数；表单/输入框按元收，写回前 `yuanToCents()` 转分。三端逐条扫过（含后端 DTO 全量核对），**未发现错误单位载荷、也未发现双重换算**——残余风险只在「重复实现 / 命名不带单位」，不在钱算错。

| 端 | 认可的展示函数 | 说明 |
|----|----------------|------|
| admin-vue | `yuanText` / `yuanTextZero` / `yuanTextNone`（[utils/display.ts](../clients/admin-vue/src/utils/display.ts)） | 输出**自带 `¥`**，负数 `-¥5.00`；空态分别 `未统计 / ¥0.00 / 暂无`。bps 用同文件 `bpsText`（60 ⇒ `0.60%`） |
| 双小程序 | `fmtMoney`（[packages/shared-uni/src/format.ts](../packages/shared-uni/src/format.ts)） | 同样**自带 `¥`** |
| 需要不带符号的元数字 | `centsToYuanDigits()`（同上文件，2026-10-16 补） | 用于 `<input>` 预填、「列头已写 (元)」的数字位。此前缺这个函数是各页自己写 `/100).toFixed(2)` 绕过格式化的根因 |

**两处「元」例外（有意保留，不是漏网）**：

1. **会员域**：`member.total_spent`、`member_level_rule.min_spent / max_spent / min_recharge` 是 `DECIMAL(12,2)` **元**，由 `BigDecimal.valueOf(cents, 2)` 从分换算写入。所以 [MemberLevelsView.vue](../clients/admin-vue/src/views/growth/MemberLevelsView.vue) 里的 `yuanOfYuan()` **入参已经是元，只加符号、绝不能再 ÷100**——名字刻意与全站「入参分」的 `yuan()`／`yuanText()` 区分。
2. **平台资金两个配置**：`platform.fund.plan.buffer_yuan`、`platform.fund.recon.tolerance_yuan` 按元存、读取用 `getDouble` 后立即 `yuanToCents()`。键名带 `_yuan` 即声明单位。

**同名不同物的两个「押金」**（问一次答一次，别再现场推断）：`device_info.deposit_cents` = 消费者**开门押金/预授权冻结额**（分，>0 时覆盖 `checkout.preauth_cents` 默认 2000 分 = ¥20）；`device_rental_deposit.amount_cents` = CB-034 **租赁押金本金**（分，另有 monthly_refund / expire_reward / install_fee_deduct 三列；装机抵扣默认 **0 分**——V383 归 0，登记按柜配参数，原「默认 10000 分」口径已废，2026-10-18 复审订正）。

### 6.2 分账状态机 + `platform_rate_bps=0` 的确切含义（2026-10-17 拍板）

`order_revenue_split.status`：`ACCRUED`（待提交微信分账，**钱包未入账**）→ `WECHAT_SUBMITTED`/`SUCCESS`；`LEDGER_ONLY`（不发分账，商户份额已按 `SPLIT`/`splitId` 幂等入**可提现钱包**）→ `SETTLED`（`confirmLedgerOnly`）；`WECHAT_FAILED`（可重试）；`VOIDED`/`REVERSED`（全额退款冲正）。

- **默认档位是 0=平台不抽成**（V382；实体 `Merchant.platformRateBps`、`doUpsertMerchant` 缺省、运营台表单四处同口径）。要抽成由运营按商户显式填，历史 `order_revenue_split` 行**永不回改**。
- **0 不只是「算出来 platform=0」，而是「不发分账请求」**：rate=0 ⇒ `receivers[0].amount == 订单毛额`，微信服务商是否允许全额分账**未实证**（CB-032 的 30% 上限仅 ⚪ 旁证）。三道闸：`doRecordSplit` 落 `LEDGER_ONLY`、`doResyncSplitForOrder` 把未提交的 ACCRUED **翻态并按全额入钱包**（补差额会少付商户 `oldMerchantCents`，lesson #313）、`prepareSubmit` 终态不发 HTTP（兜运营手工提交 + 失败重试两个入口）。
- 商户无 `wechat_receiver_id` 时同样走 `LEDGER_ONLY`——**与费率无关**，这是「账本型分账」的原有语义。
- 门禁/用例：`RevenueSplitServiceTest`（rate=0 两例）、`WeChatProfitSharingServiceZeroRateTest`（三例，含 rate>0 对照组钉 `amount==merchantCents`）。

**门禁**：`pnpm check:money-yuan-prefix`（已入 `check:audit-gates`）钉两件事——① 模板写字面 `¥` 又调用「自带 `¥`」的函数（渲染成 `¥¥12.34`、负额 `¥-¥5.00`；2026-10-16 清掉商户端 16 处 + 运营台 25 处）；② `.replace(/^¥/, '')` 剥符号手术（锚 `^` 对 `-¥5.00` 不匹配 ⇒ 只剥了正数）。它**先解析每个文件里标识符的绑定来源**再判，不做全局字符串匹配（第一版全局匹配误报 27 处）。

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
| [THREE_END_FULL_REVIEW_2026-09-27.md](archive/audit/THREE_END_FULL_REVIEW_2026-09-27.md)（已归档） | 三端全面审查结论（无 P0/11 P1，**已全部修在位**）、mock 开关总表、接真前 checklist | 历史取证；缺陷编号与修复状态以 [AUDIT_LEDGER_2026-10-13.md](AUDIT_LEDGER_2026-10-13.md) 为准；其余旧审计快照同在 `docs/archive/audit/`（定性见其 README） |
| [engineering/lessons-learned.md](engineering/lessons-learned.md) | 踩坑总册 |
| [engineering/admin-vue-debt-tracker.md](engineering/admin-vue-debt-tracker.md) | 运营后台技术债进度（D1–D25 已清） |
| [engineering/consumer-mp-debt-tracker.md](engineering/consumer-mp-debt-tracker.md) | 消费端小程序技术债（C1–C12，mp-weixin 权威） |
| [engineering/merchant-mp-debt-tracker.md](engineering/merchant-mp-debt-tracker.md) | 商户端小程序技术债（M1–M12，mp-weixin 权威） |
| [CODE_FIX_CHECKLIST.md](CODE_FIX_CHECKLIST.md) | 改完自检 |
| [TROUBLESHOOTING_GUIDE.md](TROUBLESHOOTING_GUIDE.md) | 联调排障 |
| [SATELLITE_WAREHOUSE.md](SATELLITE_WAREHOUSE.md) | 分仓四步（人绑仓 → 采购进分仓 → 仓到柜 → 月结）；不要抄 easygo 批发、不要把要货当天入口 |
| [AUDIT_LEDGER_2026-10-13.md](AUDIT_LEDGER_2026-10-13.md) | 上线前审计台账（AU-xxx 发现 / DC-xx 拍板项的**唯一编号源**）；有人说「AU-134 是什么」时来这查 |
| [AUDIT_B1_EXTENDED_FUND_2026-10-16.md](AUDIT_B1_EXTENDED_FUND_2026-10-16.md) | 资金域扩展审 F-1…F-8（V355/V359/V361 三链，幂等根因）；动钱包/佣金/费用扣款前读 |
| [AUDIT_DECISION_BRIEF_2026-10-16.md](AUDIT_DECISION_BRIEF_2026-10-16.md) | **待拍板简报**：DC-26/27/29 落地实证 + §12「还剩什么要你定」；想知道项目卡在哪些决策上读这一份 |
| [AUDIT_F2_F3_F4_DECISION_BRIEF_2026-10-16.md](AUDIT_F2_F3_F4_DECISION_BRIEF_2026-10-16.md) | 费用口径（分/天、扣款即结清、账龄锁柜、按日期重跑）拍板与落地状态表 |
| [AUDIT_THREE_END_FULL_2026-10-18.md](AUDIT_THREE_END_FULL_2026-10-18.md) | 最新三端全量复审（AU-136~173，编号源在台账；子代理明细见 [AUDIT_THREE_END_FULL_2026-10-18_APPENDIX.md](AUDIT_THREE_END_FULL_2026-10-18_APPENDIX.md)）；想知道「历史审计报告哪些还能信」读其 §5 核查矩阵（失实 17/虚报 0） |

### 7.2 Cursor 规则（`.cursor/rules/`）

| 规则 | 作用 |
|------|------|
| `project-knowledge-living` | **本活文档**：必读必补 |
| `competitor-benchmark-before-code` | **写业务代码前先对照竞品/官方做法**；台账 [`COMPETITOR_BENCHMARK.md`](COMPETITOR_BENCHMARK.md)；门禁 `check:competitor-benchmark` |
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
| `memory/EDGE-ANDROID-GRADLE.md` | 边缘端 Gradle | 历史留档（自研 `edge/android-app` 已于 2026-10-18 退役，仅 git 历史参考） |
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
| Edge | MQTT 队列丢事件、TLS truststore、Prefs apply (#88,91,94) | 自研 android-app 已退役 2026-10-18（经验留档）；现边缘侧 = device-simulator |

**配方级复用**：优先 `solved-problems-playbook` Skill，再下钻总册行号。

---

| 2026-10-18 | **三端全量复审（8 路只读审计 + 历史文档逐条核查 + 终轮源码级复核，HEAD=28c9f99a）**：新发现 **P0×2**（AU-136 `/auth/wx-h5-login` 客户端明文手机号可绑任意账号含运营号段——C13 防线的 H5 旁路，生产未配 H5 OAuth 前不可触发、配置即炸；AU-172 `slot_template`/`slot_template_line` `@TableId` 缺 `value="id"` 与 V368 DDL 主键 `id` 错位——陈列模板新建/查详情/删除全链 500，225 张表探针唯二真实失配）+ P1×3（AU-137 余额退款续退链幂等键按 requestNo 恒定→多轮复审静默吞资金且无流水可查；AU-138 商户盘点页旧正则剥 EG 前缀→新编号设备盘点必失败；AU-173 `platform-fund-daily`/`device-rental-daily` 是 39 任务唯二无 tryBegin——无分布式锁多副本双跑 + `scheduled_task.enabled` 停用开关被旁路，上线手册「保持关」关不住）+ P2×10/P3×22（AU-139~171）；历史文档核查约 150 条声明**失实 17、虚报 0**——09-27/10-05 两轮报告判断层零误报、修复全在位，债务集中在**文档不回写**（FIX_QUEUE 低估完成度、台账 AU-002/077/134 已销账、backlog 三处失实、§6.1 押金默认值已顺手订正）；终轮实跑：2030 单测全绿 + `WalletRefIdempotencySqlIT` 5/5（钱包 DB 级幂等兜底在位）+ 消费者 token 实测 `/ops/announcements/published` 200（AU-125 运行时实证）。AU-172/173 在 origin/dev（d5dee737，+15 提交含钱包统一 V387-390）仍未修 | `docs/AUDIT_THREE_END_FULL_2026-10-18.md`（§2/§8）、[附录明细](AUDIT_THREE_END_FULL_2026-10-18_APPENDIX.md)、台账「2026-10-18 三端全量复审」+「终轮增补」节 |
| 2026-10-11 | **将邑迁移后三端复审 + 协议对照 + 两缺陷修复**：①协议契约逐条对照 V16 §4.2 HTTP 面 + §4.3 WS 面（token/getDetail/downloadModelNotify/OSS STS 字段/lock/door/识别/orderError 双字段归一/视频分片/心跳 PONG/openDoor/updateModel）**全部一致**（报告 §2）；②三端一致性核对——录像读路径已收敛「服务端下发 videoPreviewUrl（将邑台账兜底）」、shared-types 零漂移、采集闸门同源、识别参考分层展示一致；③抓出并修复 **P1：admin「新增到将邑商品库」恒 400**（弹窗 mainImg 恒发空串 vs `@NotBlank`，按钮从未成功过——修法=预填 SKU 主图+可编辑输入+服务层回退 SKU imageUrl+双空 409，顺带消费 `jiangyiProductCategories` 下拉替换手填 catId）+ **P2：采集锁 409 文案漂移**（含「暂停营业」被 classifyOpenError 误判为锁机——新增 `device_gathering` 专属分类先于暂停营业判定，consumer 标题/离线判定同步，admin 入驻页 FAQ 对齐实况）+ P3 术语两处（「视觉识别处理中」→「识别商品中」）；④实跑全绿：gateway 58/58、trade 将邑面 73/73（修复后 38/38 含新增 2 用例）、admin 88+consumer 133+merchant 159 单测+三端 tsc；⑤旧链残留定界成文（5 条 D 级待拍板：叠加栈缺 jiangyi-gateway/生产钉死退役识别后端/EDGE 柜旧链仍真扣款/UAT 钉旧链/旧 YOLO 映射面在线）——**本次未擅自动旧链**，处置须先回读台账再批量清理 | `docs/JIANGYI_MIGRATION_AUDIT_2026-10-11.md`、lessons #294 |
| 2026-10-09 | **第三批落码（CB-018①营收序列 + 商户客服触点 + CB-019 判定凭证）**：①柜机×日营收序列——`CabinetOrderMapper.selectRevenueDailyByDeviceSince`（XML 按 Asia/Shanghai 日历日分桶，**口径同 sumAmountByDeviceId 不过滤状态**保证日序列求和与报表累计可对平）→ `OpsDeviceAdminService.deviceDailyRevenue`（ops:report:device、normalizeTrendDays 7/30/90、**scope 外深链返回空列表不 404 防探测**）→ `GET /reports/devices/daily-revenue` → DeviceReportView 趋势卡（EChart 柱状图+天数切换，deviceId 深链标题切「该柜机」）；②商户端在线客服触点——双端不对称实锤（C 端完整/商户端零入口）→ 新配置键 `merchant.service_phone`（ensureDefaults 种子+feature-flags.json 登记）+ merchantPublicConfig 下发 servicePhone/supportEmail（**空串 fail-closed 整组不渲染**）→ mine.vue「帮助与客服」组（safeMakePhoneCall 拨号+剪贴板复制邮箱）；③争议详情判定凭证（CB-019）——纯前端零后端改动（DisputeTicketDto 已全量下发）：disputes.vue 抽屉补申诉证据图墙（`downloadDisputeEvidence` 带鉴权下载到本地临时路径——image 标签无法携带 Bearer，失败留空占位不拼 token 防泄露+模块级缓存）+人工判定明细（resolutionItems；terminal 无明细=「本次判定未计费商品」空态对齐 C 端语义）+处理人/运营备注行。**坑**：手写 `FileAttachmentDto.fileId` 必填 number vs OpenApi 生成版 optional → vue-tsc 红，参数改结构化类型 `{ fileId?: number; url?: string }`。验证：admin 9/9 绿+vue-tsc 绿、merchant-mp type-check+154 测试绿；**UI 实机验收过（browser-skill）：趋势卡三档联动+`daily-revenue?days=30` 请求实锤，抓出并修复深链缺陷——`/reports?deviceId=xxx` 的 query 无人读（只实现了 watch 未实现 query 解析，单测未覆盖），修法=applyRouteQuery 加 deviceId 同步，复验「该柜机」标题+选择器选中+带 deviceId 请求全链路生效** | CB-018、CB-019、`OpsDeviceAdminService`、`disputes.vue` |
| 2026-10-09 | **资金两项②：渠道费实结化 V331（CB-020③）**：数据链=微信交易账单「手续费」列（官方 27 列 ALL 型 0 基第 22 列，WebSearch 取证）→ `PlatformBillLine` 加 `feeCents`（**Long null=通道未提供**，与 0 严格区分；支付宝未映射置 null、Mock 置 null 走估算兜底；**fee 解析独立 try 不丢行**——金额对账是主目标，费用列异常不能把整行变成 PLATFORM_ONLY 假差异）→ V331 `payment_platform_bill_line.fee_cents` + `payment_reconciliation.channel_fee_cents`（日×渠道粒度；feeProvided=false 存 NULL 不存 0）→ `FundBillService.listDailyBills` **实结优先**：当日跨渠道实结合计按商户 gross 占比分摊+**尾差挂当日最大 gross 商户行**（Σmerchant≡日实结），无实结回落 bps 估算；DTO 加 `channelFeeSource`（ACTUAL/ESTIMATED）前端「通道费（实结）/（估）」标注+CSV 同步。费率差异告警：`checkChannelFeeAnomaly` 实结 bps vs 配置 bps（`FundBillService.resolveChannelFeeBps` static 化复用同源配置），**绝对差>100bps 且 相对差>配置 25%** 双阈值（低费率防噪+高费率防钝）→ 复用 RECON_MISMATCH 告警+指标，**status 不动**（MISMATCH 语义只留给金额/单据不平）。`listLedger` 逐单明细**维持估算**（FundBillChannelFeeRateTest 5 用例钉死契约+逐单实结需 wechatTransactionId↔platformTradeNo 关联成本高，留增量）。**坑**：mock 未 stub `getInt` 返回 int 默认 0（0bps⇒估算 0 而非默认 60）——「默认费率」语义必须显式 stub。验证：5 测试类 30/30 绿+admin vue-tsc 绿 | CB-020、`V331__recon_channel_fee_actual.sql`、`WeChatBillCsvParserTest`、`ReconciliationServiceTest` |
| 2026-10-09 | **资金两项①：商户月度结算单物理表 V330（CB-020②）**：现状实锤=「结算批次」实为逐单一号（`RevenueSplitService:114` 逐行 `BizIds.nextNumeric()`，商户端批次=GROUP BY 聚合视图，每批次 COUNT(*)≡1）→ 升级为 `merchant_settlement_bill`（merchant_id+period_month 联合主键、bill_no 唯一确定性 `SB+yyyyMM-商户号`、快照 7 金额）。口径三处刻意优于旧视图：**排除 VOIDED/REVERSED 冲正行**（旧聚合瑕疵不延续）、月维度聚合（批次无意义）、幂等重建（CONFIRMED 行只刷金额不动状态、无有效 split 的陈旧行删除、重建不换号）；账期月=split.created_at 的 Asia/Shanghai 日历月与旧视图可对平。`MerchantSettlementBillService.rebuildMonth`（tryLock 120,5 → 整月覆盖）+ 调度器每日 02:40 重建上月+本月（错峰对账 01:30）；`MerchantFinanceService.listMonthlySettlementBills`（MERCHANT_SETTLEMENTS_VIEW 门禁+scope+默认近 12 月上限 24）→ `GET /merchant/settlements/monthly-bills`。**坑**：`BaseTradeMapper.delete(T)` 与 `BaseMapper.delete(Wrapper)` 重载 → verify 裸 `any()` 编译歧义，`any(Wrapper.class)` 消歧；`-pl` 单模块不带 `-am` 时 common-core 走 ~/.m2 旧快照缺新 DTO（铁律 #28 老坑重现）。验证：8/8 绿（Rebuild 6+Finance 2） | CB-020、`V330__merchant_settlement_bill.sql`、`MerchantSettlementBillRebuildTest` |
| 2026-10-09 | **第二批·非资金两项落码（CB-018①②）**：①补货建议一键生成补货任务——`ReplenishmentService.createTaskFromSuggestion`（`suggestForDevice(deviceId)` 单参口径与建议页一致：minLevel/ROP 触发+在途抵扣；skuId→qty 合并，空 gap 400 `REPLENISHMENT_NO_GAP`；route「建议-dev-date」PLANNED、task PENDING、notifyTaskAssigned、self.seedDraftRestockLines 货道分配+幂等复用；批次留空由出库链路回填、**不做防重** javadoc 注明）→ `OpsReplenishmentAdminService` 委托（`ops:replenishment:edit`）→ `POST /replenishment/suggest/create-task`；前端挂 `ReplenishmentView` 缺货 tab 行操作第二颗按钮「生成补货任务」（确认弹窗说明按设备全量建议生成防误触），原「补货」=路线规划不动；②柜机×日在线率——V329 `device_daily_online_rate`（kpi_date+device_id 联合主键，MP 无 @TableId 全 wrapper），数据源=`ops_exception` DEVICE_OFFLINE 半开区间（心跳不可回溯，唯一可回溯源），`DeviceAvailabilityKpiService.snapshotDeviceOnlineRates`（独立 try-catch 不拖垮主 KPI、分布式锁、windowEnd=min(dayEnd,now)、分母=device_info 全行、秒累计向上取整、rate 钳 [0,1]）；设备报表 `onlineRate7d`+`DeviceReportView`「近7日在线率」列（avgRateByDeviceSince 批量防 N+1，无样本=—）。**坑×3**：assertEquals(Long,long)/(long,long) 重载歧义→统一 longValue；纯 Mockito 缺 MP TableInfo→static 块 `TableInfoHelper.initTableInfo`（同 RevenueSplitServiceTest）；🔴 单测时间竞争——service 自取 Instant.now()，stub 拿测试的 now 匹配不上→0 分钟，修法=eq+any 宽松 stub+ArgumentCaptor 抓真实窗口+被测纯函数算期望。验证：6 测试类 29/29 绿+admin vue-tsc 绿；`gen:api-types` 待新代码服务拉起后重跑（现 8080=502/18080 无监听） | CB-018、`V329__device_daily_online_rate.sql`、`ReplenishmentServiceSuggestTaskTest`、`DeviceAvailabilityKpiOnlineRateTest` |
| 2026-10-09 | **存量扫描 S8–S11 闭环（CB-018~021，台账 21 条/128 URL）→ 第一批落码：审计增强包+密码复杂度**：①`DATA_UPDATE` 审计补 before/after（UPDATE 前按主键 SELECT 旧值 `::text`，detail=`col=old→new`，单值截断 120 字符；⚠️ **旧行取不到不提前 404**——BindingTest 钉住「值不可解析→DataIntegrityViolation(class 22)」用不存在的行作载体，提前 404 会改变错误语义，须继续走 UPDATE 原有 0 行→404）；②OpsRbacService 十处审计留痕（OPS_ROLE_CREATE/UPDATE、OPS_PERM_CREATE/UPDATE/DISABLE、OPS_ROLE_PERM_ASSIGN、OPS_USER_ROLE_ASSIGN、OPS_USER_MERCHANT_ASSIGN、OPS_OPERATOR_CREATE/UPDATE；5 个 do* 私有方法签名加 operatorId；`/audit-logs` 查询已有 `ops:audit:list` 免改）；③密码复杂度统一 `PasswordPolicy`（6-64 位+**必须同时含字母和数字**，仅新设/重置/改密生效不锁存量）覆盖全部 6 处设密路径——OpsRbacService×3（含原 createOperator **零校验**连长度都不查）、AuthService adminResetPassword、MerchantTeamAdminService×2（原「至少 6 位」连上限都没有）；负向验证：纯数字/纯字母 400 且不落审计。验证：单测 41/41 + Testcontainers 集成/并发 31/31 全绿 | `docs/COMPETITOR_BENCHMARK.md` CB-021、`PasswordPolicy.java`、`OpsRbacAuditTrailTest` |
| 2026-10-05 | **批次4（两报告并集 17 项）完成推送，CI 全绿（37413789568）**：账务仓储 8 项（回仓循环排序、收/退货受管仓纵深、分账重算三处对齐首记口径不 0 费率清零、月结钳制移到全量聚合后、在途补幂等守卫、采购价缺失阻断下单、OpsRisk 分页钳 100、佣金 job 拆设备-日短事务）；健壮性 3 项（券核销失败独立 try+HIGH 留痕不回滚扣款、recordSplit 刻意留事务内留注释、paidAt 失败留 error、**识别结算先抢锁后开事务**）；admin 3 项（11 处 yuan() 收敛 yuanText、金额 ??0 守卫 ×3、CrudTable rowKey 复核**维持原序**——props 是 string 函数在 table，类型语义非缺陷）；卫生 4 项（**check:ps1-bom 新门禁进链**、45 ps1 补 BOM、mybatis 假绿窗口 4→12+缓解窗改联合窗口+3 文件 ALLOWLIST 定案+真修 WeChatProfitSharing 两处残留清列、测试数基线 16/20/25/13、e2e 锁 TOCTOU 修 PID 校验）。唯一未本地做=架构批次2 | `docs/FIX_QUEUE_2026-10-05.md` |
| 2026-10-05 | **批次3「不彻底五连」+ 两尾巴全部完成**：P1-6 护栏补设备凭据行（CSV 层共享账号拦截+12位行计数；**P1-6b** verify-production-readiness 新增 CSV↔库 ACTIVE 设备行逐行比对，吊销闭环最后一步）、P2-8 入库**显式无主仓也拦**（与 P2-5 同宽严）、P2-14 门禁补 `${base}/api/` 模板串形态（**立即抓到 RecognitionDemoView.vue:215 实锤并收口 AdminEndpoints**）、P2-15 线长契约补 4 单测（12/12）、P1-3 死参 bookQty 删（4 处同步）、STARTUP_REFERENCE 补「staging 视觉栈 fail-loud 启动姿势」表、check-env prod 补 EMQX_BIND 非回环拦截（env 化设计兜底）。trade 1425/0/0+门禁全链+admin tsc 绿 | `docs/FIX_QUEUE_2026-10-05.md` |
| 2026-10-05 | **第三批修复（两审计合并队列批次1，9 项）**：P0-4 资金双记（负向测试实测 140/200/200 → 删 CHARGE:PREAUTH 块+终段 → net≡应付 3/3 绿；OrderPaymentChargeInvariantTest 钉三场景）、P0-1 file:// 回显（写入侧 minio:// 校验+读侧整体禁）、P0-6 门禁四形态重写（枚举终裁 47/12/35——A′17+变量2+!override4+内联14；自测四例；P0-6b 修出 feishu-relay:18098/alertmanager:19093 两条真违规全网卡→回环）、P2-3 补 ops 留痕（report HIGH）、P1-15 DTO约束/P1-12 CWD自锚/P1-10 模拟器日志如实/P1-11 docker.sock 风险注释（:ro 会断 runner 不盲改）/P1-9 离线队列上限50。trade 全量 1425/0/0 + 41 门禁 + 预检绿。staging 识别后端定案 (a) fail-loud | `docs/FIX_QUEUE_2026-10-05.md`、`docs/CODE_AUDIT_REPORT_2026-10-05_FULL.md` |
| 2026-10-05 | **验收口径收紧（用户明确）**：小程序以 mp-weixin 为准，**H5 不再重要**——不作验收面、不为其修 bug/样式/用例（dev:h5 双 vue 错配不修）；e2e-h5 job 内 admin 控制台三步 UAT 与此无关仍必须绿。已落 §4 关键约定表 | 本行 + §4 |
| 2026-10-05 | **审计修复第三批（收尾）**：P2-19 附加拍板——竞品 easygo 无实收数（账实漂移根源），本系统部分收货是正确优势，分仓收货页补实收 stepper（1..要货数，提交按实收，后端零改动）；P3 收尾：grafana.htpasswd 换 bcrypt（口令入本地 .env）、税号 18 位 USCC 校验+4 单测、消费者视频页删 `?url=` 深链（对齐商户端只认 orderId）、删过期 alipay-test×2（mp4 被门禁锚定保留、recovery/evidence 刻意保留）。merchant 154+consumer 135 单测+双端 tsc 绿。**本机 Grafana Basic（opsadmin）新口令在 `infra/.env` 的 `GRAFANA_BASIC_AUTH_PASSWORD`** | `docs/CODE_AUDIT_REPORT_2026-10-05.md` §10 |
| 2026-10-05 | **审计修复第二批**：P1-2（V305 唯一索引，先检重再建）、P2-4（FAILED 预授权清扫器+V306 种子）、P2-2（退款去外层事务）、P2-3（补差失败 HIGH 告警）、P1-6（check-env bootstrap 护栏）、P2-11（生产 EMQX 端口策略+删共享账号误导行）、P2-8（入库默认仓收紧）、P2-14（endpoints 门禁改拒绝式+收口两漏网）、P2-15（线长提现契约化）、P2-23（修正：device_ratelimit 是死配置已删）、快赢 P3×5（CI permissions/Dockerfile USER+删 java-runtime/consumer 门禁扩根抓到 feature-flags 注释裸路径/videoUrl 死引用/showError import）。P1-9 处置：DeepSeek 确认废弃仅平台吊销+本地 key 已清；高德分两类 key（JS key 配域名白名单/Web key 无法配域名）。P2-9/P2-10 评估后保持接真前批次（EMQX hash bootstrap 可行但须实测认证）。验证：trade 1421/0/0+device 48+admin 84+merchant 153+consumer 135+41 门禁+三端 tsc 全绿 | `docs/CODE_AUDIT_REPORT_2026-10-05.md` §9 |
| 2026-10-05 | **审计 P1/P2 首批修复落地**：P1-1（四包装器 rethrow BIE，F1-B 信号链恢复）/P1-3（盘点差量改当前账面基线）/P1-4（cleanupStaleOutbounds 去外层事务）/P1-7（9 份 csv() 收口 CsvCells + 新门禁 check:csv-escape）/P1-8（分仓单价服务端目录价无条件覆盖）/P1-5①（edge-results 新增必填 deviceId 强校验会话归属）；P2 快赢 13 条（userId 计入退款限额、收货仓负责人校验、作废行幻影占用、发运排序防死锁、心跳/告警 deviceId mismatch 丢弃、双充值 fail-closed、isDevBuild 前置、收货 every 门闩、staging 配置四件套）。验证：trade 全量 **1421/1421**（Skipped=0）+ device 24 + mp 288 单测 + 双端 tsc + 41 门禁全绿。**注意：VisionRecognitionResultDto 加了必填 deviceId（破坏契约，边缘接入方需同步）；mvn -pl 单模块跑测试须带 -am，否则 common-core 走 ~/.m2 旧快照**。余 P1-2（唯一索引迁移）/P1-6/P2-9/10/11（接真前）/P1-9（用户轮换凭据）见报告 §8 | `docs/CODE_AUDIT_REPORT_2026-10-05.md` §8 |
| 2026-10-05 | **搬家后全仓审计**（6 路并行+主审二轮逐条复核 55/55 成立无撤回）：无 P0；历史修复（F1/F2/F3/P3-4/S1 主体）全部在位；新 P1×9/P2×21/P3×25。要点：BalanceInsufficientException 被 runWithOrderPaymentLock 包成 500 断 F1-B 信号链、盘点过账用建单快照差量可静默错账、edge-results 无设备绑定+fleet 共享内部 key、分仓采购单价信任前端、9 份 csv() 副本仅 2 处中和、本地 env 真实第三方凭据待轮换。搬家三项已处置：`.workbuddy/` 骨架重建、根目录 BOOT-INF 已删、旧 OneDrive 位置实测无 .env 残留（剩余云端回收站清查+凭据轮换待用户）；核实中另发现收货页实收数恒等于要货数（无编辑入口）待产品拍板 | `docs/CODE_AUDIT_REPORT_2026-10-05.md`、`.workbuddy/memory/2026-10-05.md` |
| 2026-10-05 | 后台商户权限树文案对齐小程序（不改 perm_code） | V304、`MenuManageView` |
| 2026-10-05 | CI 红修续：OpenAPI 重生成含分仓采购/月结；商户 UAT M-09 改从「我的」进、无入口 SKIP | lessons #288–#289 |
| 2026-10-05 | CI 红修：删遗留 OrderDto、余额不足单测不把 ledger 读取当扣款、nav 多 perm OR、我的页 UAT、补齐边缘 YOLO stub；admin 产物须 Linux 构建 | lessons #287 |
| 2026-10-05 | **补货员分仓收货**：待收货单在小程序确认入本人仓 | `receiveSatellitePurchaseOrder`、`pages/purchase` |
| 2026-10-05 | **补货员采购入库**：商户端进本人分仓，不走运营采购权 | `ProcurementService.createSatellitePurchaseOrder`、`pages/purchase` |
| 2026-10-05 | **分仓月结一张表**：应有/实盘/上柜件数，与结算金额分开 | `WarehouseMonthlyCloseService`、lessons #284 |
| 2026-10-05 | **日常从仓到柜**：出库按柜机所属仓；无所属仓/混仓/无主仓拒绝 | `WarehouseService`、lessons #283、`docs/SATELLITE_WAREHOUSE.md` |
| 2026-10-05 | **采购进分仓**：日常采购禁止入无主仓；未选仓默认当前人负责的仓 | `ProcurementService`、lessons #282、`docs/SATELLITE_WAREHOUSE.md` |
| 2026-10-05 | **订单页白底**，去掉灰底 | `orders.page.css` |
| 2026-10-05 | **报修编号居中**；「我的」资料补余额/登录说明；优先支付与快捷入口改通栏；非 Tab 页 showTabBar 静默失败 | `report.vue`、`mine`、`index.vue` |
| 2026-10-05 | **购物页排版**：价格居中、去掉分类；加减改微信小圆钮；状态在柜名上；报修/换一台收到底栏上方 | `index.vue` |
| 2026-10-05 | **账单审核说明单行**；柜机/购物单号去掉等宽怪字体，与正文同字号 | `dispute/detail.vue` |
| 2026-10-04 | **购物清单贴内容**：缩略图+单价×件数+合计；继续选购/关门结算；不再撑半屏空白 | `live-cart-sheet.vue` |
| 2026-10-04 | **购物清单一行**：品名×件数×金额；去掉底部「预估」 | `live-cart-sheet.vue` |
| 2026-10-04 | **购物页柜名与胶囊同行居中**：去掉套层 padding；底栏不再叠 safe-area | lessons #281 |
| 2026-10-04 | **购物页叠两套购物车条**：抽 `HomeCartBar` 后删 `index.vue` 底稿；图标钉宽高；充值按钮用 `.btn-slot` 拉开 | lessons #279 |
| 2026-10-04 | **首页扫码压在底栏上**：预留 tabBar 底距，去掉 16vh 下压；hideTabBar 失败重试 | `HomeLanding.vue`、`index.vue` |
| 2026-10-05 | **分仓柜机归线**：`device_info.home_warehouse_id`（V303）；后台设备列表/新建/资产保存所属仓 | `OpsDeviceAdminService`、`DeviceListView`、`DeviceAssetDeploymentCard` |
| 2026-10-04 | **分仓第一步人绑仓**：`warehouse.manager_user_id`（V302，勿占 V301）；后台仓库可选负责人；CSV 省略字段不改绑人 | `WarehouseService`、`WarehouseEntityDialogs`、`docs/SATELLITE_WAREHOUSE.md` |
| 2026-10-04 | **Flyway 禁止同号**：曾两份 V301（wx OpenID / 人绑仓）；门禁扫目录重复版本 | `check-migration-safety.mjs`、lessons #280 |
| 2026-10-04 | **消费者 mp-weixin 禁 defineModel**：uni-mp-vue 3.0 无 `mergeModels`，HomeLanding 改 props+computed | `HomeLanding.vue` |
| 2026-10-04 | **消费者小程序按微信灰底+下划线 Tab+白 cell**：订单/消息/优惠券 Tab；「我的」白资料行；列表页去薄荷渐变与投影；首页扫码仍青绿。品牌仍 #0f766e | `clients/consumer-mp` |
| 2026-10-04 | **商户描边按钮白底**：微信默认 `#f2f2f2` + outline 透明会显灰；柜机订单加「零元单」（`excludeZero`，已退款 Tab 不滤） | `app-button.vue`、`App.vue`、`orders.vue`、`MerchantPortalController` |
| 2026-10-04 | **补货配置/消息/钱包/柜机订单排版**：去双顶栏、流水「未知」、空态折行、订单信息层级 | `ops-config`、`WalletPage`、`messages`、`orders` |
| 2026-10-04 | **销售报表按税号页同一套排版**：说明用途、去掉右侧重复柜名、件数接品名、导出用主按钮 | `cabinet-reports.vue` |
| 2026-10-04 | **经营分析只列本柜在售商品**：件数接在品名后；刷新；AI/全店金额用问号说明（#263） | `business.vue`、`MerchantAnalyticsService` |
| 2026-10-04 | **税号、销售报表放进「我的」**：经营分析不再挂税档/销售明细；入口在资料与报表 | `mine.vue`、`pages/tax`、`pages/cabinet-reports` |
| 2026-10-04 | **商户门户热补丁必须 `-parameters`**（#262）：否则 sales-reports/tax-profile 400 | `MerchantPortalController` |
| 2026-10-04 | **经营分析顶区收口**：去掉重复毛利/客单格子；主数字=毛利=营收−成本；无环比不展示「暂无」 | `business.vue` |
| 2026-10-04 | **订单/争议金额以支付流水为准**：扣 8 退 4 不再显示退 12 / 顶卡 0（#257） | `OrderPaymentLedger`、`DisputeService`、`order-detail.vue` |
| 2026-10-04 | **已退款顶卡不再显示 ¥0**：金额用 `refundedCents`；争议已扣禁止用退款额冒充（#257） | `order-detail.vue`、`DisputeService`、`MerchantPortalService` |
| 2026-10-04 | **订单明细排版 + 争议「有录像」必须文件真实存在** | `order-detail.vue`、`MerchantPortalService`、lessons #256 |
| 2026-10-04 | **商户订单详情单号字体**：去掉 `ui-monospace`（微信无此字体，19 位号显得又小又细） | `order-detail.vue` |
| 2026-10-04 | **商户购物视频 404 文案**：无录像不再「下载失败/复制链接」；去掉 H5 video 属性以免 wx-video 崩 | `video.vue`、lessons #254 |
| 2026-10-04 | **商户订单详情状态金额居中**（已退款等顶卡） | `order-detail.vue` |
| 2026-10-04 | **商户争议列表改信息层级**：事由+SLA 置顶，工单号收到脚注（仍完整 19 位），回复改胶囊按钮 | `disputes.vue` |
| 2026-10-04 | **结算对账 hot reload 崩**：`summary` 改 computed 后漏写 `summarizeDays`，且仍赋值 `summary.value` | `settlements.vue` |
| 2026-10-04 | **结算对账区间条上下对调**：快捷「近7天/本月」在上、日期 picker 在下；默认隐藏 0 元行，可用「零元单」开关显示 | `settlements.vue` |
| 2026-10-04 | **结算对账不展示 0 元订单行**（日汇总/批次）；0 元单不再建分账 | `settlements.vue`、`RevenueSplitService` |
| 2026-10-04 | **结算批次 ¥0 不是算错**：9/30 四笔是 0 元已支付单；列表改标「0 元订单」，且不再为 0 元单建分账行 | `RevenueSplitService`、`settlements.vue`、lessons #253 |
| 2026-10-04 | **结算对账去掉钱包/分账入口**：摘要只按所选日期汇总（营收−抽成=所得，待分/已结同源），不再混入全量待分/本月已结；白底卡 | `settlements.vue` |
| 2026-10-04 | **商户结算对账去说明、资金三页顶部切换**：去掉 T+1/Mock 说明卡；对账/钱包/分账用 `finance-hub-switch` + `redirectTo` | `finance-hub-switch.vue`、settlements/wallet/splits |
| 2026-10-04 | **商户「结算对账」改信息层级**：主数字=商户所得；营收−抽成；待分/已结/客单三格；按日/批次改台账行；日期只用小程序 `picker`（去掉 H5 `input type=date`）。微信开发者工具 automator 打开 `pages/settlements/settlements`，金额与用户截图一致（所得 ¥15.75） | `settlements.vue`；`docs/uat-screenshots/2026-10-04/settlements/` |
| 2026-10-04 | **工程从 OneDrive 搬家事故恢复**：工作区改为 `D:\ai-generated code\ai-cabinet`；基线 GitHub `dev` `f030f09f`（10-03 15:18）；今晚未推送已盖回 `format.ts`/`V300`/`MerchantReplenishmentService` + 要货「待备货」；ZCode 商家首页样式放弃。完整对话拷贝在 `docs/recovery/` | 会话 a9f5b970 18:59 起 |
| 2026-10-03 | **要货接单后须有出库才「去补货」**：已接单无 `outboundId` 显示「待备货」；明细用 `formatReplenRequestLine`；SKU 唯一性改为名称+规格（V300）；用户可见单号去掉 `#` | `request.vue`、`request-submit.ts`、`format.ts`、V300 |
| 2026-10-02 | **S1 验收收官（上线前必改全部清零）**：S1 设备独立凭据已由并行会话 2026-09-28 实施（V290+签发 API+ACL ${username} 命名空间+mosquitto 伪装 UAT 三用例过）；本会话独立抽验=发现轮换 SOP 缺口（EMQX restart 不重导 bootstrap，旧密码静默有效）→ 设计稿 SOP 补 force-recreate 红线 + lessons #244。F1 亦同日落地（见 10-02 F1 行）。剩余=接真三件套（视觉/支付/硬件，含行政）与 P3-4 production 评审 | docs/S1_DEVICE_CREDENTIAL_DESIGN.md、lessons #244 |
| 2026-10-02 | **F1 余额扣款竞态修复落地（三端审查「上线前必改」项清零）**：源码级核实修正报告两点推断——①竞态真实症状是 rollback-only 全回滚（结算 500+假争议单）而非多付；②多付的雷藏在 PREAUTH_CAPTURE 行 order_id=null 使 netCompletedCents 失明（markPaid 护栏对 F1 场景失明）。实施净额口径三件套：F1-A 冲抵行挂单+计入净额 / F1-B 锁内预判不足信号化（chargeOrder noRollbackFor=BalanceInsufficientException，「capture 保留+PENDING」按设计意图达成，不再 500）/ F1-C markPaid 净额三分支+cancel 净额守卫（净入账单禁自动取消转人工）。全量 1382/0/0（Skipped=0，Docker E2E 本地真跑）；设计稿 docs/F1_BALANCE_CHARGE_RACE_DESIGN.md | `ConsumerPreauthService`、`OrderPaymentService`、`UnpaidOrderService` |
| 2026-10-01 | **56 个 Skipped 测试盘点收官**：全部为 @Testcontainers(disabledWithoutDocker=true) （AdminE2E 5/ConsumerE2E 11/MerchantE2E 11/Reconciliation 1/WeChatNotify 1/DataManage 11+16 等），Docker 停机时按设计跳过；CI 集成 job 每次推送真跑全绿（上述类计数均 0 skip，run 36987395778 实证）——**非欠账**；本地想跑=启动 Docker Desktop。排查中曾误判 npipe 管道错位动过 ~/.testcontainers.properties，已还原 | run 36987395778 CI log |
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
| 2026-10-01 | **P3-1a 钱包余额公式巡检落地**：MERCHANT_WALLET_LEDGER_SUM/LINE_WALLET_LEDGER_SUM——「可用余额=Σ流水」全史公式（现有快照巡检修不了的账本历史错账）；口径依据=freeze/-x 不动 balance、release/+x、consume/-x 同减；真库存量绿+1 单测（37/37） | `DataConsistencyService` |
| 2026-10-01 | **P3-4 dev 实测通过 + 抓出存量竞态 bug**：compose 加 AICABINET_DISPUTE_AUTO_WAIVE_ENABLED=true（production 不加默认 OFF）+ xxl seed 重跑；造 4 天前未认领超时单→12:00/12:15 两轮均「免单 1」但 status 被 **DisputeSlaScheduler 整实体 save 回写旧 OPEN 踩掉**（存量竞态：人工结案撞 SLA 扫描同样会丢）→修复=SLA 标记列级更新 updateSlaMarkers（M01 同族）；12:30 轮复测 RESOLVED 站住（30s 复查），会话 COMPLETED、审计指派「系统」、防薅统计正常。测试数据已清理 | `DisputeTicketMapper.updateSlaMarkers`、lessons #243 |
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
| G7 | ~~灰度真金 vision~~ | **拍板 2026-10-11、实删 2026-10-17**：批 3 `vision-service/` 整目录 ✅ `d83a9380`、批 2 重力兜底/dev-cart 结算短路 + `settlement.recognition_mode` + `aicabinet.gravity-fallback-settle` ✅ `3cc19429`、批 6 旧视频链 ✅ `cd74d31c`+`5c80a4c9`+`fac98345`、vision-async Kafka 链 ✅ `f30635ba`、`MinioVideoService` 死注入 ✅ `f7516c77`、`UPLOAD_STUCK` 扫描族 + `ops:session:upload` 权限码 ✅ V386、reserved Kafka SCRAM 用户 `vision` ✅ `aa9d409a`、自研 `edge/android-app` 整模块退役 ✅（分支 `chore/retire-android-app`，2026-10-18；批 6 待拍板的 android-app 多摄开关随整模块删除一并了结）（分支 `chore/dc27-remove-vision` 已 ff 合入 `dev`）。识别现役唯一入口 `POST /internal/v1/vision/edge-results`（HTTP；Kafka `aicabinet.vision.recognize.*` 已随 `f30635ba` 退役） |
| G8 | 运营台仍有 **98 处**（32 个文件）`(cents / 100).toFixed(2)` 内联换算绕过 `yuanText`；双小程序已清零 | 渲染目前是对的（字面 `¥` + 纯数字），但负额会成 `¥-5.00`、且负数/空态口径与 `yuanText` 不一致。**只在该值可能为负时才算真缺陷**——2026-10-16 已把 5 处调账/提现确认串（输入框 `:min="-1000000"` 可为负）与 25 处重复 `¥` 收口；余量留作增量，动哪页顺手换成 `yuanText` 系 |
| G9 | 隔离栈 admin UI **浏览器里登录不了**（403 `Invalid CORS request`）：`docker-compose.full.yml:179` `CORS_ORIGIN:-http://localhost` 未跟 28080 端口重映射对齐 | 纯 API 驱动器不带 `Origin` ⇒ 测不出来。本轮只本机 `CORS_ORIGIN=http://localhost:28080` 临时绕过。**待拍板**：是否在 `docker-compose.uat-isolated.yml` 里钉 `CORS_ORIGIN`（含宿主端口），让隔离栈运营台开箱可用 |
| G10 | `POINTS_IDENTITY\|member 1` 累计 181 ≠ available+used+expired 149，**差 32 分**——**根因已查清（2026-10-18 只查未改）** | 不是假阳性：`POINTS_BALANCE`（可用 vs Σ 流水）同行为绿。真因＝**退款扣回积分只减 `available_points`**（`MemberService:316-323`），既不记 `used_points` 也不动 `total_points`，而流水 `points_type` 只有 EARN/USE/EXPIRE 三值 ⇒ 扣回该归 `used` 桶却漏记。更危险的是 `fixPointsIdentity`(`DataConsistencyService:1692`) 的自动修复方向相反：**下调 `total_points`** 凑三桶（实测 181→149、H5 升级 gap 319→351），等于让运营一键改口径。另查出等级判定不看积分（`MemberService:150-174`），`min_points` 只当 H5 进度分母、`max_points` 全仓零读取（后台旋钮不生效）。取证 [evidence/points-identity-investigation-2026-10-18.md](evidence/audit-2026-10-13/trackC-admin/points-identity-investigation-2026-10-18.md)，**修法待拍板** [POINTS_IDENTITY_DECISION_BRIEF_2026-10-18.md](POINTS_IDENTITY_DECISION_BRIEF_2026-10-18.md)（建议 A 扣回记入 `used` + D 撤积分族自动回写 + E 撤 `max_points` 死旋钮） |
| G11 | `paid_amount_cents`（订单毛额列）vs `payment_operation` 净额**没有对账判据** | 2026-10-18 拍板「另起一轮」。落点仍是 `DataConsistencyService`，须复用 `PaymentOperationTypes` 单一事实源、并按教训 #325 做负向证明 |

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

---

## 12. Changelog（按日追加，新条目在上）

### 2026-10-23（将邑映射复核＝结算闸口（V400 / CB-065）＋ R5 采集脚本与分步判据）
- 🔴 **结算口径变更：未按柜机当前 classes 版本人工复核的映射一律不结算（fail-closed）**。起因是 H-27：将邑 `classId` = `classes.txt` 按识别名排序的行下标，厂商每次重训整体平移（65 类→69 类实测 雪碧 25→29、可乐 330 22→26），旧实现只存 `classId→sku` 且不记录「审的是哪版 classes」⇒ 重训后静默「认 B 扣 A」。V400 加 `jiangyi_device.mapping_verified_version/at`，**verified ⇔ `classes_version` 非空且相等**；复核页「拉取差异」按 `text_name` 重算 KEEP/MOVE/NEW/ORPHAN/CONFLICT（两阶段 `-rowId` 负哨兵绕 `UNIQUE(device_id,class_id)`），人工确认后同事务写映射+版本戳；apply 带 `expectedClassesVersion`（classes 文件被换即 409），版本不符/冲突一律拒。三条自动失效：模型回执换版本（戳落后即失效）/ 未知模型回执且从未 CONFIRMED（主动撤戳）/ 下发超时真置败。三层 fail-closed：在售面返空（`JiangyiFloorGoodsService:80`）、`resolveMapping` 返 null（`JiangyiInternalController:224`，兜住 thirdPid 回退）、gateway 在归一化前整单 `MAPPING_UNVERIFIED`→`recognize-timeout`→DISPUTED 不扣款（`DeviceReportController:281`）；争议 `exceptionType=RECOGNITION_FAILED` 已在人工处置白名单内。`classId 0` 合法（0-based，过滤用 `>=0`）。
- 🔴 **上线/现场前置**：真柜 `EG943322621391` 的 `classes_version` 仍为空（只读查库确认）⇒ 部署 V400 后 `verified=false`，现场表现为「在售面空表 + 识别全部转争议」。R8 开门前必须先在复核页 adopt 当前 69 类（`modelName=hysxr20260930120329732_20260930120413844_50`、`_3.txt`、`quantity=69`、起始 0），核对 26/27/28/29→100016/100017/100018/100013 后「确认复核并应用」。共享 dev 库尚未应用 V400（`mapping_verified_*` 两列不存在），需拍板后再跑迁移。
- **R5 采集脚手架 + 分步判据**：新增 `scripts/jiangyi-gather-r5.mjs`（凭据只从环境/本机容器读且不打印；写操作必须 `--yes`），只打 V1.13 §4.3.2–4.3.14，另加 §4.4.4/4.4.5/4.4.6 只读；🔴 采集入口仍走我方 `.../jiangyi/gather/training`（ticket/finishNotifyId 一次性），**禁止**直调 `commitTrainingTenantOne`。只读实测：`waitGatherProduct` 恰 1 条 `41175 / 康师傅冰红茶柠檬口味茶饮品 / 310ml`。14 步操作与每步判据写入 `docs/JIANGYI_INTERFACE_CHECKLIST.md` §9B.7，R8 前置写入 §9B.12、H-28 台账行同步；竞品台账 CB-065（3 主体 / 2 peer）与 lessons #335 已落。
- **验证（本机实跑）**：trade-service `mvn -q -pl services/trade-service -am test` **2201 例 0 失败/0 错误**（401 份 surefire 报告全 `Failures: 0, Errors: 0`）；定向 `JiangyiModelSyncServiceTest` 27 / `JiangyiMappingReviewServiceTest` 14 / `JiangyiFloorGoodsServiceTest` 8 全绿（含新增「未知模型撤戳 / 重放已知模型不撤 / 超时置败撤戳 / 幂等重入不撤」断言）；gateway 全量 **138 例 0 失败**（17 份报告）。`openapi.ts` regen 后 **+228 行且仅 3 个新 path + 5 个新 schema**（无他人改动混入）。`check-competitor-benchmark`、`check-migration-safety`、`check-openapi-types`（结构档）、ESLint、Prettier、`check-admin-table-align`（修掉本页两处裸 `align=center` 后复跑绿）、`check-admin-bundle-budget` 均绿；审计聚合链 51 条**唯一红＝`check:line-endings`（同伴脏文件 `consumer-mp/src/manifest.json` 磁盘 CRLF，非本轮改动，未替其修复）**。未 push；`static/admin` 产物需推前用 `build-admin-ci-fidelity.sh` 在 Linux 重建后一并提交。

### 2026-10-21（隐私端判定 H5 死锁修复 + classes.txt 白名单改为纯配置）
- 🔴 **P0：H5 隐私门禁自锁（4173228b 已推 origin/dev，CI run 933 e2e-h5 红才暴露）**：`isWeixinMpRuntime()` 用「`globalThis.wx` 是否存在」判端，而 **uni-app 的 H5 运行时本来就把 uni API 别名挂在 `globalThis.wx`**（本地 :3002 实测 `typeof wx='object'`、有 `$emit`/`canvasGetImageData`、**无** `getPrivacySetting`）⇒ H5 被判成 mp ⇒ 首屏隐私窗永不渲染，而 `request.ts:233` 的 H5 门禁照旧 reject ⇒ 未同意用户**没有任何同意入口**，consumer/merchant 两个 H5 的全部请求死在客户端。判据改为「先按与 request 层**同源**的 `isH5PrivacyRuntime()` 排除 H5，再要求 `wx.getPrivacySetting` 函数存在」（两端判定同源，杜绝「门禁拦请求、弹窗不出现」各拦一半）；mp 端结论逐项对齐（现代基础库仍 true；无该 API 的老基础库改判 false，落到的 H5 分支在 mp 因无 `window` 仍不弹 ⇒ 行为不变）。取证：修前弹窗节点不存在且 `/api/v2/auth/captcha` **零网络请求**；修后点「同意并继续」写入 `aicabinet_privacy_consent_v1=1`、captcha 请求 200 真发出。详见 `docs/MINIPROGRAM_PRIVACY_LAUNCH.md` §7.1、lessons #334。
- **测试判据补牙齿**（同因）：`fetchCaptchaWithRetry` 旧文案「多为网关 auth_ratelimit 429 限流」把方向钉向 nginx，而 e2e job **没有 nginx**（`VITE_DEV_PROXY` 直连 8080）⇒ 白查一轮。现按观测分叉计数（429 命中 N / 根本没收到响应 M），`dismissPrivacyConsent` 在弹窗缺席时**必须**回查同意标记，缺失即失败（不再静默放行到下游误报）。
- **classes.txt SSRF 白名单改为纯配置**（AU-183，默认值不变＝等真值）：`aicabinet.jiangyi.model-sync.allowed-hosts` 由 `AICABINET_JIANGYI_MODEL_SYNC_ALLOWED_HOSTS` 注入（`application.yml` + `docker-compose.full/apps.yml` + `.env.production.example` 三处对齐），裸域＝强制 https+后缀匹配、`https://host`＝精确条目；厂商把 classes.txt 托管到 OSS 时按 probe 到的真实 host 填，**不要**一上来放宽成 `aliyuncs.com`。改动生效需重建 trade-service 容器。
- **模型预览整页 400 降级为逐行警示**：`previewModels` 的 catch 扩到 `ClassesParseRejectException | ResponseStatusException | RestClientException`，单个模型取不到 classes.txt（白名单拒/地址为空/OSS 不可达）只在该行显示拒绝原因，其余模型照常预览；🔴 **只降级预览**——`pregenerateForDevice`/`pushModel` 仍必须硬失败（写库/下发不能容忍）。回归测试 `JiangyiModelSyncServiceTest#previewModels_degradesUnreachableClassesTextPerModelInsteadOfFailingWholeList`。
- 验证：trade-service `verify`（含 IT）单测 2121/0/0、IT 42/0/0、BUILD SUCCESS 26:06；`JiangyiModelSyncServiceTest` 13/13 复跑绿；consumer-mp 单测 145/145（含隐私 7 条）+ `vue-tsc` 绿、merchant-mp 175/175 + `vue-tsc` 绿；prettier/ESLint 无改动无告警。

### 2026-10-21（后台巡检第一批修复：分页基数 / 异常归档页 / 弹窗残留 / 静默失败）
- ✅ **租金账单页页码基数错位**：`FeeBillMonthResolver.clampPage` 从 1 起（`page<1→1`），而 `useCrudTable` 传 `page-1`（0 基）⇒ 首屏请求 `page=0` 被钳到 1、第 2 页请求 `page=1` 又回到第 1 页，**整页只能看到第一页**。`DeviceRentalView` 传参改为 `p.page + 1`（`DeviceRentalView.vue` query builder）。
- ✅ **异常列表「已归档」页签查空**：后端 `archived` 是独立布尔条件，与 `status` 是 AND 关系；前端把 `ARCHIVED` 当状态值传给 `status` ⇒ `status='ARCHIVED'` 恒无数据。改为 `ARCHIVED` 只发 `archived=true`、不发 `status`；徽标计数本就只发 `archived=true`，两处口径对齐（`ExceptionListView.vue` query builder ↔ `OpsExceptionMapper`）。
- ✅ **将邑挂接弹窗跨 SKU 残留**：创建表单改为 `emptyCreateForm()` 工厂，每次 `open()` 整表重置（识别形态/规格/品牌/价格/分类），仅主图预填当前 SKU 图；原实现只重置部分字段，换商品后会把上一个商品的分类、价格写进将邑档案。
- ✅ **设备详情三处静默失败**：缺货阈值保存、重置覆盖价、商品排序保存补 `catch` + `ElMessage.error(errorMessage(e, '…失败'))`，不再「点完没反应」。⚠️ 遗留：`MerchantOpsConfigDto` 无版本字段，整配置 GET→PUT 覆盖仍有并发丢更新风险（修需后端加乐观锁，未在本批）。
- ✅ **发票列表错误被吞**：`onRowAction` 原把「确认框取消」与「接口失败」混在同一个 `catch` 里，接口报错也被当成取消静默返回。拆成确认/输入阶段（取消即返回）与 API 阶段（各自 `errorMessage` toast）两段。
- 验证：`vue-tsc --noEmit` 绿、ESLint 0 error、admin 单测 14 文件 / 112 用例全绿。

### 2026-10-21（将邑挂接：智能筛选 + 图片并排核对）
- ✅ **搜索分级**：`GET /jiangyi/std-skus` 增加 `skuId/includeAll`，返回 `{items,total,hidden}`；以我方 SKU 为基准分 `EXACT_BARCODE > SAME_SPEC > RELATED > SUSPECT`，规格归一（ml/毫升/L）、版本词（零卡/无糖/纤维/纪念/摩登）与 test/测试 判明显不符并默认隐藏；未挂接优先、扫码条码优先于手填。**条码不一致本身不隐藏也不阻断**（69 码随包装版本不同，2026-10-21 拍板）。
- ✅ **弹窗**：有品牌时自动预搜；卡片补缩略图、匹配徽标、`barCodeSource`（扫码/手填/无来源）、被隐藏条数与「显示全部」；挂接前并排我方 SKU 与将邑商品图片/名称/规格/条码，非条码一致须勾选「已核对为同一实物」才可提交。过渡期（nginx 已挂新前端、trade-service 未重建）兼容旧 jar 的裸数组响应，不把搜索打空。
- ✅ **识别形态不预选**：原默认 `bottle` 会把罐装商品静默写成瓶装，改为空值 + `clearable` + 提交前校验，切换 SKU 时重置。将邑 v1.13.0 枚举只有 `bottle/box/bag/bowl/egg`，**没有「罐装」**（`bottle:瓶装 box:盒装 bag:袋装 bowl:碗装 egg:鸡蛋`，见 `将邑对接资料/…v1.13.0.docx` §商品分类），罐装饮品按「瓶装」提交，提示已写在表单里。
- ⚠️ **DTO 变更须同步 generated OpenAPI 类型**：改 `StdSkuView`/返回结构后漏跑 `pnpm gen:api-types`，CI build 的 `check:openapi-types` 直接红。本机复现方式：`mvn -q -pl services/trade-service -am package -DskipTests` → 起 jar（dev PG 15433 / Redis 6379 密码 `devredis`）→ `OPENAPI_FILE=.tmp/live-openapi.json pnpm gen:api-types` → 提交 `packages/shared-types/src/generated/openapi.ts`。
- 验证：`SkuJiangyiLinkServiceTest` 19/19 + `JiangyiGatherClientTest` 1/1；admin `vue-tsc`、ESLint、dialog-a11y / admin-endpoints 门禁绿。

### 2026-10-21（提现出款权改条件 UPDATE 抢占 + MySQL 方言门禁进链 + 批量审核串行化）
- 🔴 **V395 是 MySQL 语法，PostgreSQL 起不来**：`ADD COLUMN pack_qty INT NOT NULL DEFAULT 1 COMMENT '件装数'` 的行内列注释只有 MySQL 支持 ⇒ Flyway `syntax error at or near "COMMENT"` ⇒ **56 个 `@SpringBootTest` 上下文加载集体 error**，而纯 Mockito 单测全绿（根本不起 Spring）。改为 `ADD COLUMN IF NOT EXISTS` + `COMMENT ON COLUMN` 后 trade 模块全绿。**教训落 lessons #328**。
- ✅ **固化成门禁（用户拍板「固化成第 48 条门禁」，实际落点是聚合链第 51 条）**：`scripts/check-mysql-isms-in-migrations.mjs`（19 组方言判据：行内 COMMENT/反引号/AUTO_INCREMENT/ENGINE+CHARSET/ON DUPLICATE KEY/INSERT IGNORE/MySQL 函数族与类型族/`AFTER` 位置子句/`DELIMITER`/索引 hint/`LIMIT n,m`…），`check:migration-mysql-isms` 已进 `check:audit-gates`（**50 → 51** 条，`node scripts/check-audit-gates-wiring.mjs` 实测绿）；CI 的 build job 另在 `mvn verify` **之前**加 gate + self-test 两步把反馈降到秒级（这两道**不依赖基线 ref**，与 `check-migration-safety`/`check-flyway-seed-separation` 不同，故可同时进聚合链）。`*.test.mjs` 在**真 Flyway 目录**跑 28 个红/绿方向用例（含 `CREATE TYPE … AS ENUM` 必须放行、`numeric(10,2)` 必须放行、注释里的 MySQL 片段必须不算数），临时文件收尾清零。现存 393 个 SQL 判据 0 命中。
- ✅ **提现出款权改为条件 UPDATE 抢占（决策 A+B）**：`MerchantWithdrawRequestMapper.claimForPayout(requestId, fromStatus, now)` 用 `UPDATE … WHERE request_id=? AND status=?` 抢出款权，抢 0 行即 409；`markPaying` 允许集去掉 `PAYING` 重入（单据**没有 `@Version`**，锁租约 60s 且无 watchdog ⇒ 读-改-写会丢失更新）；`payout` 的状态门从「锁外快照」改为**锁内重查**（与 AU-007 的 `cancelFailed` 同形）。`MerchantWithdrawDoubleReviewTest` 用带状态假表（`claimRow` 如实实现条件 UPDATE）跑 5 例：双审核只 1 支、真并发只 1 支、陈旧快照被锁内重查拦、锁失效并发只 1 次触渠道、全程旧快照时靠 claim 兜底 ⇒ 5/5 绿；全模块 `mvn -pl services/trade-service -am clean test` **389 类 / 2084 用例 0 失败 0 错误**。
- ✅ **同形缺口已镜像到线长提现（用户拍板「镜像」）**：`LineWithdrawRequestMapper.claimForPayout`（与商户侧逐字同形）+ `LineWithdrawService.payout` 改锁内重查 + `markPaying` 允许集删 `PAYING` 并改为条件 UPDATE 抢权（抢 0 行 ⇒ 409「该提现单已有出款在进行中」）。**删 `PAYING` 重入的前置核实**：三个 `executePayout` 入口（review :202 / payout 门 / 自动通过 :340）进入时行状态只可能是 APPROVED/FAILED，陈旧 PAYING 的唯一处置是 `failStalePayingWithdraws`（:495 起，置 FAILED 后走 payout 重试并重新冻结），不经 `executePayout` ⇒ 无合法调用方依赖重入。测试：`LineWithdrawConcurrencyTest` 加 4 例（锁内重查拦陈旧快照 / PAYING 行不再重入 / 全程旧快照靠 claim 兜底 / 双线程只 1 次触渠道）＝**8/8 绿**，`LineWithdrawTimeoutSweepTest` 3/3（其中 `payoutFailure_releasesFrozen` 与 `apply_acquiresLockAndFreezesBalance` 因新增 claim 步骤补假表 stub —— 这两条红恰好证明自动通过路径也真的走了抢权）。
- 🧪 **新增真库 IT `WithdrawClaimForPayoutSqlIT`（实测 3/3 绿，BUILD SUCCESS）**：单测里 mapper 是 mock，MyBatis-Plus 由 `update(null, lambdaUpdate()…)` 生成的 SQL **从未被任何数据库执行过**；本 IT 用 Testcontainers PG（空库从零 Flyway 起上下文 22.9s）钉三条——旧值匹配才抢到 1 行且只改 `status/updated_at`、真值已变则抢 0 行、两线程并发抢同一单**恰有一方赢**（商户/线长各一遍）。踩到两点：① `merchant_withdraw_request.merchant_id` 有 FK（**建表迁移 V139 里看不到**，是后期迁移加的），播种须先 `INSERT … merchant ON CONFLICT DO NOTHING`；② `-Dtest=<IT 名>` 会**覆盖 surefire 的 include/exclude**，于是同一个 IT 在 surefire 阶段又跑一遍；跑单个 IT 用 `-Dtest=不存在的类 -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=<IT> -Dfailsafe.failIfNoSpecifiedTests=false`（`-am` 链上聚合根与 common-core 没有该 IT，缺后一个 flag 会当场 BUILD FAILURE）。见 lessons **#329/#330**。
- ✅ **后台「批量审核」串行化（决策 C）**：新增单一事实源 `runFundReviewBatch()`（`utils/money-ui-contracts.ts`），三处资金审核改串行并逐条记名失败（`MerchantWithdrawView` / `LineManagerView` / `BalanceRefundView`）。原 `Promise.allSettled` 并发 fan-out 会让同一商户/用户的兄弟请求互抢钱包锁（`aicabinet:lock:merchant:wallet:<id>`），撞出的 409 不是业务失败、运营无法归因。`money-ui-contracts.test.ts` 3 例证明**并发度恒为 1**（15/15 绿），`vue-tsc --noEmit` 绿。
- 🛠 **顺带清掉**：`package.json` 里重复键 `check:payment-net-types-single-source` 定义了两次（JSON 后者覆盖前者，静默）。
- 🚢 **共享 trade-service 容器已重建（用户拍板，2026-10-21 07:2x）**：`node scripts/build-admin.mjs`（vue-tsc+vite，产物 07:22）→ `cd infra && docker compose -p ai-cabinet -f docker-compose.full.yml -f docker-compose.win-ports.yml build trade-service`（maven `BUILD SUCCESS`，新镜像 `99373a16f8e7`）→ 同前缀 `up -d trade-service`。结果：`Started TradeServiceApplication in 15.369 s`、启动 0 ERROR、**Flyway 进 V395**（`sku_catalog.pack_qty integer DEFAULT 1`）、`/v3/api-docs` 660 paths 含 `/api/v2/ops/admin/jiangyi/order-videos/pull`、`OPENAPI_IGNORE_CACHE=1` 现抓 regen 类型 ⇒ **聚合链 51 门禁 0 失败**（原红的 `check:admin-endpoint-paths` 转绿）。两个坑记牢：① `up -d` 只写 trade-service 仍会**连带 recreate postgres/redis/xxl-job-mysql**（共享 dev 库短暂不可用，peer 联调会撞断连）；② `gen:api-types` 默认读 `.tmp/live-openapi.json` 旧缓存，不加 `OPENAPI_IGNORE_CACHE=1` 等于没现抓。⛔ **临期页签下钻过滤（#37）端到端复跑仍做不了**：dev 库 `device_sku_lot` 里 `quantity>0 AND device_id IS NOT NULL` 计数 **0** ⇒ 造不出临期告警、`pull_off_task` 全空，服务端/前端两种过滤都回 `total=0`，无判别力；已拿到的替代证据是容器 api-docs 的 `deviceId` 查询参数（旧 jar 无此参数，证明新代码在跑）+ `ReplenishmentServiceExpiryPageFilterTest` 的透传断言，`PullOffTaskMapper.searchPage` 里 `.eq(dev != null, …)` 生成的 SQL 当时仍未被真库执行过（该盲区已由下一条 IT 补掉）。
- 🧪 **新增 `PullOffTaskDeviceFilterSqlIT`：真库 4/4 绿 + 变异验证**（用户拍板「走 IT，不往共享 dev 库造数据」）。钉四条：`deviceId` 非空时 **records 与 total 同步收窄**（旧前端 filter 的病根正是 total 不收窄）、`null` 与空白串同口径保持全量、`status`/`created_at` 倒序不受过滤影响、服务层翻页落在过滤集内（第二页恰 1 条而非大面积空）。播种踩坑两条：`pull_off_task` 有**两条 FK**（`device_id→device_info`、`sku_id→sku_catalog`，V216 补的，建表迁移 V24 里看不到）；JdbcTemplate 给 `created_at` 传裸 `java.time.Instant` ⇒ PG JDBC `Can't infer the SQL type to use for an instance of java.time.Instant`，改传 `java.sql.Timestamp.from(…)`（lessons **#331**）。🔴 **变异验证**：临时删掉 `.eq(dev != null, PullOffTask::getDeviceId, dev)` 一行重跑 ⇒ **4 条里 3 条红**（只有「空白 deviceId 保持全量」按预期仍绿），跑完 `cmp` 确认源文件逐字节还原 ⇒ 断言不是空转。已登记进 `check-docker-tests-guarded` 的 GUARDED（job: integration；该门实测 `@Testcontainers` 类 19 个：*IT.java 12 / *Test.java 7）。UI 端到端那条仍等真库存数据，**不再是代码缺口**。
- ⚠️ 本轮改动**未提交**；提交后台源码前仍须 `bash scripts/build-admin-ci-fidelity.sh` 重生成 `static/admin` 产物（宿主 `build-admin.mjs` 已跑过一轮，但 CI 的 `admin-artifacts` job 以 fidelity 脚本产物为准）。

### 2026-10-20（开门记录：更新时间排序回正 + 时长恒 0 根因修复 + 取货视频缺失定性）
- ✅ **列表改为按更新时间倒序**：`ShoppingSessionMapper.findByFiltersOrderByCreatedAtDesc` → `findByFiltersOrderByUpdatedAtDesc`（`ORDER BY updated_at DESC, created_at DESC`，唯一调用点 `OpsSessionOrderQueryService.querySessions`）；前端 `SessionListView.vue` 本地排序 `prop:'updatedAt' defaultDir:'desc'`（滞留置顶保留）、排序按钮文案改「按更新时间」。
- 🔴 **时长恒 0 的两层根因**（DB 实证 session `1792486908633898331106`：open_time 有值、close_time NULL、updated_at==created_at）：
  1. **演示关门零元结案不写 close_time**：`SessionSettleService.completeDemoZeroSettle` 直接把会话推到 COMPLETED（模式一无关门上报，用户从小程序点「关门」），`close_time` 恒空 ⇒ `shoppingDurationMs=null`、前端回退 `updated_at` 又是创建时刻 ⇒ 0s。已在该路径补 `close_time=now`。
  2. **`updated_at` 全站冻结**：`MybatisMetaObjectHandler.updateFill` 用 `strictUpdateFill`，而实体多由 `selectById` 读出、`updatedAt` 已非空，MP 严格填充只在 null 时写入 ⇒ 旧值原样回写。改为 `setFieldValByName` 每次更新刷新（顺带修正工作台「近 24h 完成数」等按 updated_at 统计的口径）。新增单测 `MybatisMetaObjectHandlerTest`。
- ✅ **前端时长口径**：已结束会话只认 `close_time`（`updated_at` 会被后续运维动作刷新，兜底会虚增）；历史单无法还原真实关门时刻，显示 `—` 而不是 `0s`；进行中会话用当前时刻算已进行时长。
- 🔍 **订单「柜机取货视频」为空＝设计如此，不是 bug**：网关 `getArtificialCheckByIdentifier` 与 `isAutoUploadVideo` 均按 2026-10-19 拍板恒回 `false`（只传异常/人工复核单）；gateway 日志 2026-10-20 实证设备 12 次轮询拿到 false，`pokerOrderVideo/uploadVideoUrl` 近 48h **0 次调用**；`jiangyi_order_video` 仅 1 行（10-14 异常单补传）。查询口径无错（admin 用 `sessionId` 查、上报 `orderNo` 即 sessionId，已验证对齐）。要逐单视频必须按设备/租户粒度放开全量上传（成本：物联卡上行 + 私有桶存储），非全局开关。
- 验证：`OpsSessionOrderQueryServiceTest` 4/4、`SessionDoorClosedIdempotencyTest` 3/3、`SessionLiveCartTest` 2/2、`SessionServiceRecoveryTest` 6/6、`MybatisMetaObjectHandlerTest` 1/1；`vue-tsc`、Prettier 通过。**后端改动需重建 trade-service 容器才在页面生效**（本轮未重建）。

### 2026-10-20（设备详情空字段收敛：DTO 补全 + 将邑明确缺失 + 新建补记流水）
- 🔴 **「最近上线/最近离线」为空是映射 bug，不是没数据**：`DeviceSlotService.toAdminDeviceDto`（详情接口 `GET /ops/admin/devices/{id}/detail` 的唯一出口）曾用 14 参兼容构造器，`lastOnlineAt/lastOfflineAt/imei/opsTags/deployedAt/firmwareVersion` 等被统一置 null；改为 36 参完整映射（`online_since`→`lastOnlineAt`、`last_offline_at`→`lastOfflineAt`，其余同 `OpsDeviceAdminService.toDeviceDto` 口径）。实证：`EG943322621391` 库内 `online_since=2026-10-19 08:23:24Z`，旧容器详情接口仍回 `null`。
- ✅ **将邑柜的「缺失」讲明白**（前端 `DeviceDetailView.vue` + `DeviceAssetDeploymentCard.vue`）：柜内温度指标、温度操作条按绑定隐藏（协议 §4.2.15 明示 temperature 暂无法获取）；固件版本与新增的 IMEI 项显示「厂商未提供」（该节无固件字段、不报 IMEI）；资产卡「设备序列号」优先 `asset.imei`，否则回退绑定表 `JiangyiDeviceDto.deviceSn` 并注明「将邑厂商 SN（厂商未上报 IMEI）」。ElTag 需 `disable-transitions`——异步 SN 到达后 v-if 切输入框，否则 appear 过渡会留下「未绑定」残影（浏览器实证）。
- ✅ **新建即投放不再无流水**：`OpsDeviceAdminService.createDevice` 落库后补写 `device_lifecycle_event`（入库 `from=null,to=INBOUND,action=INBOUND`；绑定商户 `from=INBOUND,to=DEPLOYED,action=DEPLOY`），时间线对 `fromStatus=null` 渲染「初始状态：X」而不是误标 `DEPLOYED`。**只对以后新建生效，历史不补**（用户拍板）。
- 验证：`OpsDeviceAdminServiceTest` 12/12、`DeviceSlotServiceTest` 8/8；`vue-tsc` 通过；Vite dev 实测将邑柜温度块消失/固件与 IMEI 文案/序列号回退 SN，自研柜（EG879147199289）温度块保留且流水「入库 → 退役」正常。**后端改动需重建 trade-service 容器才在页面生效**（本轮未重建）。
- ✅ **设备列表 IMEI 列同口径（补做）**：`AdminDeviceDto` 追加 `jiangyiSn` 第 37 字段（三处兼容构造器补 null）；`JiangyiDeviceDirectory.deviceSnByDeviceIds` 一次 `IN` 查询批量回填（列表避免 N+1），单台路径 `findDevice` 点查，`DeviceSlotService` 详情同步回填。前端列表：有 IMEI 显 IMEI，否则 `jiangyiSn` 显「厂商未提供」（title=厂商 SN），两者皆无显「无」；CSV 导出写「厂商未提供(SN:…)」。`openapi.ts` 已同步手工补 schema；单测 **22/22**（含新增 `listDevicesPaged_fillsJiangyiSnFromDirectory`），`vue-tsc`/Prettier 通过。**仍需重建 trade-service 容器页面才生效**。
- 遗留（商品规格换算，待拍板）：「1 件=多少支」当前**无法换算**——`sku_catalog` 只有文本 `spec` + 单值 `unit`，采购单行/仓库批次/货道库存/销售计数全链路同一整数口径（`PurchaseOrderLine` 无单位、无件装字段）；若要支持，须新增「件装数」并先定采购/库存/成本按件还是按支。

### 2026-10-20（实名二要素接 vendor + 微信支付生产开启）
- ✅ **实名定位拍板**：消费者实名走微信侧（免密代扣签约即微信实名，平台不采集；V326 语义确认），**唯一自采实名=商户/线长提现打款合规**（WithdrawEligibilityService）。vendor 选定阿里云市场「身份证二要素」（`POST https://swidverify.market.alicloudapi.com/verify/identity`，AppCode 鉴权，按次计费免费试用 200 次）。微信侧独立二要素走不通已实锤：`/v3/realname/verify` 绑 `wxp_trade_no` 且仅平台商户。
- ✅ **契约改版（dbfa7871）**：`VerifyIdentityRequest.idCardLast4(\d{4})` → `idCard(\d{17}[\dXx])`——权威库比对必须完整证件号，后 4 位对任何真 vendor 不可用；本地不落库/日志禁 PII 边界不变。`IdentityVerifyClient` 改表单提交（**x-www-form-urlencoded 非 JSON**，vendor 官方示例要求）+ `Authorization: APPCODE`；判据收敛 `result_code=="0000" 且 result.checkresult==1`（2→400/3→400 库无指引/110→429 日限 10 次/非 0000→502，未知值 fail-closed）。新增 `IdCardValidator`（GB11643 mod-11）本地拦截省按次费。
- 🔴 **部署坑两枚**：① `docker-compose.apps.yml` 漏透传 `IDENTITY_VERIFY_*` 两键（与 466930d7 双 appid 漏透传同类，139232f3 补）；② **prod profile 刻意关 springdoc**（H47）⇒ 铁律 52 的 regen 必须抓**本地 dev 容器** `/v3/api-docs`（:18080），prod 容器恒 404。
- 🔴 **vendor 响应 `charge`/`request_id` 未消费字段**：裸 ObjectMapper 测试抓出 `UnrecognizedPropertyException` ⇒ record 上显式 `@JsonIgnoreProperties(ignoreUnknown=true)`，别依赖 Boot 全局配置。
- ✅ **生产已上线**：服务器 `.env.production` 补 `IDENTITY_VERIFY_BASE_URL/API_KEY` + `WECHAT_PAY_ENABLED=true`（备份 `.bak-20261020-identity`），deploy-prod 9f3aa02d trade 40s healthy（启动校验门禁通过）；vendor 烟测 AppCode 真 200 `checkresult=2` 实证鉴权+解析；openapi regen 已提交（3fc82a8f）。**待用户真机验收**：小程序「我的→待实名>」提交真实姓名+身份证（预期通过→已实名）；假名一次预期 400「未通过」（同证日限 10 次省着测）。

### 2026-10-20（WS 通路机理闭环：setDomain 实证生效 + 自钉补第三端点 + 拨号真相表）
- ✅ **「在代码里直接传」被厂商云实锤**（只读回查，设备 token）：厂商 `getConfigDomainByIdentifier` 现回 `data=https://130954bm9ka83.vicp.fun/jiangyi/api`、`getConfigSocketUrlByIdentifier` 回 `data=wss://130954bm9ka83.vicp.fun/websocket/device/` ⇒ 10-19 `JiangyiOnboardingService.bindDomain` 的 `setDomain` 写入的 `domain`+`socketUrl` **两槽都在厂商侧生效**；商户后台「API接口配置」（rev.6 的 offnet 三槽）不是这条通路的必要条件。裸 JWT 打厂商云回 503 非法访问，必须带 `Bearer `。详见 `将邑对接资料/…沟通清单_2026-10-19.md` 修订 8。
- ✅ **自钉补齐第三端点** `POST /jiangyi/api/domainConfig/getConfigSocketUrlByIdentifier`（`DomainConfigController`）：APK V9.10.563 实证——`InitAppServerInfoControl$19.onSuccess`（smali 逐行）只在「拉回域名 ≠ `COMMON_URL` **且** prefs `url`/`socketurl` **任一为空**」时进 `getOtherToken→getOtherDeviceNum→getOtherSocketUrl` 迁移链（两键都非空=切换后稳态 ⇒ 整条跳过）；且 `getOtherSocketUrl`（`InitAppServerInfoControl.smali:1665`）拼的是 **`COMMON_URL`** + 头带 `COMMON_TOKEN` ⇒ **首切时设备问的是厂商云，不问我们**；真机 4 份 logcat 全量检索该路径 **0 次**。回值写 `Constans.SOCKET_URL`，消费者只有 MZ/CARD_258/CD_WB（`SocketTaskInfo.smali:134/174/214`，三家拼法互斥；NORMAL 不读）⇒ 它是**换域交接端点**，不是首切硬前置；三端点共用同一开关，部署仍应带全。返回 `wss://<host>/websocket/device/`（env 同一 host 推导），env 缺失三端点一起 404。`mvn -o -B verify -pl services/jiangyi-gateway` = surefire **99/99** + failsafe **19/19**（`DomainConfigControllerTest` 8/8）。**本地实测（10-20 11:01）**：以 `JIANGYI_PUBLIC_BASE_URL=http://127.0.0.1:18084` 重建本地容器 → `pnpm jiangyi:day1-check` **9/9 全过**（3 路由门 401 + 三端点 null 信封 + 值形态）；真机 token 走 `ws://127.0.0.1:18084/websocket/device/CQYB10720` 握手成功、心跳回 V16 §4.3.1 同形 `{"status":200,"msgType":"heartBeat","msgContent":"PONG"}`。
- 🔴 **`getConfigStatus` 语义修订（同日前一版解释作废）**：回值在固件里是 `Constans.USE_TYPE`（`UpdateBaseInfo$3` 把 `data` 以 `Double→int` 写入；调用点 `UpdateBaseInfo.getIsAbandon()`），与「域名配置状态 / 待拉」无关；域名拉取是无条件 `getServerUrl`，不看它。1 是固件多处常规分支，2 的语义文档与固件均未定义（固件只拒绝负数 `UpLoadApp.setUseType`）⇒ 恒回 1 保留，1/2 语义列入厂商问题。**V16 原 PDF（图像版）已用 PyMuPDF 渲染逐页核对**：目录确无 domainConfig 节；§4.2.2 setDomain 表（p14）四参 +「socketUrl 填写后才能下发开锁指令」+ 组装规则「地址+设备编号+token」；返回示例 `data:{identifier}`（p15）；§4.2.3 请求地址 =「商户服务器地址+`/token/openDoorDeviceStatus`」（p15）；§4.3.1 WS 拼接式 `"wss://…/websocket/device/"+编号+"?token="+token`（p38）。
- 🔴 **WS 仍拨 lingshouapi 的机械解释**：NORMAL 拨号只认 prefs `"socketurl"`（非空则 `socketurl+"device/"+id+"?token="`），空则回落固件常量 `wss://lingshouapi…/websocket/device/`；**拉取的 `SOCKET_URL` 只喂 MZ/CARD_258/CD_WB**。prefs 仅由 `SocketMsgDeal.updateUrl` 处理**对象格式**（入参含 `sendDeviceRuntimeType`，`msgContent{domainUrl,websocketUrl}`）时写入并重启；log4 五次 `sendDeviceDomainSure` 之后每次开机 `COMMON_URL` 仍是 lingshouapi ⇒ **厂商推的 `sendDeviceDomain` 本身就不写 prefs**（2026-10-20 深夜 563 smali 全文复核订正：该 handler 只 `contains` 判定→ACK→`reboot()`，不解析 msgContent、不写任何值——「厂商推的是字符串格式才不生效」的旧解释作废）。⇒ 改 WS 地址只能请厂商推 `sendDeviceRuntimeType` 对象消息（`websocketUrl` 填 `wss://<host>/websocket/`，固件自己拼 `device/<id>`；🔴 `domainUrl` 必须保持厂商云原值——它写 COMMON_URL 槽）或走 `sendDeviceOffDomain`（JSON 规格=卡点文档修订 10；`offnetSocketUrl`（NORMAL 无网兜底拼法 `offnetSocketUrl+identifier+"?token="`，不自带 `device/`）则填 `wss://<host>/websocket/device/`）；第三端点回值只喂 MZ/CARD/CD_WB 且三家拼法互斥（无单一正确形态，维持 `…/websocket/device/` 对齐文档拼接式）。**这是「厂商说已推、设备仍连厂商」的唯一机械解释**，也是当前唯一未闭合项。
- 🔴 **第二堵墙归属修订（2026-10-20）**：WS 的 `?token=` = `Constans.TEST_TOKEN` 快照（`WebSocketClientThread.smali:99-122`），唯一写点 `InitAppServerInfoControl$10.onSuccess` 只服务 `getToken()`→**COMMON_URL**（`InitAppServerInfoControl.smali:1888-1930`、`$10.smali:138`）⇒ token 归属跟 COMMON_URL 走：**选 `sendDeviceOffDomain` 时 `restoreOffNetDomain`/`applyAndCacheOffNetDomain` 把 COMMON_URL 换成 offnetDomain（`OffNetDomainTool.smali:585/:34`）⇒ 设备拿我方 token，第二堵墙自解**；首连空/旧 token 由 `onError→sendTokenRefreshMsg→TokenRefreshTool→getToken(COMMON_URL)→init→initWebSocket(新 TEST_TOKEN)` 自愈（`WebSocketClientThread$2:147-176`、`WebSocketClientService:165-247`）。**选 `sendDeviceRuntimeType`（domainUrl=厂商）时 COMMON_URL 仍厂商 ⇒ 厂商 HS512 token → 我方握手 401 → 刷新仍厂商 token，墙才真实存在**；届时才需拍板「宽松校验」或放弃该路线。(b) 宽松校验=结构解析+登记表 BOUND/authEnabled，(c) pm clear 已判结构性无效。
- 🔍 **「文档里有 / 代码写错了」三线复核（用户质疑，2026-10-20 深夜闭合）**：①**厂商文档穷举 8 类**（V16 源 docx 逐字、V15 OCR、采集接口 v1.13.0 源 docx、套件安装说明书/规格书/企业介绍 OCR、确认记录、extracted_text 空壳）⇒ 除 §4.2.2 setDomain 一条通路 + 承诺句（「socketUrl 填写后才能下发开锁指令」，我方已实现且云侧回查写入生效）外，**无第二套改 WS 机制**；②**我方 setDomain 代码复核无错**（四参逐字/端点一致/云侧两槽回查生效/`wss://host/websocket/device/` 形态=文档组装规则反推）；③**「代码写错了」最成立的落点＝固件 NORMAL 相对自家文档承诺的断层**（硬编码厂商常量、不消费 setDomain 的 socketUrl），非我方代码。字面量订正落地：`docs/JIANGYI_GATEWAY_DESIGN.md` 真相表 3 处 + 推送精度注 + 卡点文档 3 处 + `DomainConfigController` javadoc（「只有厂商重推 `sendDeviceDomain` 才能救回」→ `sendDeviceRuntimeType`）——此前把「写 prefs 的对象消息」转述成 `sendDeviceDomain` 属笔误，**结论不变**。版本口径：563 设备实跑过、806 行为不变（仍拨厂商）、厂商若称新版已修，812 落地后只看我方 nginx 重测即知（不改我方代码）。
- prod 操作单不变：① 建商户+柜机、SN 登记激活到 BOUND；② 补 `JIANGYI_MERCHANT_MOBILE/_PASSWORD` 重启 trade；③ **域名换正式域仍冻结**（prod 库空；且 WS 投递通道未打通，换了也只搬 HTTP）。

### 2026-10-19（将邑真机接入：拍板 A 落地 + 拍板 B 判定 + 「HTTP 已过、WS 未过」语境重定）
- **拍板 A 已实装**（`services/jiangyi-gateway/.../api/DomainConfigController.java`）：设备既然主动轮询 `domainConfig/*` 取 HTTP base，我方就把自己钉回去——`getConfigDomainByIdentifier` **照抄云侧怪形态** `{"status":null,"msg":null,"data":"<base>/jiangyi/api"}`（实测响应体 status/msg 双 null，不是 200 信封），`getConfigStatusByIdentifier` **恒回 `data:1`**（云侧 1/2 非确定性出现 ⇒ 不是状态位；恒 1 = 自愈式重钉）。env 未配/非法 ⇒ 两端点**一起 404**（配错域名会把整队柜子钉到不可达地址，宁可 404）。🔴 运维顺序 **登记 → 激活 → setDomain 不可颠倒**（设备先有合法 token 才拉 domainConfig）。
- **拍板 B：现在不做反代**。硬障碍（自行复核出、非对方清单所提）：设备带的是**我方 HS256/HS384 token**，将邑云只认自家 **HS512**（真机 `COMMON_TOKEN` payload `{"exp":2188291197,"deviceId":10720}` = 2039-05-06 到期且我方不可吊销）⇒ 原样转发必 401，**反代是改造不是配置**。收益也低于设想：`updateAppVersion` 4 次可归属采样全回 `data:10720`（无包地址，OTA 真走 WS 私有指令 `softwareUpdate`）。厂商催办顺序＝**`feature/*`（双摄识别策略 flag `reason=customer_release_dual`）第一优先** > `deviceApp/*` > AkToken/补货历史。新增**厂商问题 13（domainConfig 语义）/ 14（切商户域名后厂商能力如何过鉴权）**，见 `将邑对接资料/将邑socketUrl缓存卡点_分析与人厂沟通清单_2026-10-19.md` 修订 5。
- 🔴 **重大语境订正（我曾断言「我方从未被真机打过一次」，错）**：真机 HTTP 启动序列自 16:18 起就在打我方网关（`token→getDetail→getArtificialCheck→receiveCurrentTxtUrl→classIds` 每 ~2 分钟整循环，UA=`okhttp/4.9.3`，我方 logged 404/bad_token），**但**同时段 logcat 的 `COMMON_URL` 仍逐字打印 lingshouapi、我方域名三份 logcat 0 命中 ⇒ 「切了域名就全量过来」不成立（≥2 个地址槽，或该日志只是内置默认——两说未排除）。**取证面必须是我方 access log，不是厂商侧 logcat**。错因＝只翻 `.tmp` logcat、跳过了 AGENTS.md 每会话必读的活文档。
- **第三个待拍板（不可自选）**：WS 的 `?token=` 是云侧 HS512 ⇒ 即使 socketUrl 下发修好，我方 `DeviceHandshakeInterceptor` 仍 401。候选＝请厂商灌商户 token / 我方对白名单做「结构解析 + 登记表比对」宽松校验（代价：容忍不可吊销长期凭据进设备面）/ 现场清缓存重激活。
- **环境事实**：花生壳退役，生产域名 `cabinet.easygovm.com`（A→120.25.246.179 + certbot），prod 加载 `infra/.env.production`（仓库只有 `.example`；`deploy-prod.sh:111` 的 `--env-file`），其 `JIANGYI_PUBLIC_BASE_URL` 已换正式域 ⇒ 自钉只需一行 env。**入口路由已从公网证实**：`node scripts/jiangyi-day1-check.mjs`（新增的只读探测，`pnpm jiangyi:day1-check`）三条门全 401 ⇒ 宿主机 vhost 的 `/jiangyi/`、`/websocket/` 都通到网关。
- 🔴 **部署陷阱（本轮读 `scripts/deploy-prod.sh:43-49/57` 发现，与"配置没生效"极像）**：干净性判定 `git status --porcelain | grep -v '^??'` **不拦未跟踪文件**，而打包是 `git archive HEAD`（只含已提交内容）⇒ 新增 Java 文件忘 `git add` 会「部署成功、跑的却是没有该类的旧 jar」。**部署前 `git status --porcelain | grep '^??'` 必须为空**（本模块当前有 7 个未跟踪新文件）。
- 备用入口（断点调试期把真机引到开发机）：`docs/JIANGYI_LOCAL_TUNNEL_FALLBACK.md`——SSH 反向隧道到同一台服务器 + `dev-cabinet` 子域 vhost；🔴 收工必切回正式域（把柜子钉在隧道域名上过夜=该柜当天离线，自愈只靠开机重拉）。
- **验证**：`mvn -o -B verify -pl services/jiangyi-gateway` = surefire 97/97 + failsafe 19/19 绿。教训沿用：**设备面改动必须跑 failsafe，`*IT` 不进 surefire、`pre-push-ci-preflight` 不跑 `mvn verify`**（`dad701b4` 的 HTTP 码回归就是这样漏进库的）。


- **背景**：用户提供两个独立小程序主体的真实凭证——消费者 `wx6dacefb3671de630`、商户 `wxf7de3625991a6431`（AppSecret 只进本地 gitignored `infra/.env` 新增 4 键 `WECHAT_CONSUMER_APP_ID/SECRET`、`WECHAT_MERCHANT_APP_ID/SECRET`，禁止提交）。小程序端 `consumer-mp`/`merchant-mp` 的 `manifest.json`(mp-weixin.appid) 与 `project.config.json` 已分别填入（appid 非密钥、可提交）。
- **后端双 appid 改造**（此前 `WeChatMiniAppClient` 只有一组 `aicabinet.wechat-miniapp.app-id/secret`，而 `wxLogin`（C 端）与 `merchantWxLogin`/`bindMerchantWeChat`（B 端）共用——openid/access_token 按 appid 隔离，双小程序上线前必改）：
  - 新增 [MiniAppTarget](../services/trade-service/src/main/java/com/aicabinet/trade/wechat/MiniAppTarget.java)（CONSUMER/MERCHANT）；[WeChatMiniAppProperties](../services/trade-service/src/main/java/com/aicabinet/trade/config/WeChatMiniAppProperties.java) 加 4 字段（端专属凭证），`resolveAppId/resolveAppSecret(target)` 端专属缺省时**回退旧单组值**（存量部署零改动兼容），`isConfigured()` 语义=两端都配齐；[WeChatMiniAppClient](../services/trade-service/src/main/java/com/aicabinet/trade/wechat/WeChatMiniAppClient.java) 全部方法（code2session/getPhoneNumber/sendSubscribeMessage/generateUrlLink/accessToken）带 target，access_token 按 appid 分缓存（V310 同 appid 频率限制约束不变）。
  - 调用点端归属（9 处）：**CONSUMER**＝`AuthService.wxLogin`+`resolveWxPhoneNumber`、`ExternalNotificationDispatcher`（audience=CONSUMER）、`UnpaidOrderService` 催付、`CabinetOpenLandingController` URL Link、`WeChatPublisherStatClient` 流量主对账（**推定**广告位在消费者小程序，如流量主实际绑定在其它 appid 需回调）；**MERCHANT**＝`AuthService.bindMerchantWeChat`/`merchantWxLogin`、`MerchantNotifyService` 绑定+待办、`MerchantDeviceIncidentNotifyService`。
  - 配置面：`application.yml` wechat-miniapp 节新增 4 键；`.env.example`/`.env.production.example` 同步。
- **验证**：受影响 12 个测试类（Auth 3 + Dispatcher + 设备异常 + 商户待办并发 + SystemConfig 4 + Unpaid 并发 + 启动校验）**70 tests / 0 failure**；全局复扫无旧签名残留（`WeChatWebOAuthClient` 的 `appId()` 是公众号 H5 OAuth 另一组配置，无关）。**全量回归与 preflight 在提交前照常跑**。
### 2026-10-18（G11 冻结毛额对账判据 + G9 CORS 钉隔离栈 + G10 32 分人工归集，三件同轮收口）
- 🔴 **拍板**：用户 2026-10-18 晚选 **G9「钉进去」+ G11「本轮做完整链」+「现在按流水归集 32 分」**（三项均推荐项全采）。
- **G11 `PAID_GROSS_MISMATCH` 判据**（`80c35b72`）：`cabinet_order.paid_amount_cents` 必须 = 该单 COMPLETED 入账类型
  流水之和（V384/CB-057 定义逐字同构；类型过滤新 helper `PaymentOperationTypes.inboundTypesFilterSql`，继续单一事实源）。
  只扫冻结过的单（>0；0=从未扣款回落 total），**仅巡检不修**（该列是佣金基数，改写=动钱）。两个方向分开提示语：
  冻结>入账和=流水缺钱（资损方向，V384 fail-loud 同款）；冻结<入账和=冻结后未重放——
  **争议改单加价属已知设计形态**（V384「改单不得改写本列」+ `finalizeConfirmDispute` 不补冻结），佣金基数偏小方向；
  若日后拍板争议加价应补冻结，在 `finalizeConfirmDispute` 补 `freezePaidAmount` 即收敛。标签面三处同步（V392 + SysDictBootstrap + shared-dict src/dist）。
- **G11 验证**：单测 44/44 + SqlIT 12/12（账平含退款静默/资损方向报警/欠冻方向报警，真 Postgres）；隔离栈四段证明
  （播行/账平静默+对照版/破坏 999≠486 即 FAIL 资损方向提示语/恢复+清理探针三行全自愈 FIXED，零残留，failCount 回基线 1）
  ⇒ [evidence/paid-gross-judge-2026-10-18.md](evidence/audit-2026-10-13/trackC-admin/paid-gross-judge-2026-10-18.md)。
- **G9**：`docker-compose.uat-isolated.yml` trade-service 钉 `CORS_ORIGIN=http://localhost:28080`；
  实测带 Origin 请求 **200 + Allow-Origin 回显**（此前浏览器恒 403，纯 API 驱动器测不出）。
- **G10 32 分归集**：流水证据 EARN 181=total、USE 32（4 笔退款扣回漏归 used）⇒ 带前置条件 `UPDATE used_points 0→32`，
  恒等式 181=149+32+0 成立，复跑巡检 `POINTS_IDENTITY` 自愈 FIXED、`POINTS_BALANCE` 保持绿（record 6 销案）。
- **踩坑**：`DataConsistencyJudgeSqlIT.seedOrder` 对非 REFUNDED 单把 `paid_amount_cents` 置 0——
  毛额判据用例必须显式 `freezePaid()`，否则被 `>0` 过滤静默虚绿（第一版 IT 两条报警用例就是这么假绿的）。
- **落档**：`80c35b72`+`e43eaece`（admin 产物随 ConsistencyView 级别分类重建）快进推 origin/dev。

### 2026-10-18（G10 写侧修复落库：扣回归入 used 桶 + 撤自动回写 + 撤 max_points 死旋钮，CB-058）
- 🔴 **拍板**：对上一条「G10 取证（只查未改）」的三问，用户 2026-10-18 晚选 **A（扣回记入 `used_points`）+ D（撤掉
  POINTS_IDENTITY 自动回写，改「仅巡检需人工」）+ E（撤旋钮：删 `max_points`）**，取证文档随即推 origin/dev（`815c4b4f`）。
- **实现落库（`959a4694` + admin 产物 `5eb24626`，快进推 origin/dev）**：
  - A：`doClawbackPointsOnRefund` 补 `setUsedPoints(used + actual)`——`total = available+used+expired` 恒等式闭合，
    `total_points` 维持「累计发放只增不减」；流水 `points_type="USE"` 语义与账户列对齐（与兑换写法对齐）。
  - D：删 `fixPointsIdentity` 覆写式自动修复（曾把 total 181→149 静默改写「累计发放」），`POINTS_IDENTITY/POINTS_BALANCE`
    改「仅巡检记录，请人工核对处理」；`ConsistencyView.vue` 撤按钮与预览文案。
  - E：[V391__drop_member_level_rule_max_points.sql](../services/trade-service/src/main/resources/db/migration/V391__drop_member_level_rule_max_points.sql)
    删列（V163 以来全仓零读取死旋钮）；实体/DTO/两处映射/openapi.ts/后台表单列表全链清理，`minPoints` 列改标
    「下一档积分门槛（仅 C 端进度展示，判级不读积分）」。
  - F-7 欠账：竞品台账新增 **CB-058**（easygo 源码亲验/思迅/微信/京东京豆/农行/Snow Peak/Dynamics 365 共 7 家）+
    门禁别名 4 条；有意不同留痕：`actual = min(clawback, available)` 扣完为止不产生负积分、不从现金退款追偿（差额静默截断已如实标注）。
- **验证（全绿）**：目标 5 测试类 **61 用例 0 失败**（新 `MemberPointsClawbackIdentityTest` 修前红的 used_points 断言×2 + 幂等）；
  `check-competitor-benchmark` CB-058=7 家；preflight 47 门禁 0 失败 + openapi 结构档比对过。
- **隔离栈真库端到端（`aicabinet-uat` 换新镜像 `aicabuat`）**：`unzip -l` 实锤 jar 含 V390/V391 与新 admin 产物；
  Flyway **V390 重放 + V391 应用均 success、history 无重复行**，`member_level_rule` 仅剩 `min_points`；
  API 实跑 `POST /consistency/run` 检出 `POINTS_IDENTITY|1` FAIL（record 6）⇒ `POST /{id}/fix` 返回
  `fixed=false`「该类仅巡检记录，请人工核对处理」，**member 1 行原样（181/149/0/0）未被任何自动路径改写**。
- **剩余**：① ~~存量 32 分坏行~~ **已于同日晚按流水归集销案**（见下一条 Changelog：used 0→32，巡检自愈 FIXED）；
  ② ~~A 的真退款链路场景~~ **已于 2026-10-19 真链路 e2e 闭合**（UAT 隔离栈：MQTT 购物→云识别退役自动降级运营工作台
  manual-resolve CONFIRM 真实人工路径成单 PAID 686→消费者全额退→`used+=8/available-=8/total 不动`/恒等式归零/
  `points_log` EARN+USE 双行/巡检 POINTS 两判据零报警——
  [evidence/points-clawback-e2e-2026-10-19.md](evidence/audit-2026-10-13/trackC-admin/points-clawback-e2e-2026-10-19.md)，
  驱动脚本 `scripts/e2e-points-clawback-refund.ps1` 可重复执行）；
  ③ G9 CORS_ORIGIN / G11 `paid_amount_cents` 对账**均已同轮收口**（见下一条 Changelog）。

### 2026-10-18（数据一致性判据修复轮：两条假阳性清零 + 三域钱包判据泛化 + 流水链连续性 + 口径单一事实源）
- 🔴 **拍板**：对上一条 §12 登记的两条既有巡检口径缺口，用户 2026-10-18 选「**两条都修**」；对「数据一致性要不要多加验证 SQL」，
  采纳「按建议」= 候选 1（三域钱包判据泛化，含补货员）+ 候选 2（`balance_after` 链连续性）并入本轮，
  `paid_amount_cents` 对账**另起一轮**，`POINTS_IDENTITY` **只查不改**。证据：
  [evidence/consistency-judge-fixes-2026-10-18.md](evidence/audit-2026-10-13/trackC-admin/consistency-judge-fixes-2026-10-18.md)。
- **净额口径单一事实源**：[PaymentOperationTypes.java](../services/trade-service/src/main/java/com/aicabinet/trade/domain/PaymentOperationTypes.java)
  定为 `payment_operation` 金额类型的唯一口径（进 `CHARGE/ADJUST_CHARGE/PREAUTH_CAPTURE`、退 `REFUND`，暴露
  `netCaseSql(prefix)` / `netTypesFilterSql(prefix)`）；`DataConsistencyService` 的 **4 处手写副本**（报警判据 / 提示语净额 /
  **两条会动钱的修复路径取数**）全部改为取自它，本类内**禁止再写字面清单**。修掉 4×`PAYMENT_AMOUNT` 假阳性
  （余额预付单收款只写 `PREAUTH_CAPTURE` ⇒ 期望 486 / 实际 0）——F-10 当初只修读路径，巡检是漏掉的副本之一。
  新门禁 [check-payment-net-types-single-source.mjs](../scripts/check-payment-net-types-single-source.mjs) 钉住（聚合 **47** 条），
  两处**应当**排除 `PREAUTH_CAPTURE` 的渠道现金口径显式登记豁免与理由。
- **退货退款守卫**：`checkCrossLinkRefundInventory()` 补 `AND o.inventory_deducted = TRUE` ⇒ 修 2×`CROSS_LINK/RFINV` 假阳性
  （F3-FIND-1 修法 B 的未扣库存退货，压根没扣过库存 ⇒ 没有回架流水才是正确形态）。
- **三域钱包判据泛化**：删 6 个逐域常量，改 `WALLET_DOMAINS=[MERCHANT,LINE,STAFF]` + **后缀拼 `checkType`**，
  补上 `DataConsistencyService` 从来没有的 **STAFF 钱包判据**（统一前也没有 ⇒ 非统一引入的缺口）。
  `applyFix` 与前端 [ConsistencyView.vue](../clients/admin-vue/src/views/consistency/ConsistencyView.vue) 的 `isWalletCheckType()`
  一律**按后缀识别**（`*_WALLET` / `*_WALLET_LEDGER_SUM` / `WALLET_LEDGER_CHAIN`）——逐个枚举正是 STAFF 当初漏登记的原因，
  也是 `*_WALLET_LEDGER_SUM` 从 P3-1a 起就在写库、前端却不认识它的原因。
- **新增链连续性判据 `WALLET_LEDGER_CHAIN`**：`LAG(balance_after) OVER (PARTITION BY domain, owner_id ORDER BY created_at, ledger_id)`，
  备忘行（`WITHDRAW_FREEZE`/`WITHDRAW_RELEASE`）`effect_cents` 记 0，跳过每 owner 首行（V388 搬运的历史首行不保证从 0 起）。
  闭合「Σ 与最近余额都自洽、中间某行被改写过」的盲区。🔴 **三域共用一个 checkType ⇒ 必须一次全域跑完**：
  `resolveStaleFailuresIfComplete` 会把该类型下本轮未检出的 FAIL 自动置 FIXED，放进 per-domain 循环就会互相抹掉（代码注释已钉）。
  标签面三处同步 + 幂等迁移 [V390__register_wallet_consistency_check_types.sql](../services/trade-service/src/main/resources/db/migration/V390__register_wallet_consistency_check_types.sql)。
- **验证（全绿）**：`DataConsistencyJudgeSqlIT`（Testcontainers 真 Postgres）**9/9**（含「Σ/latest 静默、链必须报警」与
  「真实提现四行必须静默」两条负向证明），已登记 `check-docker-tests-guarded`（`*IT.java`→integration job）；
  `DataConsistencyServiceTest` **41/41**；trade-service 全量 **2034 tests / 0 failure / 0 error BUILD SUCCESS**；聚合 **47** 门禁 0 失败。
- **隔离栈实跑（`aicabinet-uat`，重启到新镜像、Flyway 追到 v390）**：`POST /api/v2/ops/admin/consistency/run`
  failCount **8 → 2**，6 条过期 FAIL 全部由 `resolveStaleFailuresIfComplete` 正常判为已消失并落 `FIXED`
  （不是删行）。剩 2 条均为真待办：`POINTS_IDENTITY|1`（181 vs 149，差 32 分未查到底）、`CROSS_LINK/DSP|…`（F2 驱动器留下的未结案工单）。
  ⇒ 运营台「数据一致性」页在真实数据上从 **6/8 是噪音** 收敛到 **0/2 是噪音**。
  教训 #325 的负向证明按 STAFF 域重做了一遍四段（本栈 STAFF 0 行，静默本身不构成证据）：
  播一致账→静默（含备忘剔除口径生效）/ 只改中间行→**只有链报警**、另两条静默 / 只改账户余额→STAFF 两条报警、链静默 / 清探针→回到 2 且全 `FIXED`。
  浏览器实跑 admin 页：V390 中文标签渲染、4 条钱包行级别一律「高」、操作列一律禁用「需人工：该类仅巡检记录」，
  一次点击同时看到三条钱包判据各自为红。「未通过」6 → 清理后回到 2，与 API 侧一致。
- ⚠️ **顺带查出隔离栈一条环境口径缺口（登记，未改仓库）**：浏览器登录后台恒 **403 `Invalid CORS request`**——
  `docker-compose.full.yml:179` 是 `CORS_ORIGIN: ${CORS_ORIGIN:-http://localhost}`，而隔离栈把 gateway 重映射到宿主 **28080**，
  两者不等 ⇒ Spring CORS 直接拒（curl 不带 `Origin` 则 200，所以纯 API 驱动器**测不出来**；共享栈用 8080 同源故未暴露）。
  本轮只本机用 `CORS_ORIGIN=http://localhost:28080 docker compose … up -d trade-service` 绕过。要不要钉进
  `docker-compose.uat-isolated.yml`（隔离栈 admin UI 才开箱可用）属环境口径 ⇒ §10 G9 等拍板。
- **同日一条既有口径事实（只登记）**：`data_consistency_record` 的同一 `(check_type, check_key)` **再次检出会再插一行**
  （STAFF 探针留下 `STAFF_WALLET|UAT-STAFF-PROBE` 两行 FIXED）⇒ 自愈断言只能按 key 判存在，**不能按行数判「清了几条」**。
- **同轮补做 G10 取证（同日，只查未改）**：`POINTS_IDENTITY|1` 差的 32 分**已定位到写侧**——退款扣回只减
  `available_points`（`MemberService:316-323`），而流水 `points_type` 只有 `EARN/USE/EXPIRE` 三值 ⇒ 扣回本该归 `used` 桶。
  同行 `POINTS_BALANCE` 为绿（余额与流水逐分自洽）⇒ 判据没错、余额没坏，坏在消耗归集漏记。
  🔴 并查出 `fixPointsIdentity` 的自动修复**方向相反**（把 `total_points` 下调凑三桶，事务内实测 181→149 / H5 gap 319→351 后回滚），
  以及等级判定根本不引用积分、`max_points` 全仓零读取两条口径缺口。
  取证 [evidence/points-identity-investigation-2026-10-18.md](evidence/audit-2026-10-13/trackC-admin/points-identity-investigation-2026-10-18.md)；
  修法与三条待答问题入 [POINTS_IDENTITY_DECISION_BRIEF_2026-10-18.md](POINTS_IDENTITY_DECISION_BRIEF_2026-10-18.md)（建议 A+D+E②，**未动代码**）。
- **落档**：提交 `e39ca4e6`（139 文件）已 refspec 推 origin/dev（快进 `d5dee737..e39ca4e6`），CI run 39118704110 **五作业全绿**
  （build / integration / admin-artifacts / mini-programs / e2e-h5）。补记一条迁移惯例：V390 首次落库后才补上
  `MIGRATION_KIND: backfill` 等评审头注释（`check:flyway-seed-separation` 要求数据写语句必须显式声明种类），
  checksum 随之漂移 ⇒ 隔离栈已 `delete from flyway_schema_history where version='390'`，下次启动按幂等 INSERT 重放（不产生重复行）。

### 2026-10-18（§12 待拍板 #1 落定：三域钱包**物理统一**为单账本 V388/V389 + 唯一写原语引擎）
- 🔴 **拍板**：2026-10-18 用户对「三域钱包要不要抽泛型账本原语」选 **A 案「彻底解决干净」**（简报 [WALLET_UNIFY_DECISION_BRIEF_2026-10-18.md](WALLET_UNIFY_DECISION_BRIEF_2026-10-18.md)）。两条锁定范围：① **只统底层**——存储 + 写原语统一，各域 REST 端点 / DTO / 生成的 `openapi.ts` / 后台视图**维持现状**（facade 层委托，DTO 形状不破）；② **平台资金池不并入**——`platform_fund_account` 是资产/备付侧、无冻结无幂等、主键粒度不同，与三域应付钱包语义正交，强并 = 泛型被撑成 `if(domain)` 泥球，维持 `PlatformFundService` 独立领域（CB-033 早定性「本仓新增领域、无竞品先例」）。
- **迁移**：`V388` 新建两张多态表 `wallet_account` / `wallet_ledger`（`domain` 判别列 + `owner_id VARCHAR(64)`，复合键 domain+owner_id；line/staff 的 BIGINT 以 `::text` 落入），从 6 张同构旧表**只读搬运**演示余额（不写死锚点）；`V389` 删除 merchant/line/staff × account/ledger 六旧表。幂等兜底：`uk_wallet_ledger_ref` partial 唯一索引 `(domain, owner_id, ref_type, ref_id) WHERE ref_type IS NOT NULL AND ref_id IS NOT NULL AND ref_type <> 'WITHDRAW'`（提现生命周期天然一 ref 多行，豁免）。`COMPETITOR_REF: CB-054,CB-033`（三域钱包本体见 CB-054、资金池不并入见 CB-033）——**刻意不新造无实证台账条目**。
- **共享层**：新增 `domain/{WalletAccount,WalletLedger}` 实体 + `mapper/{WalletAccountMapper,WalletLedgerMapper}`（复合键刻意**不**继承 `BaseTradeMapper`，单账户读锁/更新走 `(domain, owner_id)` 显式 SQL）+ `service/wallet/{WalletDomain,WalletEngine}`。**`WalletEngine` = 统一后唯一资金写入口**：把三域 `*WalletService` 里同构的「分布式锁 + 行锁 + 幂等查重 + 余额/冻结增减 + 流水记账」收敛一份，按 `WalletDomain`+`ownerId` 参数化；能力差异（商户可用额扣、补货员余额直扣、冻结生命周期仅商户/线长）用**不同方法**表达而非 `if(domain)`。🔴 锁串由 `WalletDomain.lockKey` 逐字沿用统一前原语（`merchant:wallet:` / `line:wallet:` / `staff-wallet:`），与各提现服务同一把可重入锁，改串会击穿钱包↔提现互斥 ⇒ 双扣。
- **facade 委托**：`Merchant/Line/StaffWalletService` 全部转调 `WalletEngine` 并把统一行**读映射**回各域 view 实体（`toLedger`/`toAccount`）——端点/DTO 不动。删除六旧 Mapper + 三 `*WalletAccountMapper.xml`（**删 Mapper 连带删 XML**，避 lesson #233「漏删 XML 启动崩」）；六 view 实体降级为纯内存读模型（注解收尾见下一条）。
- **对账 / 裸 SQL 消费者改写**：`PayoutReconciliationService`（商户+线长出款两侧）与 `MerchantWithdrawPayoutService`（今日手续费收入）改走 `WalletEngine` 的 domain-scoped 求和；`DataConsistencyService` 四条钱包检查（MERCHANT/LINE × ledger-sum / latest-balance）改在单张 `wallet_account`/`wallet_ledger` 上按 `domain` 过滤；两个审计脚本 [scripts/full-round-p0-04-split.mjs](../scripts/full-round-p0-04-split.mjs)、[clients/admin-vue/tests/audit-admin-flows-uat.mjs](../clients/admin-vue/tests/audit-admin-flows-uat.mjs) 的硬编码旧表名改 `wallet_*` + `domain='MERCHANT'` + `merchant_id`→`owner_id`。
- **验证（全绿）**：`mvn -pl services/trade-service -am clean compile` 877 源文件；全量单测 **2031 tests / 0 failure / 0 error**；`node scripts/run-audit-gates.mjs` **46 门禁 0 失败**（含 `check:competitor-benchmark`）；`WalletRefIdempotencySqlIT` 在**一次性空库**上 Flyway 从零跑全史（含 V388/V389）⇒ **5/5 绿**——库级幂等兜底与三域搬运端到端成立。一处测试口径坑：`MerchantWalletFeeSplitTest` 需 stub `findByOwnerForUpdate`（返回 `Optional` 的 default 包装），而非其底层 `findByOwnerForUpdateRaw`——Mockito 拦截 default 方法不委托底层。
- **运行期回归已补（2026-10-18，`aicabinet-uat` 隔离栈就地升级 V386→V389）**：首次在**有真实数据**的库上执行 V388 六表回填（升级前后 38 行归一化多重集**双向差 0**）；F2–F8 驱动器复跑 **34 PASS / 0 FAIL / 2 SKIP**（两条 SKIP 属既有说明）；`POST /api/v2/ops/admin/consistency/run` 真跑生产对账 SQL ⇒ 四条钱包判据全静默。🔴 **静默≠有效**：本栈 `wallet_account` 只有 1 个 MERCHANT 账户、LINE/STAFF **0 行**，故播了一个 LINE 探针做负向证明——账平时无报警（含 `WITHDRAW_FREEZE` 备忘剔除口径生效）、余额 800→1000 后**两条 LINE 判据同时报警**、清探针后自动 `FIXED`。详见 [evidence/wallet-unify-runtime-regression-2026-10-18.md](evidence/audit-2026-10-13/trackC-admin/wallet-unify-runtime-regression-2026-10-18.md)。补货员(STAFF)侧仍无生产对账判据（`git show 0c7765a5^` 同样零命中 ⇒ **非统一引入**），运行期证据只有单元/并发用例 + F4/F8 探针。**（→ 同日已补：三域泛化 + STAFF 探针四段负向证明，见上一条 Changelog。）**
- 🔴 **仍待监督环境复跑**：F1 结算链须 2026-10-19 真·将邑柜复跑（隔离栈模拟器 ws `code=1006` 秒断，不能代替）。
- 🔴 **本轮顺带查出两条既有巡检口径缺口（当日拍板「两条都修」并已落地，见上一条 Changelog；登记时未动代码）**：8 条报警里 6 条是**假阳性**——① 4×`PAYMENT_AMOUNT`：`DataConsistencyService:319` 的 `operation_type IN ('CHARGE','ADJUST_CHARGE','REFUND')` **漏 `PREAUTH_CAPTURE`**，与已修的 F-10（`PaymentOperationMapper:116`）**同一处遗漏、巡检侧没跟着修**，余额预付单收款全被算成净入账 0；② 2×`CROSS_LINK/RFINV`：判据缺 `AND o.inventory_deducted` 守卫，对 F3-FIND-1 修法 B 的「未扣库存退货退款」要求不存在的库存流水。改的是资金巡检判据 ⇒ 只登记等拍板。
- **同日两处收尾欠账（非拍板项，已清）**：
  - **六 view 实体摘掉 ORM 注解**：取证确认它们仍被 facade 返回类型、`MerchantWithdrawService`/`LineManagerService`/`OpsStaffWalletController` 与 7 个测试引用 ⇒ **类不删**（删等于改 facade 签名，破「各域 DTO 维持现状」的锁定范围），但 `@TableName("merchant_wallet_account")` 这类注解声明的表已被 V389 DROP ⇒ **注解删除**、各类改一行 javadoc 标明来自 `wallet_account`/`wallet_ledger` 的哪个 domain。行为中性实证：`clean compile` 绿 + 全量 **2031 tests / 0 failure**（无任何 Mapper 触达 ⇒ MyBatis-Plus 从不解析其 TableInfo；`BaseTradeMapper:97` 的 `TableInfoHelper` 路径不经过这六类）。`DataConsistencyService` 的 `MERCHANT/LINE_WALLET_LEDGER_SUM` 是**对账检查类型标识**（已入库的历史行 + 前端读它），不是表名，不改。
  - **§12 待拍板 #8 的上线手册勾选项落地**（[DEPLOYMENT_CHECKLIST.md](DEPLOYMENT_CHECKLIST.md)）：§3.1 的「资金类定时任务已按需手动开启」改成**逐条确认开启状态**（2026-10-17 拍板是上线当天两条**都保持关**，原措辞等于反向默认）；§3.1.2 补拍板日期与「不开的后果」，给 `refund-retry`/`deposit-monthly-refund` 各留一行「开/关决定 + 决定人 + 日期」空白，并把审计原建议「refund-retry 一上线就该开」**保留**在表里 ⇒ 与拍板的张力是显式记录的，不靠记忆。顺带把「Flyway 迁移至最新（V340+）」订正为高水位 **V389**。
  - ⚠️ `docs/PRODUCTION_TOPOLOGY.md` 仍是**主树未跟踪文件**（peer 的本轮工作，未进 origin/dev）⇒ 上线手册口径写进已跟踪的 `DEPLOYMENT_CHECKLIST.md`，不去碰那份。

### 2026-10-17（DC-27(b) 批 2/批 3/批 6 实删 + 提现费率注册表对齐 + 锁柜阈值登记 + 平台分账「先不收」落地）
- **DC-27(b) 拍板「现在删了」→ 隔离分支 `chore/dc27-remove-vision`（跑完再合 dev）**，落实 2026-10-11 判定文档 §3 的批 2 + 批 3 + 批 6：
  - **批 3** `d83a9380`（151 文件 +242/−8003）：仓库根 `vision-service/` 整目录（FastAPI 引擎 + 训练装置 + datasets/models + Python 测试）、`pyrightconfig.json` / `.run/vision-service.run.xml` / `.cursor/rules/vision-service.mdc` / `infra/.env.vision-dev.example` / `infra/models/production/`、14 个旧识别 e2e/训练脚本、`VisionApiProperties` + `VISION_API_KEY` 启动校验、`VISION_MODE_*`/`DemoVisionSeed`/`MIN_VISION_CONFIDENCE` 死常量，env/compose/告警/Grafana/`docker-up.ps1` 同步。**Kafka/MinIO 按拍板保留**（通知分发 + 争议证据/OTA 在用）。
  - **批 2** `3cc19429`（37 文件 +441/−1057，含本条文档回写）：`SettlementRecognitionService` 的 `withGravityFallback`/`trySettleWhenReviewRequired`/`trySettleDevMock`/`tryStagingGravitySettle`/`allowsGravityEvidenceSettle`/`allowsSandboxGravityFillSettle`、`GravitySettlementHelper` 的 `mergeWithVision`/`reconcileWithGravity`（净 −132 行）、`SettlementOrderSupport.gravityDeltasForInventory`、`InventoryService.deductForOrder` 的重力融合重载全部删除；两个开关整键删除——`aicabinet.gravity-fallback-settle`（+ `StagingProperties.gravityFallbackSettle`）与 `settlement.recognition_mode`（连带 DC-27(a) 那道 `assertConfigValueAllowed`/`ALWAYS_AUDITED_KEYS`/`isForcedAuditKey` 闸门与 `SystemConfigRecognitionModeTest` 168 行，铁律 62），`settlement.empty_auto_complete_no_gravity` 改名 `settlement.empty_auto_complete`（V385 纯 DML，**历史留痕不清**）。**保留**的重力 API 只服务取证/展示且逐个验过有消费者：`mergeGravityJson`（门事件）、`parse`/`hasSlotSpecificDeltas`（补货货道快照）、`fromRequestItems`（实时购物车）、`toRecognizedItems`（演示关门判空 + 争议分类）。
  - **挣绿来源改造（这批改动的真正验收）**：`ConsumerE2ETest` 不再靠 gravity fallback，而是把演示柜登记进 `jiangyi_device`（BOUND）+ `evict` 进程内负缓存 ⇒ 断言关门停在 `RECOGNIZING` ⇒ 走生产同一入口 `POST /internal/v1/vision/edge-results` ⇒ 断言 `PAID`；`SettlementDisputeTest` 重写 12 例（正向钉「重力列存在也按上报明细扣库」、负向钉四种非生产 modelVersion 一律转争议且零资金移动），`GravitySettlementHelperTest.settlementFusionApisStaysRemoved` 防融合 API 复活。**实测**：`mvn -B -pl services/trade-service -am -DskipITs test` → **2041 tests / 0 failure**；`node scripts/run-audit-gates.mjs` 49 门禁 0 失败。
  - ⚠️ **行为后果（用户需知情，H5/脚本改造另列待办 #24）**：mock 环境的「演示关门」`POST /api/v2/sessions/{id}/demo-close`（Controller 按 `mockEnabled` 放行，生产 403）在**购物车非空**时不再成单，而是转 `DISPUTED` 且不扣款；要演示「买了并扣款」须柜机登记端侧识别柜 + `edge/device-simulator` 的 `JiangyiDeviceSimulator` 上报。教训见总册 **#318**（删兜底必须同批改挣绿方式）与 **#319**（删配置键要连闸门/留痕/前端/注册表/测试一起清）。
  - **批 6 旧录像链三段删除**（逐条 file:line 见总册「2026-10-17 DC-27(b) 批 2/批 3 落地」表的批 6 行）：6-A `cd74d31c`（22 文件 +12/−556）删下游死代码 `VideoArchiveService`/`VideoAttachRequest`/`VideoClipDto`/`VideoUploadPresignResponse`、`MinioVideoService` 的上传预签名与 `copyObject`/`parseStorageUri`、`ObjectStorageKeys` 四个录像键、`CabinetConstants.UPLOAD_STATUS_*`；6-B `5c80a4c9`（17 文件 +63/−565）删模拟器录像上传链并把门事件报文收紧到 5 字段，`EdgeCloudMqttContractTest` 改为**钉住「旧四键云端一律忽略」**；6-C `fac98345`（16 文件 +21/−252）按用户拍板「**全部撤下**」撤历史 `video_uri`/`upload_status` 的运营可见面（工作台 `UPLOAD_STUCK` 卡片与「上传 X」文案、`waitingUploads`/`sessionWaitingUpload` 两个 KPI、会话列表 `uploadStatus` 筛选 + CSV 列、`AdminSessionDto` 三字段、`SessionService.applyVideoMetadata`＝全仓最后一个写这三列的地方、商户端 `orderVideo` 单数死链与 `SOURCE_EDGE`）。**DB 四列与历史行不清**；争议/异常中心历史只读回放、`presignPlaybackUrl`/`streamTo`/`cabinet-videos` 桶（证据图/媒体/OTA 在用）保留。
  - **批 6 验证**：`mvn -o -B -pl services/trade-service -am -DskipITs test` → **2032 tests / 0 failure / 0 error BUILD SUCCESS**（较批 2 少 9 例，用两轮 surefire 逐类核销：`ObjectStorageKeysTest` 4 + `VideoArchiveServiceTest` 4 + `SessionDoorClosedIdempotencyTest` 4→3，无静默丢例）；`node scripts/run-audit-gates.mjs` 49 门禁 0 失败；merchant-mp `order-video-url.test.ts` vitest 7 例绿；`openapi.ts` 由**本轮构建 jar** 在一次性 probe 库（`:18181` + scratch DB + Redis db15，跑完 `DROP … WITH (FORCE)`）现抓 `/v3/api-docs` 重生成，diff 恰为 4 处字段删除；admin 产物按 CI 同源（`node:24.18.0` + pnpm 9.15.9 `--frozen-lockfile` + `scripts/build-admin.mjs`）从干净克隆重建，215 条 hash 路径变更，字节核销旧文案 `争议 · 上传` 0 命中。**两个坑**：`scripts/build-admin-ci-fidelity.sh` 在 linked worktree（容器内解析不到 Windows 绝对 gitdir）与 **shallow clone**（bundle 遍历父提交失败）下都不可用，改宿主侧 `git clone --depth 1 --no-hardlinks` 再挂载容器构建；`@EnableScheduling` 在 `TradeServiceApplication` 上无条件，**probe 库绝不能指向共享 `aicabinet`**。
  - **批 6 剩余·vision-async Kafka 传输链整体删除**（`f30635ba`）：删 `SettlementVisionAsyncService`/`VisionRecognitionProducer`/`VisionRecognitionListener`/`VisionAsyncProperties`/`KafkaTopicConfig`（视觉 topic）+ 对应测试，`KafkaTopics` 只剩 `NOTIFY_DISPATCH_REQUEST`，`application.yml` 去掉 `vision-async.enabled`，`SettlementService` 构造器 11→9 参（两个纯转发方法删掉），`SessionSettleService` 去掉投递分支。**行为中性依据**：该开关硬编码 false 且三份 profile 无覆盖 ⇒ 投递分支从未在发布口径下跑过；将邑会话关门停在 `RECOGNIZING` 来自 `SessionDoorService:81-83` 的将邑豁免，与开关无关；`recognition_task_id` 仍有活写入者 `SettlementRecognitionService:104` 与读方 `DisputeService:196`。**顺带修掉一处静默失效**：`@EnableKafka` 原先只挂在 `KafkaEnableConfig`，而它的条件是已退役的 `aicabinet.vision-async.enabled`（默认 false）⇒ 唯一在用的 `NotificationDispatchListener` 即便 `NOTIFY_ASYNC_ENABLED=true` 也不注册消费；现改挂 `aicabinet.notify.async-enabled`，并用 `KafkaEnableConfigTest`（`ApplicationContextRunner`，仓内首例）钉住 true 有 `KafkaListenerEndpointRegistry`、false/缺省没有。**验证**：全量 **2030 tests / 0 failure / 0 error BUILD SUCCESS**（−2 = 删 `VisionRecognitionListenerTest` 5 例 + 新增 `KafkaEnableConfigTest` 3 例，surefire 逐类核销）+ 49 门禁 0 失败。**保留不动**：HTTP 直报链 `POST /internal/v1/vision/edge-results`（识别结果唯一入口）、reserved Kafka SCRAM 用户 `vision`（无代码消费方，部署面**待拍板**）。**配套口径订正**：两个 devops 验证脚本的「前置＝-D 开异步通道」是错的，真前置是**柜机是否登记为将邑端侧识别柜**（读 `jiangyi_device.status=BOUND`，非 JVM 开关）；2026-10-17 核对共享 dev 库时唯一 BOUND 的是审计夹具柜 `500909625160`，两脚本内 `DEVICE_ID=330449777078` 未登记 ⇒ `verify-vision-result-ingest.py` 直跑会在 B 步中止（已写进脚本自述），两者互斥、只能各跑各的环境。
  - **批 6 剩余·`MinioVideoService` 死注入清理**（`f7516c77`）：批 6-A 删掉上传预签名/`copyObject` 后，`SessionInternalController`/`AdminDashboardService`/`MerchantFinanceService`/`OpsSessionOrderQueryService`/`SessionDoorService`/`SessionService` 六处只剩「注入+赋值」、类内零调用 ⇒ 连同构造器参数一并删（`SessionService` 22→21 参、`SessionDoorService` 10→9 参），8 个测试文件的手工构造点按位置删实参。**计数勘误**：台账原记「7 处」是估值，实测 **6**——全仓 `minioVideoService.` 调用点只落在 `DisputeService`/`FileAttachmentService`/`MediaAssetService`/`MerchantPortalService`/`OtaCdnService`/`OpsExceptionService`（经 `OpsExceptionServiceSupport.minioVideoService()` 访问器），`OpsExceptionServiceSupport` 的注入是活的、不算死注入。验证：2030 tests / 0 failure / 0 error（例数与批 6-D 后一致，无静默丢例）+ 49 门禁 0 失败。
  - **批 6 收尾·`UPLOAD_STUCK` 异常扫描族 + `ops:session:upload` 权限码全撤**（`fd583f18` + V386；用户 2026-10-17 拍板「A 全撤」+「权限行改 `RETIRED` 留痕」+「**标签层全留**」；拍板面见 [AUDIT_DECISION_BRIEF_2026-10-16.md](AUDIT_DECISION_BRIEF_2026-10-16.md) §15）：删 `OpsExceptionScannerService` 的 `WAITING_UPLOAD → UPLOAD_STUCK` 一路扫描与 summary 里的「上传 N」项、删四处阈值旋钮（`SystemConfigService` 常量+种子、`OpsMonitoringProperties.uploadStuckMinutes`+`application.yml` 的 `OPS_UPLOAD_STUCK_MINUTES`、`ops/feature-flags.json` 条目、`AlertRuleView.vue` 白名单+中文标签）、后端两处 OR 与前端 7 处 `hasPerm` 收敛为只认 `ops:session:list`（DB 实证：持有 `upload` 的 `admin`/`operator` 本来就同时持有 `list` ⇒ 无人失去访问）；新迁移 [V386__retire_upload_queue_perm_and_stuck_knob.sql](../services/trade-service/src/main/resources/db/migration/V386__retire_upload_queue_perm_and_stuck_knob.sql) 把 `ops_permission` 160 改 `status='RETIRED'`（**不删行、不删 `ops_role_permission` 授权、不改历史迁移** V14/V93/V94/V118/V126/V138/V144；`OpsPermissionMapper.xml:17,29` 一律 `status='ACTIVE'` 过滤 ⇒ 改状态即全域失效，菜单管理页 `includeInactive` 仍可见＝留痕）+ 删 `system_config` 的 `ops.scan.upload_stuck_minutes` 行（参数配置页/告警规则页直接列 `system_config` 全表，只摘注册表会留一行孤儿死旋钮）。**刻意全留**：`SessionState.WAITING_UPLOAD` 枚举与全部读侧（openapi 产物不变）、`sys_dict_data` 三行、`SysDictBootstrap:205/328` 种子、`shared-dict` 三处映射、`BigScreenView:650/683`、merchant-mp `alerts.vue:251` / `todo-list.ts:211` —— 历史 `ops_exception` 行里已有的 `UPLOAD_STUCK` 仍要渲染成中文。**验证**：2030 tests / 0 failure / 0 error BUILD SUCCESS（例数不变；V386 在测试库随 382 支迁移一次性跑到 v386）+ 49 门禁 0 失败 + V386 在共享 dev 库 `BEGIN;…ROLLBACK;` 内真跑 `UPDATE 1 / DELETE 1`（回滚后核对：160 仍 ACTIVE、配置行仍在、2 条授权仍在）。
  - **批 6-G·reserved Kafka SCRAM 用户 `vision` 删除**（`aa9d409a`；用户 2026-10-18 拍板「直接删掉」）：批 6-D（`f30635ba`）删 vision-async Kafka 传输链后该 SCRAM 用户已无任何代码消费方，属部署面死凭据。四处一并清：`infra/docker-compose.yml` + `infra/docker-compose.full.yml` 删 `rpk acl user create vision` 与 `KAFKA_VISION_PASSWORD` env、`superusers` 由 `["admin","trade","vision"]` 收为 `["admin","trade"]`（幂等 `rpk cluster config set`，下次起栈即覆盖旧值）；`infra/redpanda/redpanda.sasl.yaml` 模板 superusers 去掉 `vision`；`infra/.env.production.example` 删 `KAFKA_VISION_PASSWORD` 占位、注释「三组凭据」改「两组」。**保留不动**：HTTP 识别入口 `POST /internal/v1/vision/edge-results`、`infra/prometheus/alert_rules.yml` 的 `biz:vision` 标签与 `infra/monitoring/alerts.yml` 的 `vision_recognition_duration_seconds_bucket`（非 Kafka SCRAM 用户，属监控面，是否随识别链退役另议）。**已起过的集群**里 `vision` 账号不会自动消失，但既不在 superusers 也无 topic ACL ⇒ 无害孤儿；如需彻底清可一次性 `rpk acl user delete vision`（未内置进 bootstrap，避免永久死代码）。**验证**：三 YAML 解析 OK + `check-env-placeholders` EXIT=0 + 49 门禁 0 失败 + preflight EXIT=0。**至此批 6 全部闭环。**
  - **批 6-H·自研 `edge/android-app` 整模块退役**（分支 `chore/retire-android-app`，2026-10-18；用户拍板「暂时只上将邑套件，未来不确定，现在可先移除」→ 选 A 全删+git 历史留档）：2026-10-11 已拍板生产柜体=将邑硬件，经 `jiangyi-gateway`（WS/HTTP）驱动开门/上报、识别走 `POST /internal/v1/vision/edge-results`、视频走 OSS `jiangyi_order_video`；自研 Android 工控端（Kotlin + 创智辉串口锁 HAL + MQTT 客户端）无任何生产路径 ⇒ 整目录 `git rm`。**取证边界（防误删）**：`e2e-lib.ps1:971-973` 实际仍拉起**旧 MQTT `DeviceSimulator`**（非 `JiangyiDeviceSimulator`），批 1 文档「UAT 切 JiangyiDeviceSimulator」为**不实**——故 `edge/device-simulator` 与 `services/device-service` 是 CI/E2E 承力件，**保留不删**；删除面严格限于 `edge/android-app`。连带清理：删 3 个只服务 android 的门禁（`check-android-tests-wired`/`check-edge-vision-wired`/`check-edge-queue-threading`）及 package.json 链+定义、`scripts/devops/verify-edge-threading-gate-drift.py`；`check-edge-cloud-mqtt-contract` 由「三方」（proto⟷Java⟷Kotlin `MqttDeviceClient.kt`）改「两方」（proto⟷Java 常量），死 topic 扫描（C6，走 `services`+`edge`）实测 4 个 `MqttTopics` 全部仍被 device-simulator Java 使用 ⇒ 无需裁剪；`EdgeCloudMqttContractTest.java` 仅改 Javadoc（原引 Kotlin 文件，逻辑本就只跑云端 MQTT 监听，云端 ALERT 分支保留给在网老固件）。CI 删末尾 `edge-android` job（ci.yml 1022→918 行，YAML 校验通过）；`.dockerignore`/`ai-cabinet.code-workspace` 去 android 引用；README/ARCHITECTURE/MODULES/CODEBASE_INVENTORY/CODEBASE_FOUNDATION/MASTER_TEST_PLAN 边缘面回写。**验证**：46 门禁（49−3）0 失败 + preflight EXIT=0 + `mvn test -pl services/device-service -am` 绿 + 全仓 grep 无 `edge/android-app` 悬空引用（除退役留痕）。
- **F-9 佣金基数改「收款时冻结毛额」（V384）+ F-10 净额过滤器补 `PREAUTH_CAPTURE`（`5ec4020f`）**（用户 2026-10-17 拍板：F-9「订单新增 paid_amount_cents，支付成功时冻结毛额、退款不再改它；净额=paid−refunded」、F-10「修：一行过滤 + 真调 mapper 的用例」）：
  - **F-9** 新增列 `cabinet_order.paid_amount_cents INT NOT NULL DEFAULT 0`（[V384__order_paid_amount_snapshot.sql](../services/trade-service/src/main/resources/db/migration/V384__order_paid_amount_snapshot.sql)），值定义 = 该单 `payment_operation` 中 COMPLETED 的 `CHARGE + ADJUST_CHARGE + PREAUTH_CAPTURE` 之和，**与 `PaymentOperationMapper.chargedCompletedCents` 逐字同构** ⇒ 迁移回填与运行期冻结算的是同一个数。写入点 [`OrderPaymentService.freezePaidAmount`](../services/trade-service/src/main/java/com/aicabinet/trade/service/OrderPaymentService.java)（只增不减，调用方 save）：`SettlementOrderFinalizeService.finalizeOrder` 扣款成功后、`UnpaidOrderService.markPaid` 两支（净额已覆盖 / 补扣成功）；争议结案的 `DISPUTED→PAID` 重放**不加**（那单早已冻结，多调只是多一次查询）。读侧 [`CommissionBaseCalc`](../services/trade-service/src/main/java/com/aicabinet/trade/service/CommissionBaseCalc.java) 新增 `grossCents`：`冻结值>0 ? 冻结值 : total`（0=从未扣到过款 ⇒ 回落保住历史单），`netCents = max(0, gross − refunded)`。`total_amount_cents` 继续当「剩余应付」给分账/退款用，三条回写路径不再污染佣金基数。
  - **取证（CB-057，4 家主体含 2 家同行）**：微信支付交易账单「退款/撤销行的订单金额填 0.00」（订单额与退款额分列）、支付宝「成交订单=已付款成功的订单金额汇总（**未扣除退款订单金额**）+ 发生支付、退款时会产生新的记录」、有赞「订单实付金额不剔除退款金额」、**老系统 easygo 直接 `@Column(updatable=false)` 从 ORM 层面禁止回写成交额**，退款另立 `StatFinRefundDetail` 两列，日账单 GMV=`Σ order_total_amount − 退款行`（`BillService:424`）。不采纳「基数直接取 `netCompletedCents` 流水净额」：无流水的路径会恒 0、逐单查 N+1，且事后答不出「这单当初成交多少」。
  - **实测**：负向轮把 `netCents` 换回旧公式 ⇒ **4 红**（摊薄 / 改单降价 / 改单涨价后再退 / 冻结例的净额断言），恢复后全绿；新建 `CommissionBaseCalcFrozenGrossTest`（5 例）。V384 在共享 dev 库 `BEGIN; … ROLLBACK;` 内真跑三场：① 原样（6 行订单 0 行有流水 ⇒ 全走回落，NOTICE 如实计数）② 插 5 条流水（含 REFUND 100 + **PENDING 态** REFUND_INITIATED 70）并把该单摊薄成 `total=250/refunded=100` ⇒ 回填 `paid=350`、净额 250（旧口径 150）③ 流水改成 999 ⇒ `ERROR: V384 回填被拦：1 行的冻结毛额 > (total + refunded)` 如期中止。
  - **F-10** 一行 `.in(...)` 补 `PREAUTH_CAPTURE`（同文件两个 switch 早就认它 ⇒ 代码追上文档），新建 [`PaymentOperationNetCompletedFilterTest`](../services/trade-service/src/test/java/com/aicabinet/trade/mapper/PaymentOperationNetCompletedFilterTest.java) 用 `ArgumentCaptor` 捕获**生产 wrapper** 断参数值集合（首跑恰好 1 红）；`OrderPaymentChargeInvariantTest` 删手抄 `net()`，改 `thenCallRealMethod()` 走生产 default 方法。**教训（对我自己）**：原提议的负向用例「stub `selectList` 返回冲抵行、断 `netCompletedCents>0`」**修前也会绿**——缺陷在 SQL 过滤层，聚合 switch 早已支持该类型；负向用例必须打在**缺陷所在的那一层**。
  - 文档回写：审计文档 [AUDIT_B1_EXTENDED_FUND §8](AUDIT_B1_EXTENDED_FUND_2026-10-16.md)（含 §7 修法的那条自我更正）、简报 §13 第 6/10-11 行状态改「已修」并把「共享库只读」更正为「写测试一律 `BEGIN…ROLLBACK`」。
- **F-5/F-8 修、F-6 判定暂不修（§12 第 2 项，用户「看竞品和老代码决定」）**：F-5 提交 `4bfc0de6`——日结通知的费用栏**改读钱包实扣流水**（新增只读 `MerchantWalletService:196 debitedCentsByRef`，按 `SETTLEMENT_FEE`+昨日 bizId 取负额行），`chargeDailyPeriodFees` 返回值改成「本次实扣」（幂等跳过时报 0，消除收入商户循环 + 全量循环各数一遍），扣费失败数进 `NotifyOutcome` 并写进运营可见的任务汇总文案；3 个新用例钉「展示 2.50 而非重算 1.00」「失败计 1 且 fee=0.00」「重复日不再扣钱但仍写台账行」。F-8 提交 `da72804a`——线长佣金冲回基数从**实时 `bps/fixed`** 改为**已入账佣金行**（`credited * gmvDelta / gmvBase`，`CommissionClawbackService:120-143`），加两道 no-claw 守卫（无原行/无基数 ⇒ 不冲、不造新资金路径）。F-6 判**暂不修**：全链路只有 [MerchantDailyIncomeNotifyScheduler.java:350](../services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantDailyIncomeNotifyScheduler.java#L350) 一处整数 floor（流量费自 V380 已是分/天，不再摊销），单柜误差 ≤(当月天数−1) 分 ⇒ ≤0.30 元/柜/月；「月末清算行」与 `rerunDailyFees` 的 exists-then-skip 冲突会造出**超收**风险，「逐日轮存余数」破坏「月额/天数」可读可重算 ⇒ 接受偏差，**对账以钱包流水实际发生额为准**。判定依据入新台账条 **CB-056**（回溯类金额一律以「已入账行」为基数、对外通知取账户实际发生额，禁止用当期配置重算历史；不采纳「给佣金冲回加渠道回退时限」）。
- **🔴3/🔴4 补审（§12 第 3 项）**：🔴3 押金月退 `runMonthlyRefunds` 由**零用例补到 6 个**回归用例（不重复退、余额守卫、台账守卫失败不注入钱包等），`DeviceRentalServiceTest` 28/28；🔴4 三任务七处**逐处亲验齐全** + 共享库实读 `enabled`（daily-income=t / payee-bill=t / deposit-monthly=f，与 V376 一致）+ 两道门禁 EXIT=0。**并勘误审计口径**：「`check-scheduled-task-seed` 只校种子齐、不校七处同步」是错的——分工明写 [XxlJobManagedTasks.java:44-45](../services/trade-service/src/main/java/com/aicabinet/trade/service/XxlJobManagedTasks.java#L44-L45)（①~④⑦ 归 `check-xxl-job-wiring`，⑤⑥ 归 `check-scheduled-task-seed`）；真实剩余风险是「key 一开始就四处皆漏时检不出」，由「`tryBegin` 调用点必须已登记」兜住（本轮实测 39 个调用点全登记、无豁免）。
- **押金安装费默认归 0（§12 第 4 项，V383）**：`install_fee_deduct_cents` 列默认改 0（含 `DO $install_fee_zero_default$` 失败即停断言）+ Java 常量 `DEFAULT_INSTALL_FEE_DEDUCT_CENTS=0L` + 4 处文案去「必扣」口径 + `settle_defaultZeroFee_returnsBalanceInFull` 钉「不登记 ⇒ 全额返还」；粒度按用户选择保持在**登记接口参数**（运营台不加位）。⚠️ 同处 `DeviceRentalService:165` 把 `chargeMode` 写死 `"ONLINE"` 的缺口用户未定，未动。
- **「平台分账分的是什么」实证答复（§12 第 7 项 → 简报 §14）**：分的是**每笔已支付订单毛额里平台那一刀佣金**——`platform = gross*bps/10000`（`RevenueSplitService:503`），出门**只有一个接收方**且 `amount=merchantCents`、`unfreeze_unsplit=true`（`WeChatProfitSharingService:155-166`，余款解冻归服务商）；员工/线长不参与渠道分账，走内部钱包。**老系统不做渠道级分账**：`RyTask.java:443-447`「机器分账账单」= 逐柜月账单（台账形态），出款用支付宝单笔转账到银行卡（`WithdrawalController.java:693,710-718` `DIRECT_TRANSFER`/`TRANS_BANKCARD_NO_PWD`）。⇒ 我方与老系统是**形态不同**（先分后结 vs 先结后转），恢复真收仍卡在 CB-032 那 6 问。
- **§12 状态回写**：拍板简报新增 §13（9 项逐条「已落/未落」，含 DC-27(b) 与 Track C-admin **本轮未动工**的诚实标注）+ §14；第 3 行的门禁说法已就地划删除线并给勘误。共享库本轮**只读**（用户「写入痕迹不要清」）。
- **运营台 `¥¥` 双印 7 处修复 + 门禁补盲区（`42e7a99e` + 产物重生成 `5a8016c9`）**：`yuan` 在三个视图里是 `yuanTextZero as yuan`（**自带 ¥**），而提现/退款**确认弹窗**文案在 `<script setup>` 内拼 `` `…¥${yuan(x)}…` `` ⇒ 渲染 `¥¥100.00`；`check:money-yuan-prefix` 原两条形态都要求 ¥ 落在 `{{ }}` 插值里，对脚本侧**结构性失明**（205 个 .vue 全绿却漏 7 处）。已加 `YEN_THEN_JS_TEMPLATE`/`YEN_PLUS_JS_CALL` + 6 条自检用例（含 3 条 B 类反例：该形态盘上共 14 处命中，除 7 处缺陷外另 7 处是 `¥${v.toFixed(2)}`/`¥${money(x)}`/`¥${Number(v)}`/`¥${Math.round(v)}` 属正确写法，必须继续放行）。教训 314：口径类门禁要**逐出入口形态**给用例，开新形态前先测盘上命中数。教训 315（F-10 根因）：**禁止在测试里手抄被测实现的口径**——复制版不会因为生产代码改坏而变红。
- 🔴 **待办 #25「净额基数复核」抓出两条新缺陷，均未动代码、等拍板**（⇒ 2026-10-17 已拍板并全部修完，见本节首条）（简报 §12 第 10/11 行，审计文档 §6/§7）：**F-9** `CabinetOrder.totalAmountCents` 有两套写入约定（按行部分退把 total **改写成剩余应付** `SettlementOrderSupport:157`；金额退只加 `refunded`），而 `CommissionBaseCalc:33-35` 恒算 `total − refunded` ⇒ 按行退过的单**退款被扣两遍**（`SettlementPartialRefundTest:142-149` 自证生产形态 `total=300/refunded=700` ⇒ 净额本该 300、实得 0）⇒ `LineCommissionJob:181-188` **少发线长佣金**，且 `countableCount` 照计单数造成「固定佣金付了、比例佣金归 0」矛盾行；零覆盖原因=没有用例把部分退接进 `netGmv`，而 `CommissionClawbackServiceTest:182-191` 直接 seed `total=8000/refunded=2000`（假设 total 仍是毛额）。**F-10** `PaymentOperationMapper:113-116` 的 `.in(...)` 不含 `PREAUTH_CAPTURE` ⇒ 同文件 `:120-146` 的两处 PREAUTH_CAPTURE 分支是**死代码**，「只冲抵未另记 CHARGE」的单在 `netCompletedCents` 里恒为 0 ⇒ `OrderPaymentService:418` 补扣按全额（冲抵部分扣第二遍）、`UnpaidOrderService:383` 的「净额已覆盖直接结单」走不到；测试全绿的原因很脏——`OrderPaymentChargeInvariantTest:166-179` **手抄**了一份自称同口径的净额逻辑且抄漏了 `.in(...)`。
- **6‰ 提现费保留为默认档**（用户确认「不归零、随时可改」）：`merchant.withdraw.fee_bps` 在 [feature-flags.json](../services/trade-service/src/main/resources/ops/feature-flags.json) 的注册表默认从 `0` 对齐成 `60`，此前 V381 只翻了 sys_config 种子、注册表仍写 0 ⇒ `check:feature-flags` R5 红「注册表与 seed 不一致」。运营侧改档/按商户覆盖/填 0 免收三条路都不受影响（[SystemConfigService.java:834](../services/trade-service/src/main/java/com/aicabinet/trade/service/SystemConfigService.java#L834) `upsertIfAbsent` 只在无值时写）。
- **锁柜阈值换键收尾**（V380 遗留）：注册表里 `merchant.overdue.lock_months` 换成 `merchant.overdue.lock_days`（默认 7，与 Java 种子一致）。旧键在代码里只剩一句「刷成【已废弃】描述」（[SystemConfigService.java:1046](../services/trade-service/src/main/java/com/aicabinet/trade/service/SystemConfigService.java#L1046)），既不 seed 也无判据读取 ⇒ 留在注册表就是「能关却关了个空气」的死开关，从清单里摘掉而不是给它 deprecated 豁免（豁免要求 seed 存在，R5 不认 deprecated）。
- **到账金额算例（提现 ¥1000，费率同 6‰、固定额 0、不封顶）**：老系统 ¥994.00；我们 `platform_rate_bps=0` 时同样 **¥994.00**；`rate=1000`（现行默认 10%）时每卖 ¥1000 只入账 ¥900 ⇒ 最多提 ¥900、手续费 ¥5.40、到手 **¥894.60**（平台合计 ¥105.40=¥100 分账+¥5.40 费）。差别始终在**入账基数**，不在提现扣法。
- ✅ **平台分账按 0 收（任务 #15，同日落地）**：用户拍板「不确定有没有这笔费用，先配置为 0、先不收」。原「必须实证的点」——[WeChatProfitSharingService.java:148](../services/trade-service/src/main/java/com/aicabinet/trade/payment/WeChatProfitSharingService.java#L148) 的 `amount` 取 `split.getMerchantCents()`，rate=0 ⇒ **amount==订单毛额**（比 CB-032 记的「90% ≫ 默认 30% 上限」更极端，提额真实性仍未与微信确认）——按「**0 抽成不发分账请求**」绕开，不再依赖未实证能力：见下方落地条目。
- 门禁：`check:feature-flags` 由红转绿（103 键全登记），`check:flyway-seed-separation`、`check-migration-safety`（V380/V381）绿。
- **F-2/F-4 收尾（任务 #9）**：运营台「商户费用台账」页已可达（路由 `merchant-fee-ledgers` + menu.ts:299 + sidebar 图标 + `v-hasPermi` 双码），F-4 手动重跑端点 `POST /api/v2/ops/admin/merchant-fee-ledgers/rerun-daily-fees?feeDate=YYYY-MM-DD` 补两道闸（**只允许不晚于昨天**、**回溯 ≤92 天**）。此前 `check:admin-endpoint-paths` 判它「死路由」——不是端点不存在，而是该门禁的后端路由表**只认生成的 `openapi.ts`**（CI 用刚构建的 jar 拉 `/v3/api-docs` 重生成后 `git diff` 钉住）；已用重建 jar 现抓 spec 重生成，diff 恰为该端点（paths 618 条）。
- **离线重生成 openapi 的安全姿势**（不动共享栈）：`mvn -pl services/trade-service -am package -DskipTests` → 临时 `postgres:16-alpine`（:15499）+ `redis:7-alpine`（:15599）两个一次性容器 → `java -jar … --server.port=18123` 带 `FEE_BILL_AUTO_GENERATE_ENABLED=false RECON_SCHEDULED_ENABLED=false DISPUTE_SLA_SCHEDULER=false RISK_CONTROL_ENABLED=false XXL_JOB_ENABLED=false` 起 → `curl /v3/api-docs` → `OPENAPI_FILE=… pnpm gen:api-types`。**不碰共享 Postgres（避免 V380/V381 被顺手施到 peer 的 dev 库），也不碰共享 Redis（避免空库实例把缓存写成空值）**。跑完删容器。
- **F-2/F-3/F-4 负向用例与守卫全部落地（任务 #10）**：[MerchantDailyIncomeNotifySchedulerTest](../services/trade-service/src/test/java/com/aicabinet/trade/service/MerchantDailyIncomeNotifySchedulerTest.java) 13 用例覆盖 7 条拍板判据（分/天不摊销、买断同收、0/NULL 不写行、扣款成功即 SETTLED、异常当日不留行、第 6 天只催不锁/满 7 天锁、结清只解「费用欠缴锁柜」前缀）；新增 [OpsMerchantFeeLedgerControllerRerunGuardTest](../services/trade-service/src/test/java/com/aicabinet/trade/api/OpsMerchantFeeLedgerControllerRerunGuardTest.java) 5 用例钉重跑闸（今天/未来/超 92 天/不可解析=400 且**不落到扣款**，昨天与第 92 天=放行）；`WalletRefIdempotencySqlIT` 补登记进 `check-docker-tests-guarded` 的 GUARDED（integration/failsafe，实测 5 用例真跑）。全量：trade-service 2017 单测 + IT、jiangyi-gateway 75+12 全绿；`node scripts/run-audit-gates.mjs` **49 门禁 0 失败**。
- **竞品台账勘误（任务 #11）**：CB-034 标题与 CB-041 结论里「欠费停机是行业主流」**删掉**——该两条检到的柜机主体从未公开「欠费即停机」条款，老系统更是扣费失败只 `continue`（`MachineListController.java:143,149` 手点停售、`EgoIotCardInfoServiceImpl.java:359` ISP 侧停机，均与平台费用无关）⇒「欠费自动锁柜」是我方新增设计。判据的真实参照另立新条 **CB-052**（阿里云/腾讯云/华为云三段式「催缴→停服保留期→补足自动复机」，纯时间判据、无金额档；公开规格跨 5/7/15 日，我方默认 7 天取**下沿且可配**）。`check-competitor-benchmark` 新增 3 个 `cross` 主体别名。
- **竞品台账补条 + 自查勘误（任务 #17）**：为三条此前**无台账条目**的已实现/半实现能力补 CB-053「渠道退款失败闭环」（触发点=V364 `refund-retry` 种子行缺引用）、CB-054「一线作业人员钱包/自动发佣」（V359 三域钱包）、CB-055「渠道账单/对账形态」；`check-competitor-benchmark` R7 要求 ≥3 可识别主体且 ≥2 `kind: peer`，媒体源（新浪/头条/baike/知道）一律 `id:null` 不计数 ⇒ 参照主体只用 **旧系统 easygo + 哆啦宝 + Nayax**，黑猫投诉链接属门禁禁用域只作正文叙述。新增别名 `哆啦宝`(peer)/`易宝支付`(official)。**两处我自己的审计结论被源码推翻并回写**：① CB-034「押金额度口径」与 CB-044「安装费扣回」的矛盾此前只在 CB-034 一侧记了往返、CB-044 没指针 ⇒ 双向补齐，并钉 V376 把 `deposit-monthly-refund` 默认改 `FALSE` 后**现行默认=人工退押**、「已退完未满 5 年倒扣 100」默认永不触发；② 审计简报 §6 关于 V347 那一行三处过头——V347 是**进销存人工导入**不是资金对账、`isReady()` 恒 false 讲的是**出款侧**（收入侧 `WeChatPlatformBillProvider.fetchDailyBill:41-58` 早已走 `/v3/bill/tradebill` 下载 CSV）、"老系统全库零对账代码"漏了注释态 FTP TODO（`LingNanTongServiceImpl.java:42`）与月结账期字段。另：DIFF 文档「新佣金基数违背老系统」判定**过时**——`LineCommissionJob:181-182` 已用 `CommissionBaseCalc.netGmv` 净额基数；分账侧/GMV 侧基数本轮未复核，列入待办而非缺陷。架构与资金语义两问（三域钱包要不要抽泛型账本原语、`staff_wallet_account.frozen_cents` 死列启用还是删）**不自行拍板**，进简报 §12 第 9 项。
- **V382 + 三道 0 抽成闸落地（任务 #15）**：`platform_rate_bps` 归 0 **不是只改列默认值**——真正写库的默认在 Java 实体，故同时翻 [Merchant.java:25](../services/trade-service/src/main/java/com/aicabinet/trade/domain/Merchant.java#L25)、[MerchantService.java:147](../services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantService.java#L147) 缺省、运营台新建/编辑表单 3 处（[MerchantSplitsView.vue](../clients/admin-vue/src/views/merchants/MerchantSplitsView.vue)，标签改「平台抽成（%，0=不抽成）」），`V382` 再把存量 2 家（实测 1000/1500）一并置 0；`DemoDataService` 演示商户与 `rebuild-demo-rbac-accounts.mjs` 是**显式 fixture**，保留原档位（无微信接收方 ⇒ 本来就走 LEDGER_ONLY，不发分账）。**历史 `order_revenue_split` 行不回改**（三列记的是当时口径、且已跟过资金动作）。0 的下游语义按「不发分账」实现，闸放在三处：`doRecordSplit`（`platform<=0` ⇒ LEDGER_ONLY，份额整笔入钱包）、`doResyncSplitForOrder`（重算后 0 ⇒ 未提交的 ACCRUED 翻 LEDGER_ONLY 并**按全额入账**，ACCRUED 期间钱包从未入账、补差额会少付商户 `oldMerchantCents`）、`WeChatProfitSharingService.prepareSubmit`（`platformCents<=0` ⇒ 终态不发 HTTP、状态不动——改状态会在这层把钱留在无人入账的中间态；此闸兜住运营手工「提交分账」与失败重试两个入口）。负向用例先行：`RevenueSplitServiceTest` +2、新建 `WeChatProfitSharingServiceZeroRateTest` 3 例（含 rate>0 对照组，钉 `receivers[0].amount==merchantCents` 与 out_order_no 先落库）。**实证**：共享 dev 库 `BEGIN;…ROLLBACK;` 跑 V382 文本 ⇒ 2 行归 0、列默认 0、注释到位、回滚后仍 1000/1500；一次性空库（临时 pg :15499 + redis :15599 + 新构建 jar）Flyway 从零跑 378 迁移到 v382 ⇒ 种子商户 `MCH-DEFAULT/MCH-OTHER` 落地即 0。跑完删容器，共享栈未动。

### 2026-10-16（三端金额单位审计：口径收口 + 双重 ¥ 清零 + 一处「分」输入框真缺陷）
- **口径钉死**：存储/接口一律分（字段带 `Cents`），展示一律走各端唯一函数，表单按元进、`yuanToCents` 出 → 写入 §4 约定表 + 新增 §6.1。三端逐页 + 后端 DTO 全量扫过，**未发现单位错传（该传分传了元）与双重换算**；残余风险是重复实现与不带单位的命名。
- 🔴 **真缺陷 1（会写错钱）**：[DevicePricingCard.vue](../clients/admin-vue/src/components/device/DevicePricingCard.vue) 覆盖价输入框直接绑 `overridePriceCents` + `:precision="0"` ⇒ 运营想填 ¥3.50 只能填 3.5，被截成 **3 分（¥0.03）**。改为按元输入、`@change` 走 `yuanToCents` 写回，`:min/:max` 同步换成元口径。
- 🔴 **真缺陷 2（渲染错，41 处）**：格式化函数分「自带 `¥`」(`fmtMoney` / `yuanText*`) 与「只出数字」(各页本地 `money()`) 两类，模板里再补字面 `¥` 就渲染成 `¥¥12.34`、负额 `¥-¥5.00`。商户端账单 10 + 结算 6、运营台财务/促销 25 全部去掉字面 `¥`；5 处调账/提现确认串（输入框 `:min="-1000000"` 允许负额）把 `¥${(cents/100).toFixed(2)}` 换成 `yuanTextZero`（负数正确出 `-¥50.00`）。
- **绕过格式化**：`centsToYuanDigits()`（[shared-uni/format.ts](../packages/shared-uni/src/format.ts)）与 `bpsText()`（[admin display.ts](../clients/admin-vue/src/utils/display.ts)）此前**不存在**，是各页自写 `/100).toFixed(2)`、`(bps/100).toFixed(1|2)` 的根因；补上后 device-detail/pricing 列头 (元) 数字位、5 个页面的费率百分比统一。运营台仍余 98 处内联换算（登记为 G8，非缺陷、增量清）。
- **两处「元」例外显式命名**：会员域 `DECIMAL(12,2)` 元 ⇒ MemberLevelsView 的 `yuan()` 改名 `yuanOfYuan()`（入参已是元，再 ÷100 就是 1/100 价格）；`platform.fund.*._yuan` 键名自带单位。DTO 侧 [MerchantWalletLedgerDto](../../services/common/common-core/src/main/java/com/aicabinet/common/dto/MerchantWalletLedgerDto.java) 用 javadoc 钉 `balanceAfter/frozenAfter` 单位（改名会破 openapi 契约，故只补注释）。
- **同名不同物的两个押金**入 §6.1：`device_info.deposit_cents`=开门押金/预授权冻结（>0 覆盖 `checkout.preauth_cents` 默认 2000 分），`device_rental_deposit.amount_cents`=CB-034 租赁押金本金。
- **勘误**：上一轮「我们系统提现双重收费」的表述**不成立**——两端扣法相同（60bps=6‰，都是从打款额里出），差异在入账基数：老系统入账毛额、我们 `merchant_cents` 已是分账后净额（[RevenueSplitService:114](../../services/trade-service/src/main/java/com/aicabinet/trade/service/RevenueSplitService.java#L114)）。¥10000 一笔平台收入 ¥60 vs ¥1054 是商业模式差，不是重复扣费。
- **新门禁** `check:money-yuan-prefix`（已入 `check:audit-gates`，接线门禁同步通过）：①「字面 ¥ + 自带 ¥ 的调用」按**每文件绑定来源**判定，②禁 `.replace(/^¥/,'')`（锚 `^` 对 `-¥5.00` 不匹配 ⇒ 只剥正数）。第一版全局字符串匹配误报 27 处，故改为解析 import/本地函数体；判定**按整文件扫**而非逐行（prettier 会把 `¥{{` 与调用折成两行，逐行版实测漏掉 PromotionsView 剩余预算那一处）。自检覆盖 A/B 类绑定、折行命中与行号定位。
- 验证：三端 `vue-tsc` 全绿，vitest admin 99 / consumer 138 / merchant 176 全绿；`check-audit-gates-wiring`、`check-mp-template-comments`、`check-line-endings`、`sync-shared-uni-components --check`、`style-full-audit --check`、`check-admin-table-align`、`check-admin-page-size`、`check-uat-selectors` 绿。**零迁移、零数据变更**。

### 2026-10-15（老系统 easygo 全域规则卡对照 + 三处旧结论勘误 + dev 全面审计）
- **老系统结构钉死**：ego-automat=柜机/运营商域；**mis-server（RuoYi）内含补货员佣金与点位账单的 quartz 跑批**；**dinngdang-wx 消费者小程序的真实后端是 dvm**（wapp.easygovm.com），不在 ego-automat。两代交易链：dvm 弹簧柜先付后货（NOT_FALL 自动退款、用券单禁自动退）；m8/m9 开门柜**先取后付**（识别由设备端做，服务端直扣余额**无下限校验=可扣负挂账**；开门门槛=实名+余额≥5 元）。
- 🔴 **勘误 E1**：「补货员佣金计算在仓库外（黑盒）」不实——就在 `mis-quartz RyTask.generateComminssionMachineDailyBill`(:151-187 亲验)：按 `ego_opt_commission_machine_link` 配置算 type0=销售额(分)/10000×ratio、type1=件数×ratio，**T+10 日结**入 CommissionAccount+支付宝代发。「对齐老系统」对新系统 staff 佣金只对**公式**成立，归属人（老=配置「佣金所属人」，新=实际补货任务人）与节奏（T+10 vs 日结）均不同。
- 🔴 **勘误 E2**：`add_goods_user_id` 注释=「佣金所属人」（按机器配置，与谁补货无关），实际补货留痕在 `ego_opt_stock_operator_record`——10-14 拍板依据需修正。
- 🔴 **勘误 E3**：「点位电费/客情/返点=纯台账无自动扣款」不全——`RyTask.positionMachineBillMonthly` 月出账且**支付宝自动打款**（电费按量硬编码 240 元/月、梯级返点硬编码 ≤2000→10%/>2000→15%，EgoOptPositionBillMachineMonthlyServiceImpl:247-284 亲验）。新系统「线下标记 payout」是用户拍板口径非老复刻；老点位有 **5 组收款人**（线长/电费/合作1/合作2/租金），新 machine_position_config 单组=表达缺口。
- 其他高价值事实：老系统**无任何欠费锁售/催缴代码**（余额可负仅禁提现，停售靠物联卡+人工）——新 FEE_OVERDUE_LOCK 是纯新能力，判据必须费用口径（关联 P0-3 押金误锁）；老线长基数=**已扣退款净额**（26 号月结三池+人工审核闸）——佐证 P0-2 冲正应按 `total−refunded` 净额；老**不存在部分金额退款**（按件整退），PARTIAL_REFUNDED 是新能力、下游基数三处未跟上；老押金档位制含**撤机扣 100 安装费/满 5 年反发 200/500 档不退**——新 V360 自由配置未覆盖撤机场景；老无渠道对账代码（新 ReconciliationService=超越）。
- **同日 dev 全面审计**（6 路+主审回读实证）：P0×3——①LineRoutes/MachinePayeeBills 未登记 menu.ts+meta 无 perm→fail-closed 守卫 403 **全员不可达**；②佣金冲正基数=状态过滤+全额→部分退款整单冲回（多扣钱）+发佣前退款永久漏发；③押金 debitIfAbsentAllowNegative 负余额被 checkOverdueAndRemind 当费用欠缴（pendingFeeTotal 零调用）→误锁全部柜。P1 要点：台账 SETTLED 与钱包不联动（线下结清不解锁）、负余额 doDebit 412 阻断消费者退款冲正、零销售日 early-return 跳过全批扣费催缴、staff 钱包 selectById 无行锁（线长侧有 findByIdForUpdate）、换绑线长同 device-day 双付、实时购物车透传推送价（第五价点非同源）、争议结果零触达（模板已种无调用）、admin 打款按钮 ops:replenishment:* vs 后端 ops:line-manager:* 错配+catch 空吞。将邑争议 reason 不匹配 likeRight("识别超时")→自动免单永不命中。
- 详见 `docs/OLD_EASYGO_VS_AICABINET_DIFF_2026-10-15.md`（含 6 节差异卡+修复启示）；竞品台账「老系统无自动打款/佣金黑盒」两处表述待按 E1/E3 修订。

### 2026-10-14（WorkBuddy 会话：定价功能 + 端点误删修复 + 死路由清零）
- 🔴 **设备详情「资源不存在」根因修复**：b96a74d4 误删 4 个设备级端点（`GET /devices/{id}/detail`、`GET/PUT /slots`、`POST /slots/stocktake`），原样挂回 `OpsReplenishmentController`（service 方法一直存活，属误伤非有意下线）。
- **新功能：单柜差异化定价**（V350，台账 CB-037）：`device_sku_price.enabled` 覆盖价启停开关 + `sku_catalog.min_price_cents` 显式最低限价 + `ops:pricing:view/edit` 权限 + admin 设备详情「单柜定价」tab（行内编辑：改覆盖价/启停/恢复基准，不走弹窗）。限价规则抽 `SkuPriceBounds` 与商户端同源；**用户否决「不低于进货成本」硬线**（时段/清仓折扣本可打穿成本，不自洽）——min 留空=不限仅>0，低于成本前端提醒不阻断；admin 改全局基准价首次补限价校验。
- 🔴 **11 条死路由清零**（台账 CB-038）：6 条补货编排/效期端点**恢复**（`/replenishment/plan`、`suggest/create-task`、`routes/{id}/cancel-empty`、`/expiry/alerts`×3）——竞品实证（丰e/友宝「系统生成任务下发」是头部标配）且 service 全存活；5 条 admin **执行链**（代签到/完成上架/明细/证据）**不恢复**——执行面归商户端小程序（`/merchant/replenishment/tasks/*`），死 UI 全删（打卡/完成按钮、明细抽屉、taskLines composable、「仅待分配」筛选），admin 保留补货开门（活端点）与路线收口。
- **「待分配货道」能力以列表聚合重建**：`ReplenishmentFulfillmentTaskDto.unassignedRestockLines`（一次 SQL 聚合 applied=false & line_type=RESTOCK & slot_id 空的行数），徽标/「仅待分配」筛选恢复，替代旧的逐任务 N 次请求。
- **新门禁 `check-admin-endpoint-paths`**：前端端点引用 ⊆ openapi paths（EXEMPT 已清零）——任何死引用直接 FAIL，堵死本类回归。
- ⚠️ **澄清钉死**：本项目**没有柜机屏硬件**（将邑柜无屏），广告/推广展示在**小程序**内；`/screen-content` 链路无真实消费方（仅 edge/device-simulator 模拟器联调），DeviceController 已标注冻结——勿再做屏显类产品假设。
- 商品表单（SkuListView）补「最低/最高限价」字段（min 留空=不限、max 留空=售价×2）。
- 相关文件：V350 迁移、`SkuPriceBounds`/`OpsDevicePricingService`/`AdminDevicePricingController` 新类、`OpsReplenishmentController` 两批恢复、前端 `DevicePricingCard.vue`/`useDevicePricing.ts`。
