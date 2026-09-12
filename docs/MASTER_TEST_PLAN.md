# ai-cabinet 最终版全量测试文档（MASTER TEST PLAN）

> **版本**：2.1（FINAL · 代码对照版） · 日期：2026-09-12
> **v2.1 变更**：①修正后端测试资产计数（246 全库/87 并发/198 service）；②§7 全景重写（改动→必跑映射、E2E/UAT 资产补全、CI 对照表、Playwright 纪律、可观测冒烟）；③新增 §3.6 设备/视觉/边缘专项；④§0.3 PASS 保鲜规则；⑤§2.4 菜单轮转规则；⑥§2.7 UI/UX PR 最短验收（贴合近期 token/a11y/表格/文案类合入）。数字以 v2.1 实测为准。
> **定位**：本文是 ai-cabinet 三端 + 后端 + 边缘的**唯一总测试真源**，覆盖业务、UI、资金、权限、数据一致性、安全、性能、兼容性、回归自动化与上线门槛。每个 UI 用例均标注**代码真源路径**，页面数量以当前代码统计为准（2026-09-12 实测）。
> 旧测试文档（附录 A）自本版起降级为分册/历史，冲突时**以本文为准**。
>
> **阶段前提（重要）**：项目处于**开发/联调阶段**——未接入真实柜机硬件、未接入真实微信/支付宝商户号、识别为端侧提供方 + 云端 mock。本文用例默认运行 `pay:mock · door:sim · vision:mock`；凡标 🔒 的项**只能在真实环境终验**，mock 通过 ≠ 真实通过。

---

## 0. 使用方式

### 0.1 三级验收（每个写操作必过）

| 级别 | 名称 | 必须看到 | 不算通过 |
|------|------|----------|----------|
| **L1** | 可达 | 菜单/路由进得去；按钮可见可点；中文无乱码；loading/防双击 | 仅截图有按钮 |
| **L2** | 接口 | 返回 `code=0`（或约定业务码）；列表/详情刷新 | Toast 成功但接口 4xx/5xx |
| **L3** | 业务挂钩 | **下游真源变化**：DB 账本/对端页面/流水/状态机一致；幂等重放不双计 | 配置保存了但主链路不消费 |

宣称 PASS 至少 L3（只读查询可为 L2）。**资金/库存/开门类禁止跳过 L3**。

### 0.2 每用例固定检查项

1. 正常路径 → L3
2. 校验边界（空/超长/负数/超限额/非法状态）→ 中文报错、无脏数据
3. 幂等/连点 → 不双开门、不双扣、不双入账
4. 权限（换无权账号）→ 按钮隐藏或 403；直链落 `/forbidden`
5. 异常（断网/超时/下游关闭）→ 可读失败态；资金类冻结/回滚符合状态机

### 0.3 状态、证据与 PASS 保鲜

状态：`PASS / FAIL / BLOCK / SKIP / N/A`。资金、开门、争议类必须记**证据 ID**（`sessionId / orderId / splitId / withdrawId / ticketId`）+ 截图（`docs/uat-screenshots/YYYY-MM-DD/`）。无证据 ID 的资金项不得标 PASS。

**PASS 保鲜（防无限继承）**：
1. 每条 PASS 记录必须带日期 + 环境标签（§0.4）。**超过 30 天的 PASS 自动降级为「待复测」**；资金/开门/争议类 14 天即降级。
2. 该用例所属域（§7.0 改动→必跑映射）**有任何合入**（代码/配置/迁移脚本），其历史 PASS 同样降级为「待复测」，至少复跑 ⚡ 级用例。
3. 「待复测」不计入轮次 DoD 的通过数；附录 B 的 P0 基线日期即按此规则滚动失效。

### 0.4 环境标签（结果必须带）

`env:docker-full | env:local` × `pay:mock | pay:live` × `vision:mock | vision:端侧` × `door:sim | door:mqtt-real` × `client:h5 | client:mp-weixin`

---

## 1. 环境与账号

### 1.1 启动与端口（真源 `STARTUP_REFERENCE.md`）

| 模式 | 命令 | trade 端口 |
|------|------|-----------|
| IDEA 本地 + Docker 基础设施（推荐） | `docker compose -p ai-cabinet -f infra/docker-compose.yml up -d`，再 IDEA 起 trade/device/vision/simulator | 8080 |
| 全栈 Docker | `.\docker-up.ps1` | 18080 |

必启：Postgres **15433** · Redis **6379**（进 health）· EMQX **11883** · MinIO **9000/19000** · trade **8080**（`/actuator/health` 须 UP，依赖 Redis+vision）· device **8081** · vision **8082**（`/health` 含 `recognizer_available`）· 模拟器参数 `CAB-001`。
三端入口：consumer H5 `:3002` · merchant H5 `:3001` · admin 走 Gateway `http://localhost/admin/index.html`（或直连 `:8080/admin/index.html`）。
已知坑：trade health DOWN 先查 Redis 6379 与 vision 8082；勿 Docker 全栈与 IDEA trade 双跑。

### 1.2 预检（每轮开始前）

```powershell
.\scripts\phase-f-gray-launch.ps1 -CheckOnly   # 期望 17/17
.\scripts\cleanup-test-data.ps1                # 轮次间清争议/异常残留
```

### 1.3 演示账号（密码均 `123456`，真源 `DEMO_ACCOUNTS.md`）

| 端 | 账号 |
|----|------|
| 运营后台 | `13900000001` 超管 · `13900000002` 财务 · `13900000003` 运营 · `13900000004` 平台补货员 · `13900000005` 只读 |
| 商户端 | `13800138001` 管理员(MCH-DEFAULT) · `38002` 店员只读 · `38003` 他商户(MCH-OTHER) · `38004` 财务 · `38006` 店长 · `38007` 补货员 |
| 消费者端 | `13800138000`（微信 mock 登录） |

---

## 2. UI 测试（三端）

> UI 债务 2026-09-12 五轮审计收口至 P3 并关闭（真源 `ui-audit-r3-verify-2026-09-12.md`）。本章 = 防回归基线 + 代码对照手工用例 + 原生专项。

### 2.1 页面清单基线（代码实测 2026-09-12，防页面漂移）

