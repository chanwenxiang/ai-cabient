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

**AI 开门柜**：消费者扫码开门取货 → 视觉/端侧识别 → 自动结算扣款；运营后台管设备/SKU/争议/财务；商户端管补货/定价/钱包。独立于旧仓 `ego-automat`（只读参考）。

---

## 2. 仓库现状速览（2026-09-24 校准）

| 维度 | 当前值 | 备注 |
|------|--------|------|
| Flyway 迁移 | **321** 个脚本，最新 **V325** | 合入前 `ls V*` 查重号；门禁拦同号；勿再写已占用版本号 |
| trade Controllers | **85** | `*Controller.java` |
| trade 单测 | **333** `*Test.java` | 含大量并发测；全量 `mvn -o -pl services/trade-service -am test` |
| admin-vue 业务视图 | **77** `.vue` | `src/views` |
| consumer-mp / merchant-mp | 独立 uni-app | 分包 + `preloadRule`；H5 `:3002` / `:3001` |
| shared packages | types / api / dict / rbac / uni | 改 API 后 `pnpm gen:api-types` |
| 踩坑总册条目 | **≥223** | `docs/engineering/lessons-learned.md`（末条编号 L-223） |
| 竞品对照台账 | **14 条 / 71 个 URL** | `docs/COMPETITOR_BENCHMARK.md`；门禁 `check:competitor-benchmark` |
| 审计门禁 | `pnpm check:audit-gates` | 新建脚本须进 `ci.yml` |

> **最后校准**：2026-10-07（本节数字为**当日实测**，非估算）。
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
| 验收口径 | **mp-weixin 是双小程序唯一验收权威**（2026-09-26 定）；2026-10-05 收紧：**小程序 H5 不作验收面、不再为它投入修复**（dev:h5 起不来/双 vue 错配均不修）。注意：CI `e2e-h5` job 混装——consumer/merchant H5 两步可按冒烟看待，但同 job 内 three-end business / admin console / role-regression UAT 跑在**运营后台**，不受此口径影响、仍必须绿 |
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
| [SATELLITE_WAREHOUSE.md](SATELLITE_WAREHOUSE.md) | 分仓四步（人绑仓 → 采购进分仓 → 仓到柜 → 月结）；不要抄 easygo 批发、不要把要货当天入口 |

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
