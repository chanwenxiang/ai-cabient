# UI 样式统一计划（2026-11-03 定稿，待实施）

> 方案：**自绘 + 系统化**——shared-uni 升格为跨端小程序设计系统（不引第三方组件库），admin 扩展 main.css 令牌语义层。**各端内部统一**（保留端个性：admin=工作台密度、consumer=C 端轻松、merchant=B 端工具感），不追求三端同长相。
> 背景：2026-11-03 UI 样式审计（青绿色相滥用/色彩语义缺失/提示文本 99+68+100 处常驻/数字排版混乱/触达热区偏小），依据 = 当日 R13/R14/R15 全页截图 + Vercel Web Interface Guidelines + 微信小程序设计指南核验（10 项合规，偏差=触达热区 48rpx<88rpx、错误 toast 形态待核）。

## 现状数据（2026-11-03 实测）

| 端 | 全局基建 | 缺口 |
|----|----------|------|
| admin | `src/styles/main.css`：字号阶（display/hero/table/hint…）+ 布局色（--layout-*）+ `--z-*` 齐备 | 语义层缺失（无状态色/图表系列/KPI 映射）→ 视图内 **275 处裸 hex** |
| 双小程序 | **共用 `packages/shared-uni/src/theme.css`（91 token）**，App.vue 全局导入 | token 存在但页面不遵守 → 页面+组件 **1057 处裸 hex**；提示类名 12+ 种自造 |

## Phase 1 + 1.5 · 令牌语义层与三阶（1 天，纯 token 不动页面）

### 色值表提案（全部从现有色系派生，不引入新视觉语言）

**admin（main.css 追加）**

| Token | 值 | 语义 |
|-------|-----|------|
| `--color-success` | `#059669` | 正向/完成（emerald-600，与品牌青绿同族） |
| `--color-warning` | `#d97706` | 警示/待处理（amber-600） |
| `--color-danger` | `#dc2626` | 危险/失败（red-600） |
| `--color-info` | `#0284c7` | 信息/中性提示（sky-600） |
| `--chart-1..6` | `#2dd4bf #38bdf8 #a78bfa #fbbf24 #f472b6 #4ade80` | 数据系列=现有 DONUT_PALETTE 上户口（echarts.ts 改引 token） |
| `--kpi-accent-revenue` | `var(--chart-1)` | 营收=品牌青绿 |
| `--kpi-accent-cost` | `#94a3b8` | 成本=中性 slate |
| `--kpi-accent-profit` | `#f59e0b` | 毛利=amber |
| `--kpi-accent-rate` | `#a78bfa` | 率类=violet |

**shared-uni theme.css 追加（一处补两端）**

| Token | 值 | 语义 |
|-------|-----|------|
| `--color-pos` | `#059669` | 正向金额/涨 |
| `--color-neg` | `#b91c1c` | 负向金额/跌（复用 danger 族） |
| `--color-warn` | `#d97706` | 待办/警示 |
| `--color-info` | `#0284c7` | 信息链接 |

**三阶（admin + mp 同构）**

| 阶 | admin | mp（theme.css） |
|----|-------|-----------------|
| 间距 | `--space-1..8` = 4/8/12/16/20/24/32/40px | `--gap-xs/sm/md/lg` = 8/16/24/32rpx |
| 圆角 | `--admin-radius-card/-control/-dialog` = 10/8/12px（收口双档并存） | `--radius-card/control/dialog` = 24/16/32rpx |
| 阴影 | `--shadow-card/-dropdown/-modal` 三档递进 | 两档弱化（mp 阴影宜轻） |

- **DoD**：新增「裸 hex 棘轮门禁」——admin views 275 / mp 1057 只减不增（扩展现有 UI Token 门禁）。

## Phase 2 · 最痛三页迁移（2 天）

1. **consumer 订单页**：筛选/链接/金额走语义色（色相减法）；单号格式化（前6…后4）；「需要关注」卡诊断文案收敛为用户语言 + 设备原文折叠区
2. **merchant 工作台**：四个统计数字统一 KPI 数字类；角标 99+ 封顶
3. **admin 财务毛利**：KPI 色条走 `--kpi-accent-*` 语义映射；「累计快照」卡重做成 KPI 卡同款
- 附带：图标风格盘点（Element 图标 vs `app-icon` 混用处清单）
- DoD：三页前后截图对比 + 全页探针无回归

## Phase 3 + 5 · 提示文本四层 + 文案规范（1.5 天）

四层规范：**L1 常驻**=字段名+值本身；**L2 按需**=规则/口径解释走问号组件；**L3 时机性**=空态引导/错误修复步骤；**L4 兜底**=一句话+跳帮助页。规则类解释禁止常驻。

- 组件：admin `HelpTip`（el-tooltip 封装）+ shared-uni `help-tip`（business 页 `help-q` 好形态升格，一处修两端）
- 迁移第一批：admin 99 处横幅中规则类（约 30 处）；mp 12 种提示类名收敛为 3 种标准（`hint` / `help-tip` / `empty-hint`）
- 文案：按钮具体化、错误必含修复步骤、加载「…」结尾、金额/单号/计数 `tabular-nums`
- DoD：常驻横幅棘轮门禁（只减不增）

## Phase 4 · 可达性 + 交互三态 + 微信合规清点（1 天）

- 加载态两标准：列表骨架屏 / 操作按钮 spinner
- 触控热区清点：可点元素 < 88rpx 清单（清点脚本顺手写成门禁；微信规范热区约 7–9mm）
- 错误提示形态核查：`showError` 全链路确认为文字型带修复步骤（微信规范：图标型 toast 不适用错误）
- 焦点态 focus-visible / 图标按钮 aria-label / safe-area / 图片显式尺寸+懒加载 / 表单细节（autocomplete、错误聚焦首字段）
- 自绘控件对齐 WeUI 交互形态（按钮高度/主次态/cell 形态/弹窗双钮顺序）——借规范不引库
- DoD：清单内条目清零或登记 wontfix

## Phase 6 · 防回归 + 规范落仓（0.5 天）

- 截图基线棘轮：R13 探针脚本（78 页）改造为门禁（渲染结构 diff）
- `ui-style-guide` skill 落仓：语义色板/四层提示/三阶/三态/WeUI 对齐表——未来会话自动遵守
- 最终验收：复跑三端全页探针 + 交付前后对比图集

## 依赖与工具

- Chrome DevTools MCP：workspace 配置 `.zcode/config.json`（`mcp.servers.chrome-devtools`，v1.12.0）——**生效需重启会话**，客户端 Settings → MCP 可查状态
- 基线资产：R13/R14/R15 的 130+ 张截图（before 图集）；R13 探针脚本；automator 配方（9420/9421）

## 明确不做

三端视觉一致化（各自统一）｜换组件库（TDesign/uni-ui/Vant 评估过，迁移风险>收益）｜暗色模式实现（语义色板天然预留）｜业务逻辑改动