> **分册新鲜度声明（2026-09-12 实测）**：`BUSINESS_FULL_TEST_MATRIX.md` v1.2 的**页面路径引用 37/37 全部有效**，按钮级明细可继续使用；但其「消费者 23 页」计数过时（现 24，缺 `pages/balance/balance` 用例，见 §2.4 UI-C04 补测）。`PERFORMANCE_TESTING.md` 的 JMeter 脚本**仅为文档内嵌 XML 示例，仓库无现成 .jmx 文件**——跑 PERF-1 前须先从该文档导出落盘。`SECURITY_BEST_PRACTICES_REPORT.md`（08-25）不含 CI 已新增的 OSV/secret scan（已在本文 §5 SEC-R8/R9 收录）。

| 端 | 真源文件 | 实测数量 |
|----|----------|----------|
| 运营后台 | `clients/admin-vue/src/router/index.ts` | 路由 **76** 条（业务 66 + 登录/打印/forbidden/动态/兜底等）；`views/` 下 **70** 个 .vue；`menu.ts` **66** 个 path 条目 |
| 商户端 | `clients/merchant-mp/src/pages.json` | **22** 页；TabBar 4：工作台/柜机/待办/我的；`merchant-nav.ts` 13 个功能 key（replenishment/devices/alerts/messages/pricing/settlements/wallet/splits/line-wallet/orders/disputes/business/team） |
| 消费者端 | `clients/consumer-mp/src/pages.json` | **24** 页（较旧矩阵 +1：`pages/balance/balance` 余额明细）；TabBar 3：首页/订单/我的 |

> 每轮回归先重跑上述统计，数量变化必须先更新真源文档再测。**注意**：旧 `BUSINESS_FULL_TEST_MATRIX.md` 写消费者 23 页已过时。

### 2.2 设计系统基线（新代码必须遵守，违者打回）

| 约定 | 规则 | 代码真源 |
|------|------|----------|
| 品牌主色 | 三端统一 `--brand: #0f766e`、`--brand-deep: #134e4a`；禁裸业务 hex（`var(..., #fallback)`、`--chart-*`、微信/支付宝品牌色豁免） | `packages/shared-uni/theme.css:26-32` |
| 深底白字 | **五档** `--on-deep-opacity-50/78/88/92/95`，用法 `rgba(255,255,255,var(--on-deep-opacity-78))`；border/shadow/渐变端点可裸写 | `theme.css:84-88`（含豁免注释 :81） |
| z-index | 七档 `--z-sticky:10 / map-control:500 / dropdown:1000 / fixed-tip:4000 / context-menu:5000 / fullscreen:9999 / skip-link:10000`；禁裸写 ≥100 | admin `main.css:72-78` |
| 弹窗宽度 | `--admin-dialog-width:480 / -wide:880 / -max:720 / -max-wide:960`，view 内禁散写 width | admin `main.css:156-159` |
| 卡片内边距 | `--admin-card-pad-y:14px / pad-x:16px` | admin `main.css:162-163` |
| 表单 label | `--admin-form-label-width: auto`，禁固定 px | admin `main.css:165` |
| 表格列语义 | 每列挂 `class-name`：`col-text`(左)/`col-money`(右，`label-class-name` 双挂)/`col-status`(居中)/`col-action`；文本列禁 `align="center"` | admin `main.css:591-602` |
| 组件复用 | NavBar/AppButton/EmptyState/ErrorState 经 easycom 复用，禁端内复刻 | consumer/merchant `pages.json` easycom 段 |
| 可访问性 | 可点元素 `role="button"`；图标 `aria-label`/`aria-hidden`；状态=颜色+文字双通道（`app-status`+dot） | `scripts/check-mp-a11y-coverage.mjs` |
| 玻璃态 | 三端登录卡禁 `backdrop-filter: blur`（全库现存均为显式 `none`） | R3-A01 收口记录 |

### 2.3 静态门禁与 CI 对照（基线 = 2026-09-12 实跑输出）

> **纪律：本地门禁每轮必跑，不能假设 CI 已扛。** CI（`.github/workflows/ci.yml`）实测覆盖见下表右列；未覆盖项必须由本地 §7.1 兜住。

| 脚本（`pnpm <name>`） | 检查 | 当前基线 | CI 覆盖 |
|------|------|----------|---------|
| `check:mp-a11y` | 两端小程序 role/aria 覆盖 | clickables **392/392 (100%)** · icons **37/37 (100%)** · `MIN_ROLE_PCT=95` | ❌ **CI 未跑，本地必跑** |
| `check:admin-table-align` | admin 列语义对齐 | col-text 冲突 **0** · 裸 align=center 债 **0**（历史 632）· 金额 label 未挂 **0**（col-money 137 处/26 view） | ❌ **CI 未跑，本地必跑** |
| `check:admin-bundle` | admin 包体积预算 | ui-vendor 1046.9KB(≤1200) · index 72.8KB(≤120) · leaflet 179.7KB(≤220) · 最大路由 WarehouseView 117KB(≤150) · 总 JS 2537.3KB(≤3200) 全 OK | ✅ ci.yml L130 |
| `check`（聚合） | **注意：仅 = lint + format + shared 包构建/测试 + nav-perms**（不含上面两条 UI 门禁） | 绿 | ✅ 等价步骤散布（L251-249 lint/format + RBAC/nav-perms） |
| `check:nav-perms` / `check:merchant-nav-guard` | 菜单权限码漂移 / 商户导航守卫 | 绿 | ✅ nav-perms 在 mini-programs job；merchant-nav-guard L231 |
| `check:migration-safety` / `check:openapi-types` | 迁移安全 / API 类型漂移 | 绿 | ✅ L96 / L170+249 |
| `smoke:admin-a11y` / `smoke:admin-list-race` / `smoke:realms` | admin a11y 冒烟（Playwright）/ 列表竞态 / 域冒烟 | 绿 | ✅ 仅 admin-a11y（L237）；后两者本地跑 |
| `test:mp` | 两端小程序 CI 测试（vitest） | 绿 | ❌ **CI 只跑 type-check + H5 build（L257-267），≠ test:mp；本地必跑** |

