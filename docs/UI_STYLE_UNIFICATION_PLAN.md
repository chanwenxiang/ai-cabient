# UI 样式统一计划（2026-11-03 定稿 v2；Phase 1 已实施）

> 方案：**自绘 + 系统化**——shared-uni 升格为跨端小程序设计系统（不引第三方组件库），admin 扩展 main.css 令牌语义层。**各端内部统一**（保留端个性：admin=工作台密度、consumer=C 端轻松、merchant=B 端工具感），不追求三端同长相。
> 背景：2026-11-03 UI 样式审计（青绿色相滥用/色彩语义缺失/提示文本 99+68+100 处常驻/数字排版混乱/触达热区偏小），依据 = 当日 R13/R14/R15 全页截图 + Vercel Web Interface Guidelines + 微信小程序设计指南核验（10 项合规，偏差=触达热区 48rpx<88rpx、错误 toast 形态待核）。

## 已拍板（2026-11-03，实施以此为准）

1. **单号保持全量展示**，不做「前 6…后 4」缩略——运营/人工核对订单需要完整单号（`f14a8319` 已撤销缩略）。
2. **C 端设备原文 = 剥离，不是折叠**：`｜设备原文：` 之后不在 C 端展示；争议详情/运营后台保留全量（`469d046a`）。
3. **KPI 营收强调色跟随 `--app-primary`**（admin 主色可换 teal/blue/violet/orange）；图表数据系列仍用固定分类色 `--chart-1..6`。
4. **基线图集不做全量归档**：130+ 张截图只在 `.tmp`（gitignore）作人工参考，不入库；验收只对本次实际改动的页面拍「同会话、同数据快照」前后对比。
5. **存量页面颜色迁移 = C「碰到就迁」**（2026-11-03 拍板）：不搞全量 codemod；新代码必须用 token，每次改某页/某模块时把该页迁完，棘轮基线随之下降。完成线是「逐轮收敛」，不承诺一周内全站裸色清零。

## 现状数据（2026-11-03 实测）

| 端 | 全局基建 | 缺口 |
|----|----------|------|
| admin | `src/styles/main.css`：字号阶（display/hero/table/hint…）+ 布局色（--layout-*）+ `--z-*` 齐备 | 语义层已补（Phase 1）→ **6 位裸 hex 251 处**（Phase 2.3 财务页迁移后；另有 3 位 47、8 位 1、rgba 144，已全部纳入门禁） |
| 双小程序 | **共用 `packages/shared-uni/src/theme.css`（95 token）**，App.vue 全局导入 | token 存在但页面不遵守 → **6 位裸 hex 1048 处**（Phase 2.1/2.2/3/4 迁移后；另有 3 位 79、rgba 166，已全部纳入门禁）；提示类名 30+ 种（含页面语义类） |

## Phase 1 + 1.5 · 令牌语义层与三阶（1 天，纯 token 不动页面）

### 色值表（Phase 1 已实施，2026-11-03；以下为实际值）

**admin（main.css 追加）**

| Token | 值 | 语义 |
|-------|-----|------|
| `--color-success` | `#059669` | 正向/完成（emerald-600，与品牌青绿同族） |
| `--color-warning` | `#d97706` | 警示/待处理（amber-600） |
| `--color-danger` | `#dc2626` | 危险/失败（red-600） |
| `--color-info` | `#0284c7` | 信息/中性提示（sky-600） |
| `--chart-1..6` | `#2dd4bf #38bdf8 #a78bfa #fbbf24 #f472b6 #4ade80` | 数据系列=现有 DONUT_PALETTE 上户口；`echarts.ts` 两处（DONUT_PALETTE 定义 + 大屏主题）已单源化（2026-11-03）；剩 `AnalyticsView.vue` 6 处内联色值按 C 档「碰到就迁」 |
| `--kpi-accent-revenue` | `var(--app-primary)` | 营收=跟随 admin 主色（可换 teal/blue/violet/orange） |
| `--kpi-accent-cost` | `#94a3b8` | 成本=中性 slate |
| `--kpi-accent-profit` | `#f59e0b` | 毛利=amber |
| `--kpi-accent-rate` | `#a78bfa` | 率类=violet |
| `--kpi-accent-orders` / `-aov` | `var(--color-info)` | 订单/客单=info（同组） |

