# ai-cabinet 三端 UI 合并审计与修改任务

> 合并时间：2026-09-12  
> 收口时间：2026-09-12（Wave A–D 完成并推送 `46c00283`）  
> 来源：  
> 1. [`ui-audit-2026-09-12.md`](./ui-audit-2026-09-12.md) 第一轮 + §八进度  
> 2. [`ui-audit-2026-09-12-r2.md`](./ui-audit-2026-09-12-r2.md) 第二轮（源码扫描 + Playwright）  
> 3. 人工复核：报告「已修」与代码不一致处（M02 / C01 / A01 / A07 / A04 / A05 等）  
>  
> **本文件为状态真源**；旧报告保留作历史。Wave A–D **任务均已做完**；下文「剩余债」为有意降级项，不是未完成主任务。

---

## 〇、合并总评（收口后）

| 层级 | 结论 |
|------|------|
| 用户原始截图类问题 | 已修（弹窗等宽、遮罩、帮助双标题、全局表格强制居中、品牌主色等） |
| 「报告写已修、代码半到位」 | **已打实**（M02 / C01′ / A01′ / A05 / A04 / A07 冲突清零） |
| Wave A–D | **全部完成**；admin / consumer / merchant 均有 Playwright 结论 |
| 剩余系统债 | 见 §1.3（STRICT 列对齐、a11y CI 门槛、营销色 / fallback 等），**不挡本轮关闭** |

**原则校正（合并时已采纳，仍有效）：**

1. **M02**：不能简单把 consumer `.card` 改成四边 `page-gutter`（会双倍水平边距）。要对齐的是**布局模型**。  
2. **C01**：原「双标题重复」已消；`.hero` / `hero-sub` 已删，属结构收口。  
3. **A01**：空态/骨架 + `.qr-body { min-height: 240px }`；重建 admin 后已实测。  
4. **深底白字 / rgba(255,255,255,x)**：合法特效，用 `--on-deep-opacity-*` / `--white`，勿零容忍删光。

---

## 一、状态总表（合并后）

### 1.1 已关 / 可维持

| ID | 说明 |
|----|------|
| G01 | 三端品牌 `#0f766e` / `#134e4a` |
| G03 / R2-G01（高频） | 业务页硬编码色已迁 token；`--white` / soft / chart 已补 |
| G04 / R2-G02 | `nearby.vue` 字号 → `--font-size-*` |
| G06 / G09 组件主体 | shared-uni empty/error + AppButton；空态无 `∅` |
| G07 / R2-G05 | `.action-btn` → `app-btn-flex` |
| G08 箭头 / R2-C01 | 无字面量 `›`/`‹`；帮助中心 SVG mask 图标 |
| X04 / R2-C06 / R2-M04 | 加载文案 → `UI_COPY` / `loadingLabel()` |
| X05 | H5 `--phone-w: clamp(...)` |
| C08–C11 / C07 等 | 弹窗按钮、遮罩、resume 实心卡、tip token |
| A06 / A10 / A11 | 侧栏顶栏、暗色字、卡片 padding |
| A07（全局强制居中） | `main.css` 全局 `center !important` 已取消；`col-text` 冲突已清 |
| M01 / M07 | 品牌对齐、TabBar `borderStyle` |
| R2-M01 / R2-M02 | 商户未绑定文案互斥 |
| R2-M03 | `device-detail` / `business` → `app-status` |
| R2-G04 / R2-M05 | 登录去玻璃模糊 |

### 1.2 半到位（报告曾标已修 → 本轮已打实）

| ID | 问题 | 正确理解 | 状态 |
|----|------|----------|------|
| **M02** | consumer / merchant `.card` 用法曾不一致 | 水平 gutter 在 page-body；`.card` 仅纵向 16rpx | **已打实** |
| **C01′** | `help.vue` 曾有 `.hero` + `hero-sub` | 已删除 hero 块 | **已打实** |
| **A01′** | `.qr-body` 无 `min-height` | `min-height: 240px`；T-D1 重建后实测 | **已打实** |
| **A07′** | 大量 `align="center"` | `col-text`+center 冲突已清；裸居中见 §1.3 | **冲突已打实** |
| **A05** | `label-width` 混用 | 统一 `auto` + `--admin-form-label-width` | **已打实** |
| **A04** | Tabs / breadcrumb 层级弱 | DeviceDetail header/Tabs 已加强 | **已打实** |

### 1.3 剩余债（有意降级，非本轮未完成）

| ID / 项 | 严重度 | 现状 | 建议 |
|---------|--------|------|------|
| A07 裸 `align="center"` | P3 | ~632 列仍居中（状态/操作等合理场景为主） | 可选 `STRICT=1` 再收；勿与 `col-text` 冲突回潮 |
| G10 / X01–X02 a11y CI | P3 | `pnpm check:mp-a11y` 默认门槛 70%；实测 ~97% | 阈值分期抬到 95%，再进 CI hard fail |
| G03 余量色 | P3 | `var(..., #fallback)`、token 定义行、少数营销渐变 | 不必强清；新代码禁止裸 hex |
| 图表色 | — | `--chart-*` 独立色板 | 不与业务 token 混谈 |

---

## 二、修改任务清单（按波次）

### Wave A — 打实「已修」标签