> 修复建议（工程项，不挡测试）：给 ci.yml 的 mini-programs job 补 `pnpm check:mp-a11y` 与 `pnpm check:admin-table-align` 两步（纯 node 脚本，零依赖成本），使 CI 与 §7.1 对齐。落地前，**这两条门的执行责任在每轮测试人**。

### 2.4 UI 手工用例（代码对照版）

**通用六维**（每页必查）：业务逻辑 / 按钮交互（loading·防双击）/ 页面状态（加载·空·错·无权限·token 失效）/ 中文编码（无 mojibake，金额 `¥x.xx`）/ 错误提示（401/403/409/网络失败中文）/ 布局体验（无遮挡，1366×768 可接受）。

**Admin 全菜单轮转（防页面漂移）**：下表 A 组仅是每轮抽测 10 页；另执行——**每轮快速轮对 admin 菜单做 L1 冒烟 N=10 页轮转**（按 `menu.ts` 顺序滚动，确保两轮内覆盖全 66 项）；**月度做一次 66 项全量 L1**（可达/无乱码/无白屏/无控制台报错）。页面数量基线以 §2.1 为准，菜单项与路由数漂移时先更新本文再测。

**A. 运营后台（抽测 10）**

| ID | 页面 | 必查点 | 真源 |
|----|------|--------|------|
| UI-A01 | 登录 | 正确登录 redirect；错误密码中文；HttpOnly cookie | `views/LoginView.vue` |
| UI-A02 | 设备管理→详情 | 二维码卡 `qr-body min-height:240px` 空态/骨架；统计 tile 为 div role=group；IMEI 未绑定=el-tag info | `views/devices/DeviceDetailView.vue` |
| UI-A03 | 任意列表页 | 列语义：文本左/金额右/状态与操作居中；分页不串页 | `main.css:591-602` |
| UI-A04 | 任意表单弹窗 | label 不折行（auto）；宽度走 dialog token；必填校验中文 | `main.css:156-165` |
| UI-A05 | 争议/异常中心 | 危险操作二次确认；状态徽章色彩语义（col-status） | `views/disputes/DisputeListView.vue` |
| UI-A06 | 只读账号视角 | viewer 无写按钮；直链 `/forbidden` | `views/system/*` + 路由守卫 |
| UI-A07 | 深色主题 | `data-theme='dark'` 无白底黑字残留 | `main.css` dark 段 |
| UI-A08 | 空态 | EmptyState 组件，无 `∅`、无死转圈 | `packages/shared-uni/src/components/empty-state.vue` |
| UI-A09 | 大屏/图表 | tooltip 无 blur（v2.1 复核：ChartBox 已无 `backdrop-filter`）；色板 `--chart-*`；z-index 走 token | `components/ChartBox.vue` |
| UI-A10 | 导出 | CSV 中文可读；无权限不可导出 | `views/reports/*` |

**B. 消费者端（抽测 9）**

| ID | 页面 | 必查点 | 真源 |
|----|------|--------|------|
| UI-C01 | 首页开门 | 授权弹窗两按钮等宽（`.landing-sheet-btn flex:1`）；遮罩 `rgba(4,31,26,0.42)`；扫码按钮层级 | `pages/index/index.vue` |
| UI-C02 | 帮助中心 | 无双标题（hero 已删）；圆形操作图标；FAQ chevron 旋转 | `pages/help/help.vue` |
| UI-C03 | 订单/详情/账单结果 | 状态 chip 双通道；金额 `¥`；视频/争议入口 | `pages/orders/orders.vue`、`pages/result/result.vue` |
| UI-C04 | 我的 + **余额明细（新页）** | 余额流水空态/加载态；免密入口文案；深底头卡文字走 `--on-deep-opacity-*` | `pages/mine/mine.vue`、`pages/balance/balance.vue` |
| UI-C05 | 充值 | 档位选择；**生产构建无「123456/模拟充值」联调文案**（dev 才有） | `pages/recharge/recharge.vue` |
| UI-C06 | 争议/账单审核 | 免单/退款入口；提交后状态回显 | `pages/dispute/detail.vue` |
| UI-C07 | 会员/积分兑换 | 兑换确认框；倍率说明 | `pages/member/index`、`pages/points/redeem` |
| UI-C08 | 深底页（landing/mine 头卡） | 白色透明度走五档 token；border 豁免类可裸写 | `pages/index/index.vue:3019,3079`（豁免示例） |
| UI-C09 | 视频/附近柜 | 深底沉浸（黑）；定位授权降级文案 | `pages/video/video.vue`、`pages/nearby/nearby.vue` |

**C. 商户端（抽测 8）**

| ID | 页面 | 必查点 | 真源 |
|----|------|--------|------|
| UI-M01 | 工作台 | KPI 卡；待办/告警空态互斥；未绑定商户文案 | `pages/home/home.vue` |
| UI-M02 | 补货任务 | 徽章四变体：pending 橙 / in_progress 绿 / completed 绿soft / **cancelled 灰**；默认样式归属 PENDING（有注释） | `pages/replenishment/replenishment.vue:2570-2594` |
| UI-M03 | 柜机/详情 | 温度保存反馈；`app-status` 双通道 | `pages/devices/devices.vue`、`pages/device-detail/device-detail.vue` |
| UI-M04 | 结算对账 | 渐变卡文字走 token（v2.1 复核：L487/L496 已用 `--on-deep-opacity-78`）；导出对账单 | `pages/settlements/settlements.vue` |
| UI-M05 | 钱包/提现 | 余额=流水合计；申请后冻结↑；低于最低额中文拦截 | `pages/wallet/wallet.vue` |
| UI-M06 | 分账明细 | 与运营侧 split 一致；失败态有原因 | `pages/splits/splits.vue` |
| UI-M07 | 团队 | 邀请/停用二次确认；只读店员 403 | `pages/team/team.vue` |
| UI-M08 | 登录 | 无玻璃态（`backdrop-filter: none` L374/418）；错误中文 | `pages/login/login.vue` |

### 2.5 小程序原生专项（🔒 `client:mp-weixin` 真机；H5 PASS 不替代）