**shared-uni theme.css 追加（一处补两端）**

| Token | 值 | 语义 |
|-------|-----|------|
| `--color-pos` | `var(--success)`（=#16a34a） | 正向金额/涨（实际实现；非提案的 #059669） |
| `--color-neg` | `var(--danger)`（=#b91c1c） | 负向金额/跌 |
| `--color-warning` / `--color-info` | 既有别名（`var(--warning)` / `var(--info)`） | 待办/警示、信息链接（未新增 `--color-warn`，直接用既有别名） |

**三阶（admin + mp 同构）**

| 阶 | admin | mp（theme.css） |
|----|-------|-----------------|
| 间距 | `--space-1..8` = 4/8/12/16/20/24/32/40px | `--gap-xs/sm/md/lg` = 8/16/24/32rpx |
| 圆角 | `--admin-radius-card/-control/-dialog` = 10/8/12px（收口双档并存） | `--radius-card/control/dialog` = 24/16/32rpx |
| 阴影 | `--shadow-card/-dropdown/-modal` 三档递进 | 两档弱化（mp 阴影宜轻） |

- **Element Plus 语义色接线（已实施）**：`--el-color-success/warning/danger/info/error` + `light-3/5/7/8/9` + `dark-2` + `-rgb` 全部映射到 `--color-*`，亮/暗两套混色口径；浏览器实测 success 按钮亮色 `rgb(5,150,105)`、暗色 `rgb(52,211,153)`。
- **DoD**：新增「裸色值棘轮门禁」——admin **hex6 251 / hex3 47 / hex8 1 / rgba 144**、mp **hex6 1048 / hex3 79 / hex8 0 / rgba 166** 四项分别只减不增（`check:ui-barehex-ratchet`）；范围已含两端 components，排除 theme.css / App.vue / styles/main.css 等令牌定义文件；带负向自检与空 scope 断言。

## Phase 2 · 最痛三页迁移（2 天）

1. **consumer 订单页**：筛选/链接/金额走语义色（色相减法）；**单号保持全量**（审计/核对需要，不做缩略）；「需要关注」卡诊断文案收敛为用户语言 + **剥离** `｜设备原文：` 运维尾巴（详情/运营端保留全量，已完成 `469d046a`）；**样式收敛已落（订单页裸色清零，mp 棘轮 −45 hex6/−1 rgba）**
2. **merchant 工作台**：四个统计数字统一 KPI 数字类（20px/800/tabular-nums，与顶部 `ov-num` 同口径）；快速角标 99+ 封顶（tabBar 角标 `todo-badge.ts` 原已封顶）；页面裸色清零（−39 hex6）
3. **admin 财务毛利**：KPI 色条走 `--kpi-accent-*` 语义映射（营收＝`var(--app-primary)` 随主色；成本=slate、毛利=amber、率=violet、订单/客单=info）；「累计快照」改为 2 列 KPI 卡同款；趋势图例/系列改用 `--chart-*`/`DONUT_PALETTE`；页面裸色清零（−13 hex6）
- 附带：图标盘点——事实：`app-icon` 是 CSS 绘制 3 形态（chevron/back/circle），**不是图标字体**；实际混用 = mp 41 个文件的 emoji 字形 + admin 30 处 Element 图标（Refresh 占 22）。清单 1h 内出；待拍板：mp 是否禁止 emoji 当图标
  - **2026-11-03 订正 + 拍板**：原「41 文件 emoji」把源码注释里的标记符号（🔴 ⇒ →）也算进去了。剥掉注释后实测：mp **渲染内容里彩色 emoji = 0 处**，剩下 17 文件 35 个是 `✓ → ★ ☑ ✕ ↑ ↩` 单色排版符号，与 CSS 自绘 `app-icon` 观感一致 ⇒ **不做批量替换**；规则改为「硬禁新增彩色 emoji」，落门禁 `check:mp-emoji-icon`（基线恒 0，`--selftest` 覆盖彩色/注释/单色/VS16 四类判据）。
