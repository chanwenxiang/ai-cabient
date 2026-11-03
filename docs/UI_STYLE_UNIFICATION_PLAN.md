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
| 双小程序 | **共用 `packages/shared-uni/src/theme.css`（95 token）**，App.vue 全局导入 | token 存在但页面不遵守 → **6 位裸 hex 1052 处**（Phase 2.1/2.2/3 迁移后；另有 3 位 79、rgba 166，已全部纳入门禁）；提示类名 30+ 种（含页面语义类） |

## Phase 1 + 1.5 · 令牌语义层与三阶（1 天，纯 token 不动页面）

### 色值表（Phase 1 已实施，2026-11-03；以下为实际值）

**admin（main.css 追加）**

| Token | 值 | 语义 |
|-------|-----|------|
| `--color-success` | `#059669` | 正向/完成（emerald-600，与品牌青绿同族） |
| `--color-warning` | `#d97706` | 警示/待处理（amber-600） |
| `--color-danger` | `#dc2626` | 危险/失败（red-600） |
| `--color-info` | `#0284c7` | 信息/中性提示（sky-600） |
| `--chart-1..6` | `#2dd4bf #38bdf8 #a78bfa #fbbf24 #f472b6 #4ade80` | 数据系列=现有 DONUT_PALETTE 上户口（echarts.ts 仍硬编码，单源化待 Phase 2） |
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
- **DoD**：新增「裸色值棘轮门禁」——admin **hex6 251 / hex3 47 / hex8 1 / rgba 144**、mp **hex6 1052 / hex3 79 / hex8 0 / rgba 166** 四项分别只减不增（`check:ui-barehex-ratchet`）；范围已含两端 components，排除 theme.css / App.vue / styles/main.css 等令牌定义文件；带负向自检与空 scope 断言。

## Phase 2 · 最痛三页迁移（2 天）

1. **consumer 订单页**：筛选/链接/金额走语义色（色相减法）；**单号保持全量**（审计/核对需要，不做缩略）；「需要关注」卡诊断文案收敛为用户语言 + **剥离** `｜设备原文：` 运维尾巴（详情/运营端保留全量，已完成 `469d046a`）；**样式收敛已落（订单页裸色清零，mp 棘轮 −45 hex6/−1 rgba）**
2. **merchant 工作台**：四个统计数字统一 KPI 数字类（20px/800/tabular-nums，与顶部 `ov-num` 同口径）；快速角标 99+ 封顶（tabBar 角标 `todo-badge.ts` 原已封顶）；页面裸色清零（−39 hex6）
3. **admin 财务毛利**：KPI 色条走 `--kpi-accent-*` 语义映射（营收＝`var(--app-primary)` 随主色；成本=slate、毛利=amber、率=violet、订单/客单=info）；「累计快照」改为 2 列 KPI 卡同款；趋势图例/系列改用 `--chart-*`/`DONUT_PALETTE`；页面裸色清零（−13 hex6）
- 附带：图标盘点——事实：`app-icon` 是 CSS 绘制 3 形态（chevron/back/circle），**不是图标字体**；实际混用 = mp 41 个文件的 emoji 字形 + admin 30 处 Element 图标（Refresh 占 22）。清单 1h 内出；待拍板：mp 是否禁止 emoji 当图标
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

## Phase 6 · 防回归 + 规范落仓（0.5 天）

- 截图基线棘轮：R13 截图 runner **未存仓，需新写**；现有 CI 只有 e2e-h5 且 admin `-Pskip-admin-ui`，三端截图无法在 CI 跑 ⇒ 定位为本地 pre-push + 交付前人工复核（待确认）
- `ui-style-guide` skill 落仓：语义色板/四层提示/三阶/三态/WeUI 对齐表——未来会话自动遵守
- 最终验收：只对本次实际改动的页面复跑探针 + 交付「同会话、同数据快照」前后对比；**不做 130+ 全量归档**

## 依赖与工具

- Chrome DevTools MCP：workspace 配置 `.zcode/config.json`（`mcp.servers.chrome-devtools`，v1.12.0）——**生效需重启会话**，客户端 Settings → MCP 可查状态
- 基线资产：R13/R14/R15 的 130+ 张截图（仅在 `.tmp`，gitignore，作人工参考，**不入库**）；R13 截图 runner 未存仓；automator 配方（9420/9421）

## 明确不做

三端视觉一致化（各自统一）｜换组件库（TDesign/uni-ui/Vant 评估过，迁移风险>收益）｜暗色模式实现（语义色板天然预留）｜业务逻辑改动