| ID | 项 | 验法 |
|----|-----|------|
| N-01 🔒 | 真机扫码开门 | 相机组件扫柜码，与手动输号等价 |
| N-02 🔒 | JSAPI/免密支付 | 真机 + 对应 mock/live 渠道 |
| N-03 | 胶囊与自定义顶栏 | 真机对照 `packages/shared-uni/src/status-bar.ts` 遮挡 |
| N-04 | 分包/包体积 | `dist/dev/mp-weixin` 为本次构建产物；主包不超限 |
| N-05 🔒 | 下拉刷新/分享/定位 | 附近柜定位真机授权 |
| N-06 | 安全区 | iPhone 底部不遮 TabBar（borderStyle=black 已核对） |

### 2.6 兼容性

Chrome/Edge 近两个大版本（admin+H5）· 1366×768 与 1920×1080 · 微信基础库近期版本 · iOS ≥2 款 + Android ≥2 款真机 · Offline/Slow 3G 无假成功。

### 2.7 UI/UX PR 最短验收（只改前端可见面时必过）

> **适用**：token / 裸色迁移 / a11y / 表格列语义 / 文案去联调腔 / 深色对比 / blur·玻璃态 / 空态加载 / 布局对齐 / 列表竞态等——近期 `fix(ui)|fix(front)|fix(admin)|style` 类合入。  
> **不适用单独替代**：资金/开门/争议写路径仍须走 §7.0 对应行 + L3；本节只保证「改了 UI 不会静默回退设计系统与体验基线」。  
> **宣称 PASS**：遵守 §7.3（Playwright 或 IDE Browser 截图+DOM；禁止口头通过）。H5 PASS ≠ `mp-weixin`。

**最短命令（约 5～10 分钟，合入前本地）**：

```bash
pnpm check:admin-table-align                 # 表格列语义（CI 未跑）
pnpm check:mp-a11y                           # 小程序 role/aria（CI 未跑）
pnpm check:admin-bundle                      # 触及 admin 依赖/分包时必跑；纯文案可 SKIP 并注明
pnpm smoke:admin-a11y                        # admin 壳层 a11y 冒烟（Playwright）
# 触及 admin 分页/双列表/仓库等高流量列表时加：
pnpm smoke:admin-list-race
# 触及小程序页面逻辑/组件时加（CI ≠ 本命令）：
pnpm test:mp
```

**按改动面加验（勾选即做）**：

| 触及 | 必做 | 期望 |
|------|------|------|
| 设计 token / 裸色 / 深底白字 / z-index / blur | 对照 §2.2；改动页开真实 URL 看计算样式 | 无新增裸业务 hex；深底字走 `--on-deep-opacity-*`；登录卡无 `backdrop-filter: blur`（非豁免层） |
| admin 表格 / 金额列 | `check:admin-table-align` + 打开 ≥1 改动列表页 | 文本左 / 金额右（`col-money`+`label-class-name`）/ 状态与操作居中；无裸 `align="center"` 文本列 |
| a11y / 图标 / 可点区域 | `check:mp-a11y` 和/或 `smoke:admin-a11y` | role/aria 不低于 §2.3 基线；状态=颜色+文字双通道 |
| 文案 / 空态 / 错误态 | 改动页：加载中 → 空 → 错（断网或关下游） | 中文无 mojibake；无「模拟/123456」进生产构建；EmptyState 无死转圈 |
| 布局 / 弹窗 / 表单 | 1366×768 + 一档大屏；开相关 dialog | 无遮挡裁切；label 不折行；宽度走 dialog token |
| admin 列表翻页 / 多请求并行 | `smoke:admin-list-race` + 手工连点翻页 | 无旧页数据盖新页；无静默空表 |
| 消费者/商户自定义顶栏、Tab、安全区 | §2.5 N-03/N-06（至少开发者工具；发版前真机） | 不挡胶囊；底部不遮 TabBar |
| 深色主题（admin） | `data-theme='dark'` 扫改动页 | 无白底黑字 / 低对比残留 |

**UX 轻量五问**（每条改动页口头过一遍即可，记 FAIL 须截图）：

1. 主操作是否一眼可辨、危险操作是否二次确认？  
2. 加载是否有反馈、完成后结果是否可读（含金额 `¥x.xx`）？  
3. 空/错态是否告诉用户下一步，而非空白或英文堆栈？  
4. 触控/点击热区是否过小或与相邻控件粘连（小程序尤其）？  
5. 信息层级是否被装饰抢戏（多余徽章/多主色按钮并存）？

**证据**：改动页截图（或 Playwright report）落 `docs/uat-screenshots/YYYY-MM-DD/ui-pr-<短题>/` 或 `clients/*/output/playwright/`；PR/轮次记录写清跑过的命令与 SKIP 理由。

**与全量轮关系**：完整回归仍走 §2.4 抽测 27 条 + 菜单轮转；**仅 UI PR** 用本节最短集即可合入，但同一迭代内累计 UI 改动 ≥3 个 PR 时，须加跑 §2.4 对应端抽测各 ≥3 条。

---

## 3. 业务功能测试

> 页面级全量矩阵与历史执行记录见分册 `BUSINESS_FULL_TEST_MATRIX.md` §1–§3；本章为**每轮必跑**的主链路 + 挂钩 + 边界。

### 3.1 跨端主链路 P0（10 条，每轮联调/发版前必过）