- DoD：三页「同会话、同数据快照」前后对比 + 其余页面 smoke（无白屏/新增 403）
- **实施进度（2026-11-03）**：2.1/2.2/2.3 代码、单测（consumer 172 / merchant 253 / admin 131）、三端 type-check、mp-weixin 构建、裸色棘轮全绿；视觉验收已完成——consumer 订单页 + merchant 工作台走**微信模拟器（非 H5）**，admin 财务毛利走**真实 Chrome（亮/暗）**；证据在 `.tmp/phase2-verify/`（gitignored），before 仅用 R13/R14/R15 参考图对照

## Phase 3 + 5 · 提示文本四层 + 文案规范（1.5 天）

四层规范：**L1 常驻**=字段名+值本身；**L2 按需**=规则/口径解释走问号组件；**L3 时机性**=空态引导/错误修复步骤；**L4 兜底**=一句话+跳帮助页。规则类解释禁止常驻。

- 组件（已落）：admin `HelpTip`（el-tooltip 封装）+ shared-uni `help-tip`（business 页 `help-q` 好形态升格，一处修两端）
- 迁移第一批（已落 17 页/27 处，规则类基本收口）：finance / fund-bills / reconciliation / platform-fund / device-rental / consistency / merchant-fee-ledgers / machine-payee-bills / sku-vision / invoices / merchant-splits / operator-manage / learning-library / org-sites / warehouse-monthly-close / warehouse / merchant-withdraw；剩余 el-alert 多为状态/操作确认（错误、权限、待处理），刻意保留
- mp 提示类名（盘点后调整口径）：实测 30+ 种，其中标准三类已定——`hint`（行内）/ `help-tip`（按需问号，旧 `help-q` 已清零）/ `empty-hint`（空态）；其余 `menu-desc` / `intro-desc` / `order-hint` / `filter-tip` 等是**页面语义样式**，刻意不做批量重命名（合并会丢语义、扩大回归面）；新代码只允许用这三类
- 文案：按钮具体化、错误必含修复步骤、加载「…」结尾、金额/单号/计数 `tabular-nums`
- DoD：常驻横幅棘轮门禁 `check:admin-banner-ratchet`（只减不增，基线 52）；文案审计口径与修复记录见下
- **实施进度（2026-11-03）**：`HelpTip`/`help-tip` 已落；admin 17 页规则横幅收进问号（el-alert 79→52，已迁页逐批核过）；merchant 经营分析 3 个 `help-q` 已换 shared `help-tip`（点击弹窗保留）；余额不足文案已含「请先充值/去充值」（open-prep-drawer），`UI_COPY.loading` 已统一「…」，orders/business/home 数值类补 `tabular-nums`；文案审计见下。

### 文案审计记录（2026-11-03）

实测 admin `ElMessage.error` 381 处、mp `showError` 230 处；不逐条重写，按以下口径统一高价值路径：

- **通用兜底**：`errorMessage` 默认改为「操作失败，请重试或联系管理员」；`useCrudTable` / 仓储 tab / 库存盘点加载失败补「请重试」；外部渠道导入四类加载失败补「请刷新重试」。
- **扫码/购物**：扫码取消或无法识别 → 「请重试或手动输入柜机编号」；「暂无可选」→「该商品暂无可选库存」；「无法再加」→「已达可购上限」。
- **加载**：充值按钮「处理中…」；`UI_COPY.loading` 已是「加载中…」；其余「处理中」为业务状态，不强行加省略号。
- **确认按钮具体化**：设备生命周期「确认激活/退役…」、外部导入「确认提交 / 确认放弃 / 解绑」、采购审批「通过 / 驳回」。
- **误标修正**：外部导入「放弃批次」失败原提示「绑定失败」→「放弃失败，请重试」。
- **不叠加原则**：后端已返回具体原因时不再追加泛化修复句，避免文案肿胀；内部/技术错误不外露。
- **数值对齐**：orders / business / home 的金额、单号、计数类已补 `tabular-nums`。