| 任务 ID | 内容 | 状态 | 触及文件 |
|---------|------|------|----------|
| **T-A1** | M02：两端 `.card` 仅纵向；`.page-body` 水平 gutter；devices/alerts 补水平 margin | **已做** | 两端 `App.vue`、`devices.vue`、`alerts.vue` |
| **T-A2** | C01′：删除 help `.hero` / `hero-sub` | **已做** | `help.vue` |
| **T-A3** | A01′：`.qr-body { min-height: 240px }` | **已做** | `DeviceDetailView.vue` |
| **T-A4** | DeviceDetail 文本列 `col-text`；金额 `col-money`；表单 `label-width`→`auto` | **已做** | `DeviceDetailView.vue` |

### Wave B — 工程化收口

| 任务 ID | 内容 | 状态 | 触及文件 |
|---------|------|------|----------|
| **T-B1** | 全 admin `label-width`→`auto`；`--admin-form-label-width` | **已做** | 49 处 / 29 文件 + `main.css` |
| **T-B2** | 清 `col-text`+`align=center`；eslint + `pnpm check:admin-table-align` | **已做**（STRICT 未开） | 43 views + eslint + script |
| **T-B3** | DeviceDetail page-header / Tabs 层级 | **已做** | `DeviceDetailView.vue` |
| **T-B4** | `--on-deep-opacity-*`；index/mine 深底白字 | **已做** | `theme.css`、`index.vue`、`mine.vue` |

### Wave C — 用户可感知体验

| 任务 ID | 内容 | 状态 |
|---------|------|------|
| **T-C1** | 帮助中心 SVG mask 图标 | **已做** |
| **T-C2** | 空态 CSS glyph（shared + 两端） | **已做** |
| **T-C3** | 商户未绑定文案互斥 | **已做** |
| **T-C4** | `app-status` + `onlineLabel` | **已做** |
| **T-C5** | `nearby.vue` → `--font-size-*` | **已做** |
| **T-C6** | `UI_COPY` / `loadingLabel()` | **已做** |
| **T-C7** | hex→token；`--white`；裸 `#fff` 清零 | **已做** |
| **T-C8** | 登录去玻璃模糊 | **已做** |
| **T-C9** | `.action-btn` → `app-btn-flex` | **已做** |

### Wave D — 补验与门禁

| 任务 ID | 内容 | 状态 |
|---------|------|------|
| **T-D1** | Playwright admin：登录 / 设备列表 / 详情 QR / Dashboard | **已做**（见附录；须 `build-admin`） |
| **T-D2** | `pnpm check:mp-a11y`（默认 `MIN_ROLE_PCT=70`） | **已做**（~97.7% / ~94.6%） |
| **T-D3** | 任务状态 + consumer/merchant Playwright 回归 | **已做** |

---

## 三、明确不做或降级（避免范围膨胀）

| 项 | 决定 |
|----|------|
| 强制一周内 a11y ≥95% CI | **降级**为 T-D2 抽检，阈值分期 |
| 图表色全部清零 | 单独 `--chart-*`，不与业务 token 混谈 |
| 把 consumer `.card` 直接改成四边 `page-gutter` | **禁止**（双倍 gutter）；走 T-A1 方案 A |
| 把 C01′ 写成「原 C01 完全没修」 | **禁止**；按结构收口跟踪 |
| admin 全库 `align="center"` STRICT | **可选后续**；本轮只清与 `col-text` 冲突 |

---

## 四、后续若「继续」

优先处理 §1.3 剩余债（STRICT / a11y CI / 新页禁止裸 hex）；**不必再重开 Wave A–D**。

---

## 五、附录：关键证据索引

| 证据 | 位置 |
|------|------|
| consumer `.card` 仅下边距 | `clients/consumer-mp/src/App.vue`（含防双 gutter 注释） |
| merchant page-body gutter | `clients/merchant-mp/src/App.vue` |
| help 无 hero-sub、SVG 入口图标 | `clients/consumer-mp/src/pages/help/help.vue` |
| `.qr-body` min-height | `DeviceDetailView.vue`；重建后实测 `240px` |
| `col-text` 左对齐 | `main.css`；重建后 `td.col-text` → `left` |
| hex→token | `scripts/migrate-hex-tokens.mjs`（`pnpm migrate:hex-tokens`） |
| mp a11y | `scripts/check-mp-a11y-coverage.mjs`（`pnpm check:mp-a11y`） |
| R2 结论 | `docs/ui-audit-2026-09-12-r2.md` §五 |
| 验收截图 | 本地已删（gitignore）；结论见下表 |

### T-D1 Playwright 结论（2026-09-12）

| 步骤 | 结果 |
|------|------|
| 登录 `13900000001` + Redis captcha | PASS → `/admin/dashboard` |
| Dashboard | PASS（KPI/卡片可见） |
| 设备列表 | PASS（6 行） |
| 设备详情 `330449777078` | PASS；页头「返回 / 在线 / 入库 / 已锁机」 |
| QR `.qr-body` | `build-admin` 后 `min-height: 240px` |
| `col-text` | 重建后 `text-align: left` |

说明：gateway 挂载 `services/trade-service/.../static/admin`；验收源码 UI 改动前须重建 admin。

---

*合并人：Cursor Agent · 2026-09-12；Wave A–D 收口同日*