| # | 链路 | L3 挂钩（必核对） | 边界/异常 | 最近状态 |
|---|------|-------------------|-----------|----------|
| 1 | 扫码开门→结算 | 会话状态机推进；订单生成；金额可读；MQTT 门事件一致 | 未登录/未开通支付/柜离线/门超时/连点 | PASS（P0-01）|
| 2 | 争议闭环 | 结案后退款/免单落账；**分账 void/adjust**；三端一致；二次结案幂等 | 重复提交；部分退 vs 全额退 | PASS（P0-05）|
| 3 | 补货履约 | 任务完结；货道账面变化；FEFO/实盘调账 | 未签到完成、扫错柜、超权限开门 | PASS（task=2）|
| 4 | 分账入账 | 有 split；`merchantShare=gross−platform(bps)`；钱包+流水；**重放不双入** | 比例 0/10000；ACCRUED 勿断言本地钱包 | PASS（S 全表）|
| 5 | 提现打款 | 冻结→PAID consume / REJECT 释放；**FAILED 冻结仍在** | 低于最低额；超日限；双 requestNo | PASS（P0-04/10）|
| 6 | 营销核销 | 发券可见；下单抵扣；核销计数；停用不可用 | 过期券、叠用、发完 | PASS（MK-01~03）|
| 7 | 设备运维 | 报修→工单闭环；通知到达 | 重复报修、取消 | PASS |
| 8 | 消息公告 | audience 可见；未发布不可见；已读计数 | 删信后对端 | PASS |
| 9 | 审批流 | 节点走完才 ACTIVE/打款；错部门 403；旧实例快照 | 跳节点、并行重复通过 | PASS |
| 10 | 数据隔离 | 他商户 403/空；运营仅权限范围可见 | 篡改 URL id | PASS（P0-07）|

### 3.2 资金域 L3 用例（S 系列）

S-01 配置生效（新单按新比例，旧单不静默改写）/ S-02 支付入账（split + 公式 + LEDGER_ONLY）/ S-03 钱包挂钩（SPLIT_CREDIT）/ S-04 商户端明细一致 / S-05 幂等（UK 拒重插）/ S-06 全额退（VOIDED + SPLIT_REVERSE）/ S-07 部分退（PARTIAL_REVERSE + 库存联动）/ S-08 ACCRUED（有 wechat_receiver 时本地钱包不入账，**文档化勿误报**）/ S-09 提现联动 —— 全部 PASS（证据 ID 见分册 §10.1）。

### 3.3 分域用例

| 域 | 用例与要点 | 状态 |
|----|------------|------|
| 交易 | P-01 余额支付 / P-02 余额不足 412 / P-03 回调重放不双扣 / P-04 开门指令 / P-05 门未关 10min `CANCELLED` 兜底 | PASS |
| 库存 | I-01 销售出库 / I-02 实盘调账面 / I-03 采购入库状态机 / I-04 FEFO 近效期优先 | PASS |
| 营销 | MK-01 发券 / MK-02 抵扣 / MK-03 停用拦截 / MK-04 积分兑换 / MK-05 会员倍率 `floor(分×rate)` | PASS |
| 风控 | R-01 拉黑拒开门 / R-02 解黑 / R-03 调余额幂等+审计 | PASS |
| 设备 | E-01 新建范围 / E-02 OTA 灰度 check / E-03 YOLO 映射 SKU | PASS |
| 系统域 | 字典 D-01~04（**只做展示非开关**）/ 参数 C-01~04（含补货四门禁：定位·开门·凭证·500m）/ 审批 A-01~06 / 告警定时审计 T-01~06 / 菜单 M-01~04（侧栏真源是 `menu.ts`） | PASS |

### 3.4 通用边界 G-01~G-15（资金/开门类全做）

空提交/非法值/状态机/连点/幂等键/401/403/404/断网/超时/并发审批/空态/分页/导出/二次确认 —— 全部 PASS（证据见分册 §7）。

### 3.5 权限与数据隔离（每 perm 至少「有权/只读/无权」三测）

运营 5 角色侧栏+直链+写按钮+数据范围 · 商户 RBAC ∩ 功能包（关 `pack_field/biz/team` 对应入口 403）· 店员/财务/店长/补货员/他商户逐号边界 · 运营号登商户门户 403 —— 全部 PASS。

### 3.6 设备 / 视觉 / 边缘专项（mock/sim 可测部分）

> 真实硬件 🔒 项仍归 §10 上线门槛；本节是**开发阶段在 `door:sim` / `vision:mock` 下就能跑完**的系统性用例。后端真源：`services/device-service`（现有 5 个测试类：`DoorEventDeduplicatorTest` / `MqttEventListenerDoorTest` / `DeviceCommandServiceTest` / `DeviceCommandTrackerTest` / `TradeServiceClientRetryTest`）；视觉：`vision-service`（CI 已跑 `test_mock_recognizer.py`）；端侧：`edge/device-simulator`（Go/Kotlin 模拟器，DV-03 依赖）与 `edge/android-app`（柜机安卓端，🔒 真柜联调阶段验收，开发阶段不阻塞）。

| ID | 项 | 验法 / 期望 | 现状 |
|----|-----|------------|------|
| DV-01 | 门事件乱序/去重 | 模拟器发重复+乱序 door 事件 → `DoorEventDeduplicator` 去重；`DoorEventDeduplicatorTest`/`MqttEventListenerDoorTest` 绿 | 后端单测绿；**端到端乱序注入用例缺** |
| DV-02 | 开门 ACK 链路 | 下发→tracker 超时/成功双分支（`DeviceCommandTrackerTest`）；trade 下游调用失败重试（`TradeServiceClientRetryTest`） | 单测绿；模拟器多柜并发注入待补 |
| DV-03 | 模拟器多柜 | `edge/device-simulator` 起 ≥2 柜（CAB-001/002），心跳/开关门互不串话；`MqttEventListenerDoorTest` 契约对齐 | 手工可跑 |
| DV-04 | vision health | `:8082/health` UP 且 `recognizer_available` 字段符合 mock/端侧模式预期 | 手工可跑 |
| DV-05 | 识别失败→争议 | mock 识别低置信度/映射失败 → 订单进入争议路径，与 `e2e-dispute-recognition.ps1` 场景一致 | 脚本已有 |
| DV-06 | 已知缺口标注 | device-service 集成测试薄（仅 5 类）：MQTT 桥与模拟器契约、多柜并发属**已知缺口**，补测优先级高于新功能用例 | 登记于 §9 |

---

## 4. 数据一致性与对账

| ID | 项 | 方法 | 期望 | 状态 |
|----|-----|------|------|------|
| DC-01 | 执行对账 | `/reconciliation` 执行 | 产出结果；mock 账单日可复现 MISMATCH | PASS（recon=1）|
| DC-02 | 一致性巡检 | `/consistency` 立即巡检 | failCount 真实可解释 | PASS（=2）|
| DC-03 | 日终三账 | 订单 ↔ 账本 ↔ 库存 | 差异 0 或全部进入可追踪异常 | 每轮跑 |
| DC-04 | 退款库存策略 | RefundInventoryPolicy | 回库/核销按策略 | PASS |
| DC-05 | 补偿任务 | 手动执行 compensation | lastRunAt 更新、有结果行 | PASS |