## Phase 4 · 可达性 + 交互三态 + 微信合规清点（1 天）

- 加载态两标准：列表骨架屏 / 操作按钮 spinner
- 触控热区清点：可点元素 < 88rpx 清单（清点脚本顺手写成门禁；微信规范热区约 7–9mm）
- 错误提示形态核查：`showError` 全链路确认为文字型带修复步骤（微信规范：图标型 toast 不适用错误）
- 焦点态 focus-visible / 图标按钮 aria-label / safe-area / 图片显式尺寸+懒加载 / 表单细节（autocomplete、错误聚焦首字段）
- 自绘控件对齐 WeUI 交互形态（按钮高度/主次态/cell 形态/弹窗双钮顺序）——借规范不引库
- DoD：清单内条目清零或登记 wontfix
- **实施进度（2026-11-03）**：
  - a11y：mp clickables 433/433（100%），icons 35/35；补 9 处 role/aria；admin dialog 门禁绿。
  - 热区：微信模拟器量 orders/mine/home/business/devices/replenishment 等 9 页，small=0；aux-link / foot-btn / scan-btn / help-tip / mine quick-item 改「88rpx 热区 + 小视觉」；devices「常驻柜」筛选按用户拍板移除（保留设备行 ★ 与「清除常驻」）。
  - 热区门禁落仓（2026-11-03 补）：`scripts/check-mp-touch-target-ratchet.mjs`（静态判据：模板可点元素 + CSS 显式 rpx 宽高 ⇒ 有效短边 <88rpx 记违规；padding/只写单边不判、后代选择器不连坐基类；**认得两种合规写法**=本体 ≥88 或 `.X::after{position:absolute;inset:-Nrpx}` 热区扩展）。首扫抓到运行时探针漏掉的 5 处，同日全部清零 ⇒ **基线 0**（再犯即红）：`evidence-del 32`→`inset:-28`、`promo-popup-close 48`×2→`inset:-22`、`qty-btn 56`→`position:relative`+`inset:-18`；`stepper-btn 44` 本就有 `inset:-22` ⇒ 属门禁初版误报，已把判据修对并补自检用例。已接进 `check:audit-gates`，负向对照（基线压 1）正确报红；三条新规则核对已编进 wxss。
  - safe-area：live-cart-sheet 补 env；HomeCartBar / AppSheet / open-prep-drawer / order-appeal-sheet / order-detail 已有。
  - 表单：两端登录补 aria-label / autocomplete + 错误聚焦首字段；admin 登录已有 autocomplete。
  - 图片：商品格 / 活动 banner / 申诉附图补 `lazy-load`；显式尺寸沿用 CSS 类。
  - 按钮/弹窗：refund / alerts / empty-scan / divider-toggle 72→88rpx；AppConfirmDialog 取消左 / 确认右；app-button 主按钮已 88rpx。
  - 加载：操作按钮 spinner 沿用 app-button `loading`；列表骨架屏 2026-11-03 补齐——shared-uni 新增 `list-skeleton`（3 端令牌、WXSS 无 color-mix、prefers-reduced-motion 降级），easycom 注册两端 + `OPTIONAL_SHARED_COMPONENTS` 拍平；消费端 7 页 / 商户端 10 页列表加载态由「加载中…」文字换骨架（消费端 orders 首载走页级 `booting` 门，该门一并换骨架）。消费端订单页微信模拟器截图实证（`.tmp/phase2-verify/phase4-skeleton-consumer-orders.png`，sk-card=4）；商户端实证受限于该 IDE 实例 mockWxMethod/`wx.request` 补丁均无法拦截请求（消费端实例可以，同版本行为不一致），以构建产物（17 页 usingComponents+wxml 含组件）+ 同组件同接线作证。
  - 补货措辞对齐「自提自送」铁律：商户端「出库 N」chip→「领货 N」、明细提示「有出库明细」→「有领货明细」、自补货提交提示去「分仓出库」字样（[useWarehouseOutbounds.ts:20] 铁律）。

## Phase 6 · 防回归 + 规范落仓（0.5 天）