---

## 5. 安全测试

> 基线：`SECURITY_BEST_PRACTICES_REPORT.md`（Critical 0 / High 1 已缓解 / Medium 5 已修）。

| ID | 项 | 验法 | 状态 |
|----|-----|------|------|
| SEC-R1 | 认证会话 | admin HttpOnly cookie；失效跳登录；改密旧 token 策略 | 已修 |
| SEC-R2 | 越权 | 三端 E2E 403 矩阵 + §3.5 手工 | PASS |
| SEC-R3 | 注入 | MyBatis 无 `${}`；动态表名白名单；回归抽扫 | 已修 |
| SEC-R4 | XSS | 充值 DOMParser 白名单 / 图表 SVG sanitize / escapeAttr | 已修 |
| SEC-R5 | 密钥与 mock | `/internal/`、`/actuator/` 网关 403；strict profile 拒默认密钥与 123456；mock 控制器条件装配 | 已缓解 |
| SEC-R6 | 静态扫描 | `vue-tsc` + `mvn compile` + SpotBugs High + eslint | 每轮 |
| SEC-R7 🔒 | 渗透/等保 | 第三方渗透、漏洞扫描、脱敏留存 | 上线前 |
| SEC-R8 | 依赖漏洞审计 | CI 已自动跑：后端 Maven 全树 OSV（`audit-backend-deps.mjs`，finding 即 fail，ci.yml L178）+ 前端 prod audit（`pnpm audit:frontend`，L228）+ pip-audit 尽力而为；本地复跑 `pnpm audit:frontend` / `pnpm audit:backend` | CI 持续 |
| SEC-R9 | 密钥防提交 | CI secret scan（ci.yml L43：AKIA/RSA·EC·OPENSSH 私钥/ghp_/sk_live 模式即 fail）+ 生产模板门禁（L61：mock 必须 false、JWT 必须占位符） | CI 持续 |

---

## 6. 性能与并发

| ID | 项 | 方法 | 门槛 | 状态 |
|----|-----|------|------|------|
| PERF-1 | 订单创建压测 | JMeter（方案见 `PERFORMANCE_TESTING.md`；**注意：仓库无现成 .jmx，须先按文档内嵌 XML 导出落盘**；1000 用户 ramp 60s） | p95<800ms，错误率<0.1% | **待跑基线** |
| PERF-2 | 并发开门 | 多柜模拟器并发 | 无超开/漏开，幂等 | 部分 |
| PERF-3 | 视频上传 | 并发上传 MinIO | 无丢帧丢单 | 待跑 |
| PERF-4 | 识别任务 | 并发识别队列 | 无积压雪崩 | 待跑 |
| CONC | 后端并发单测 | trade-service `src/test` 实测 **241** 个 `*Test.java`（其中 `*ConcurrencyTest*` **87** 个、`/service/` 路径约 **198**）；device-service 另有 5 类（§3.6） | `mvn test` 全绿 | 持续 |
| FE-PERF | admin 包体积 | `check:admin-bundle` | §2.3 预算内 | 绿 |

---

## 7. 回归与自动化资产

### 7.0 改动→必跑映射（每次合入前按此裁剪，上收自 `CODEBASE_FOUNDATION.md` §11）

| 触及域 | 本地门禁 | 自动化 | 手工/抽测 |
|--------|----------|--------|-----------|
| 资金（支付/分账/提现/钱包/退款） | `pnpm check` | `e2e-fund-safety.ps1` + `e2e-shopping.ps1`；退款类加 `e2e-partial-refund-line` / `e2e-refund-restore-compare` | §3.2 S 系列抽 2 条 + 证据 ID |
| 开门/门事件/MQTT | — | device-service `*Test`（5 类）+ `e2e-shopping` | §3.6 DV-01~03；PASS 按 §0.3 保鲜 |
| 争议/识别 | — | `e2e-dispute-recognition.ps1` | §3.6 DV-05 + 三端争议 UI |
| 库存/货道/补货 | — | `e2e-replenishment.ps1` / `e2e-inventory-inout-refund.ps1` | §3.3 库存域抽 1 |
| 权限/RBAC/菜单 | `check:nav-perms` + `check:merchant-nav-guard` | 三端 `*E2ETest`（CI 强制）+ `role-regression-uat.mjs` | §3.5 变更 perm 三测 |
| UI token/样式/表格/文案/布局 | `check:admin-table-align` + `check:mp-a11y`（+ 按需 `check:admin-bundle`） | `smoke:admin-a11y`；列表改动加 `smoke:admin-list-race`；小程序逻辑加 `test:mp` | **§2.7 最短验收**；同迭代累计 ≥3 个 UI PR 再加 §2.4 对应端各 ≥3 条 |
| admin 构建/依赖 | `check:admin-bundle` | CI 产物一致性（L127） | — |
| 数据库迁移 | `check:migration-safety` | — | 起服后跑 §4 DC-01/02；**全量 271 个 Flyway 脚本须在空库一次起服成功**（CI 每次新建 PG 即隐含验证） |
| 依赖/安全 | — | CI SEC-R8/R9 自动；本地可选 `pnpm audit:frontend` | — |
| OpenAPI/类型 | `check:openapi-types` | — | 受影响端 L2 冒烟 |

### 7.1 每轮必跑（快速回归，~15 分钟）

```bash
pnpm check                                   # 注意 = lint+format+shared+nav-perms（不含 UI 门禁！）
pnpm check:admin-table-align                 # UI 门禁①（CI 未覆盖，本地必跑）
pnpm check:mp-a11y                           # UI 门禁②（CI 未覆盖，本地必跑）
pnpm check:admin-bundle                      # 包体积
pnpm test:mp                                 # 两端小程序 vitest（本地必跑；CI ≠ 本命令，见 §2.3）
.\scripts\run-api-tests.ps1                  # API 冒烟
.\scripts\e2e-shopping.ps1                   # 购物闭环
.\scripts\e2e-fund-safety.ps1                # 资金安全（含幂等 TC-5.7）
```

### 7.2 全景资产表（分域脚本，按 §7.0 加跑）

**E2E（PowerShell，`scripts/e2e-*.ps1`：磁盘 17 个 = **16 个可执行场景 + 1 个公共库 `e2e-lib.ps1`**）**：

| 脚本 | 覆盖 |
|------|------|
| `e2e-full-flow-milk.ps1` | 采购→仓→补货→购物→分账全链 |
| `e2e-shopping.ps1` / `e2e-demo-smoke.ps1` | 购物闭环 / 演示冒烟 |
| `e2e-fund-safety.ps1` | 资金安全与幂等 |
| `e2e-replenishment.ps1` | 补货闭环 |
| `e2e-partial-refund-line.ps1` / `e2e-consumer-partial-refund.ps1` / `e2e-refund-restore-compare.ps1` | 部分退三视角 / 退款恢复对比 |
| `e2e-inventory-inout-refund.ps1` | 库存进出+退款 |
| `e2e-dispute-recognition.ps1` | 争议识别 |
| `e2e-vision-gravity-shopping.ps1` | 视觉重力购物 |
| `e2e-three-end.ps1` | 三端 API 面 |
| `e2e-nearby.ps1` | 附近柜 |
| `e2e-live-cart.ps1` / `e2e-consumer-mp-flow.ps1` / `e2e-consumer-marketing-recharge.ps1` | 实时购物车 / 消费者小程序流 / 营销+充值 |
| 聚合入口 | `verify-local.ps1`（= shopping+replenishment+marketing-recharge+dispute-recognition）/ `verify-full.ps1` / `verify-production-readiness.ps1` |

**UAT（Playwright，`clients/*/tests/*.mjs`，8 个）**：

| 脚本 | 端 |
|------|-----|
| `admin-uat.mjs` / `role-regression-uat.mjs` / `batch-imp-uat.mjs` | admin 全量 / 运营角色 / 批量导入 |
| `three-end-business-uat.mjs` / `three-end-dispute-ui-uat.mjs` | 三端业务 / 三端争议 UI |
| `consumer-h5-uat.mjs` / `merchant-h5-uat.mjs` | 两端 H5 冒烟 |
| `imp-dispute-copy-uat.mjs`（在 `clients/consumer-mp/tests/`） | 消费者争议 |

**后端 E2E**：`AdminE2ETest` / `ConsumerE2ETest` / `MerchantE2ETest`（CI 强制出报告，L111-123）。工程门禁全集见 §2.3。

**辅助/工具脚本（按需）**：

| 脚本 | 用途 |
|------|------|
| `create-open-dispute.ps1` | 造争议测试数据（三端争议 UI 用例前置） |
| `apifox-smoke-scenario.ps1` / `admin-layout-smoke.ps1` | API 场景冒烟 / admin 布局冒烟 |
| `check-env.ps1`（`-Prod`） / `validate-miniapp-env.mjs` | 环境变量 / 小程序生产包联调残留检查（§10 第 4/6 条的前置工具） |
| `testdata/`（8 jpg · 5 png · 5 mp4） | 识别/视频类用例的标准素材，禁止用随手拍图跑识别断言 |

> 纪律：自动化绿 ≠ PASS。资金项仍需按 §0.3 补一次带证据 ID 的联调。

### 7.3 Playwright 验收纪律（总规约，盖过分册旧表述）

1. **UI 宣称 PASS 的唯一自动化凭据是 Playwright**：MCP 或 CLI（`clients/*/tests/*.mjs`）实跑输出；无法自动化时回退 IDE Browser 截图 + DOM 断言，**禁止口头/肉眼 PASS**。
2. **H5 PASS ≠ `mp-weixin` PASS**：小程序端结论必须来自真机或微信开发者工具（§2.5），Playwright 只覆盖 H5。
3. 旧分册 `BROWSER_MIN_UAT.md` 提及的「Cursor Browser MCP」等工具表述**已被本节取代**；冲突一律按本节执行。
4. UAT 报告落 `clients/*/output/playwright/uat-report.*`，轮次记录引用该文件。

### 7.4 运维可观测冒烟（每轮 T5 或发版前）

| 项 | 入口 | 失败判据 |
|----|------|----------|
| Actuator | trade `:8080/actuator/health`（device/vision 同理） | 非 UP； vision 另查 `/health` 的 `recognizer_available` |
| Grafana 总览 | `http://localhost:13000`（`GRAFANA_PORT` 默认 13000，`infra/docker-compose.yml` grafana 服务，profiles=apps）；总览看板 `infra/monitoring/grafana/provisioning/dashboards/json/ai-cabinet-overview.json` | 面板无数据 / 数据源红 |
| 告警规则 | `infra/prometheus/alert_rules.yml` + `infra/monitoring/alerts.yml` | 规则加载失败；抽样触发一条测试告警无通知 |
| 定时任务 | xxl-job 控制台（`infra/docker-compose.xxljob.yml`，T-01~06 对应任务） | 任务错过调度 / 失败无重试记录 |

---

## 8. 执行顺序（完整轮 ~2 天；快速轮只跑 ⚡）

| 时段 | 内容 |
|------|------|
| T0 ⚡ | §1.2 预检 + §2.1 页面数核对 + §7.1 快速回归（全绿才继续） |
| T1 ⚡ | §3.1 P0 十链路 + §3.2 S 系列资金全表 |
| T2 | §3.5 权限矩阵 |
| T3 | §2.4 UI 手工抽测（27 条）+ §2.3 门禁复核（含 CI 未覆盖两条）+ Admin 菜单轮转 10 页；本迭代若有 UI PR 则核对 §2.7 证据 |
| T4 | §3.3~3.4 分域 + 边界抽样 + §3.6 DV 专项抽样；深分支对照 `pass-notes` |
| T5 | §4 数据一致性 + §6 性能抽样 + §7.4 可观测冒烟 |
| T6 | 缺陷复测 + 证据归档 + §9 轮次记录 + §0.3 过期 PASS 复测清点 |