- 截图基线棘轮：R13 截图 runner **未存仓，需新写**；现有 CI 只有 e2e-h5 且 admin `-Pskip-admin-ui`，三端截图无法在 CI 跑 ⇒ 定位为本地 pre-push + 交付前人工复核（✅ 2026-11-03 用户拍板此定位，runner 已落仓，见下）
- `ui-style-guide` skill 落仓：语义色板/四层提示/三阶/三态/WeUI 对齐表——未来会话自动遵守
- 最终验收：只对本次实际改动的页面复跑探针 + 交付「同会话、同数据快照」前后对比；**不做 130+ 全量归档**
- **实施进度（2026-11-03）**：
  - ✅ Skill 已落 `.cursor/skills/ui-style-guide/SKILL.md`（硬规则/色板三阶速查/四层提示+组件/文案口径/验收面/门禁命令/已拍板禁区七节），并登记进 `ai-cabinet-dev-test` 路由表 + `PROJECT_KNOWLEDGE.md` §7.1；路由表原「小程序 UI → Playwright/H5」已订正为**微信开发者工具模拟器（非 H5）**。
  - ✅ 最终验收复跑：三端单测 consumer 172 / merchant 253 / admin 131 全绿（与 Phase 2 基线一致，零回归）；消费端 7 个改动列表页 + 商户端 14 个改动页在微信模拟器实跑 **bad=0**（骨架屏全部正常复位、真实数据渲染：订单 29 / 余额 ¥47.57 / 消息 77 未读）；admin 本轮无新改动（Phase 2.3/3 的 Chrome 亮/暗实证仍有效）。
  - ✅ 收尾三件（2026-11-03 深夜批）：① 5 处小热区清零 + 门禁认得伪元素热区惯用法（见 Phase 4）；② **新增 `check:mp-emoji-icon`**——渲染内容硬禁彩色 emoji（基线 0，实测本来就 0），订正了本文「41 文件 emoji」的口径（含注释标记）；③ 删掉 echarts 大屏暗色主题死代码（`BIGSCREEN_THEME`/`registerBigScreenTheme` 无调用方、`EChart` 的 `theme` prop 无人传，大屏各图本就逐图显式配色）。
  - ✅ 补「改了控件却没视觉实证」的缺口（2026-11-04）：商户端补货详情抽屉实跑打开——4 张任务卡、`.qty-btn` ×4 渲染正常，量到本体 29×29px（=56rpx），配 `inset:-18rpx` ⇒ 实际热区 ≈94rpx，抽屉各区块（步骤条/签到/取消任务/照片/录像/明细）完好。**消费端两处（`promo-popup-close`、`evidence-del`）无法实证**：弹窗数据源是 `promotion_placement`（活动↔位置↔图片），当前 dev 库没配 ⇒ `GET /api/v2/marketing/popups?position=POPUP_BEFORE_OPEN` 返回 `[]`；`evidence-del` 需要先有带附件的申诉单。两处改动与已证的那处是同一写法（已定位元素上的不可见 `::after` 负 inset，不动布局），且三条规则都已核对编进 wxss；要补证需在共享 dev 库造投放数据（待用户点头）。
  - ✅ 基址口径变更（用户拍板）：**开发者工具验证期把 `clients/{consumer,merchant}-mp/.env.development.local` 钉成 `http://127.0.0.1`**，真机预览才临时改局域网 IP。起因是本机 WLAN IP 一天内 `.26→.199→.26` 来回漂两次，每次都让列表卡在加载态、几乎被误判成骨架屏 bug；且 `cli cache --clean compile` 对内联常量不够，须 `cli quit` + 删 `WeappCompileCache` 才恢复。判据顺序与处置已写进 skill §5。：① 5 处小热区清零 + 门禁认得伪元素热区惯用法（见 Phase 4）；② **新增 `check:mp-emoji-icon`**——渲染内容硬禁彩色 emoji（基线 0，实测本来就 0），订正了本文「41 文件 emoji」的口径（含注释标记）；③ 删掉 echarts 大屏暗色主题死代码（`BIGSCREEN_THEME`/`registerBigScreenTheme` 无调用方、`EChart` 的 `theme` prop 无人传，大屏各图本就逐图显式配色）。
  - 🔴 runner 自身两个判据 bug 已修：mp 截图是 **Adam7 隔行 PNG**，初版解码器不支持隔行 ⇒ 21 页全报「差 100%」；补 Adam7 逐 pass 解 + `--selftest` 增「隔行同图必须 0%」。另：mp 截图含模拟器状态栏时钟 ⇒ 噪声底实测 0.05–0.2%，阈值由 0.2% 放宽到 **0.5%**（`--diff-threshold` 可调），否则每页天天假 CHANGED。
  - ⚠️ 复跑中发现并修复的环境问题（非代码缺陷）：本机 WLAN IP 由 `192.168.0.26` 变为 `192.168.0.199`，两端 `.env.development` / merchant `.env.development.local` 仍烤旧 IP ⇒ 请求连不到网关、列表停在骨架屏（旧版会同样停在「加载中…」）。跑 `node scripts/sync-consumer-mp-api.mjs` 重选 + 手工更新 `.local` 后重烤即恢复。**网络变更后跑 mp 验收前必须先重烤 env**，否则会把 IP 漂移误判成骨架屏 bug。
  - ✅ 截图基线 runner 已写（用户认可「本地 pre-push + 交付前人工复核」定位）：`scripts/ui-screenshot-runner.mjs` + 页面清单 `scripts/ui-screenshot-pages.json`。三模式 capture/`--promote`/`--mode=check` + `--selftest`，健康判据=白屏/console error/骨架屏 7s 未复位记 FAIL，其余按**像素差异占比**（阈值 0.5%，内置无依赖 PNG 解码，支持 Adam7 隔行）给 SAME/CHANGED——初版用哈希判等，同页两次截图差 3 字节就报 CHANGED，会把人工复核淹掉，故换像素比。截图与基线都在 `.tmp/ui-baseline/`（gitignore，与「不入库」拍板一致）。**三端端到端实跑**：admin 9 页 × 亮/暗 18 张 FAIL=0（暗色主题、HelpTip 问号、真实订单列表均在）、consumer 7 页、merchant 14 页 FAIL=0。
  - ✅ `:3100` 已按用户指示重启（旧进程 11/2 08:55 起、依赖预构建缓存过期），重启后 `/orders` `/devices` `/warehouse` 亮/暗全部挂载正常；18 张与基线比对 **FAIL=0 / CHANGED=0**（此前两张暗色图的 CHANGED 实测差 0.001%，是 PNG 压缩噪声）。
  - ⚠️ 实跑中抓到的两个**环境坑**（都不是样式回归，已写进 skill §5）：① 商户端 `dist/dev` 的 wxml/wxss scoped 哈希不一致（`a1958b82` vs `0cd09a48`）⇒ 模拟器整页无样式，根因是**另一会话在用的 `uni.js` 增量 watcher 与全量构建交叉写 dist/dev**（该会话 src 30 分钟内仍在改 ⇒ watcher 不能杀）⇒ 验收面切 `dist/build/mp-weixin` 即恢复；② admin `:3100` 的 vite 依赖预构建缓存过期 ⇒ 动态 import 回 504 Outdated Optimize Dep ⇒ `/orders` `/devices` `/warehouse` 只剩「跳到主内容」，**需用户重启 dev server**（runner 已能识别并在 FAIL 文案里点破）。

## 依赖与工具

- Chrome DevTools MCP：workspace 配置 `.zcode/config.json`（`mcp.servers.chrome-devtools`，v1.12.0）——**生效需重启会话**，客户端 Settings → MCP 可查状态
- 基线资产：R13/R14/R15 的 130+ 张截图（仅在 `.tmp`，gitignore，作人工参考，**不入库**）；R13 截图 runner 未存仓；automator 配方（9420/9421）

## 明确不做

三端视觉一致化（各自统一）｜换组件库（TDesign/uni-ui/Vant 评估过，迁移风险>收益）｜暗色模式实现（语义色板天然预留）｜业务逻辑改动