---

## 9. 缺陷管理与完成定义

```markdown
| 用例ID | 状态 | 失败维度 | 现象 | 截图/证据 | 严重度 |
|--------|------|----------|------|-----------|--------|
| UI-C05 | FAIL | 错误 | 生产构建仍见「模拟充值」 | uat-screenshots/... | P0 |
```

严重度：P0 资金/安全 · P1 流程阻断 · P2 体验 · P3 文案样式。

**单轮 DoD**：
- [ ] §7.1 快速回归全绿（附输出；注意 §2.3 两条 CI 未覆盖门禁必须在列）
- [ ] §2.1 页面数与真源一致（consumer 24 / merchant 22 / admin 76 路由）
- [ ] §3.1 P0 十条全 PASS 且带证据 ID（按 §0.3 保鲜规则，过期即复测）
- [ ] §2.4 UI 抽测无开放 P1/P2；两条 UI 门禁全绿；本迭代 UI PR 均有 §2.7 最短验收记录
- [ ] 所有 FAIL 有截图/日志；P0/P1 已修复复测或登记 issue

```text
轮次: YYYY-MM-DD  环境: env:… pay:… vision:… door:… client:…  执行人:
P0: __/10 · 快速回归: 绿/红 · UI 门禁: 绿/红 · 包体积: OK/超
关键 FAIL/BLOCK: - <ID>: 现象/期望/证据/issue#
```

---

## 10. 上线门槛（对接 `production-launch-checklist.md`）

**量化红线**（🔒 真实环境）：开门成功率 ≥99.9% · 关门事件完整率 ≥99.99% · 单品识别 ≥99%、组合达业务阈值 · 错扣率低于红线可快速退款 · 日终三账差异 0 · 连续灰度 ≥14 天无 P0。

**🔒 仅真实环境可终验（mock 通过不算）**：
1. 真实柜机开门/关门/门锁/心跳 + 断网与重复事件（device-service + `edge/`）
2. 真实微信/支付宝商户号、证书、回调、退款；JSAPI/免密真机
3. 端侧识别真机联调 + SKU 映射 + 置信度人工审核；≥1000 次真实拿取/放回组合
4. 生产密钥全走 Secret；`check-env.ps1 -Prod` + `verify-production-readiness.ps1` 通过
5. 高可用/备份恢复演练、等保/渗透、容量压测、RTO/RPO
6. 小程序主体认证、类目、域名白名单、审核发布
7. 停用演示账号与默认密码；RBAC 最小权限

---

## 附录 A：真源与分册索引（冲突以本文为准）

| 分册 | 内容 | 关系 |
|------|------|------|
| `BUSINESS_FULL_TEST_MATRIX.md` v1.2 | 三端页面矩阵 + 历史执行证据（注意其消费者页数 23 已过时，现为 24）；**按钮级明细继续留分册，不再上收** | §3 明细分册 |
| `BROWSER_MIN_UAT.md` / `BROWSER_FULL_UAT_PLAN.md` | 浏览器 UAT 步骤级脚本（其「Cursor Browser MCP」工具表述已被 §7.3 取代） | 步骤细化 |
| `pass-notes/PASS_3A~3F` | 资金/争议/MQTT/库存/钱包/运营深分支 | 深分支真源 |
| `ui-audit-r3-verify-2026-09-12.md` 等 5 份 | UI 五轮审计收口记录（已关闭） | §2 依据 |
| `CODEBASE_FOUNDATION.md` §10/§11 | 复杂度热点 / 验证矩阵雏形（已上收为本文 §7.0/§3.6） | 上游底稿 |
| `SECURITY_BEST_PRACTICES_REPORT.md` | 安全扫描基线 | §5 依据 |
| `PERFORMANCE_TESTING.md` | JMeter 脚本 | §6 依据 |
| `production-launch-checklist.md` | 上线清单 | §10 依据 |
| `DEMO_ACCOUNTS.md` / `STARTUP_REFERENCE.md` / `MODULES.md` | 账号/端口/模块 | §1 依据 |

## 附录 B：当前基线快照（2026-09-12 代码实测）

| 指标 | 值 |
|------|-----|
| 页面入口 | consumer **24** 页 / merchant **22** 页 / admin **76** 路由（70 个 view，menu.ts 66 项） |
| UI 门禁 a11y | 392/392 + 37/37 = 100%（`MIN_ROLE_PCT=95`） |
| UI 门禁表格 | conflicts 0 / 债 0（历史 632）/ 金额未挂 0（col-money 137 处） |
| UI 债务 | P3 收口关闭；blur 全清；z-index 七档 token；深底白字五档 |
| admin 包体积 | 总 JS 2537.3KB ≤ 3200；最大路由 WarehouseView 117KB ≤ 150 |
| P0 业务链路 | 10/10 PASS（2026-09-08 · docker-full · pay:mock · door:sim · h5）**——按 §0.3 保鲜规则，资金类 14 天即到期，下轮须复测** |
| 后端测试资产 | 全库 **246** 个 `*Test.java`：trade-service **241**（`*ConcurrencyTest*` **87**，`/service/` 路径约 **198**）+ device-service **5**；另 3 端 E2E |
| E2E/UAT 资产 | `scripts/e2e-*.ps1` 磁盘 17 个 = **16 场景 + e2e-lib 公共库**；3 个 verify 聚合；Playwright UAT **8** 个（clients/*/tests/） |
| 已知缺口 | device-service 集成测试薄（§3.6 DV-06）；CI 缺 mp-a11y/table-align 两门禁（§2.3）；性能基线未跑（§6） |
| UI/UX 最短集 | 仅 UI 合入走 **§2.7**（门禁 + 按面加验 + UX 五问）；完整抽测仍 §2.4 |
| 待办 | §6 性能基线未跑；§2.5 原生专项 🔒 未验；§10 真实环境 🔒 全部未验 |

---

*维护约定：新增/删除页面、新增资金域、更换支付/识别策略时，必须同步更新 §2.1 基线数字与对应章节并升版本号。本文由业务矩阵 v1.2 + 五轮 UI 审计（2026-09-12）合并升级，v2.1 按当日代码与脚本清单二次实测校正。*
