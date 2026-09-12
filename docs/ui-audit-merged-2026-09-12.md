# ai-cabinet 三端 UI 合并审计与修改任务

> 合并时间：2026-09-12  
> 来源：  
> 1. [`ui-audit-2026-09-12.md`](./ui-audit-2026-09-12.md) 第一轮 + §八进度  
> 2. [`ui-audit-2026-09-12-r2.md`](./ui-audit-2026-09-12-r2.md) 第二轮（源码扫描 + Playwright）  
> 3. 人工复核：报告「已修」与代码不一致处（M02 / C01 / A01 / A07 / A04 / A05 等）  
>  
> **本文件为当前唯一待办清单**；旧报告保留作历史，状态以本文为准。

---

## 〇、合并总评

| 层级 | 结论 |
|------|------|
| 用户原始截图类问题 | 大多已修（弹窗等宽、遮罩、重复大标题「帮助中心」、全局表格 `!important` 居中、品牌主色等） |
| 「报告写已修、代码半到位」 | **成立**：M02 卡片 gutter、A07 列 `align="center"`、A05 `label-width`、A01 高度稳固、C01 结构收口 |
| 仍大的系统债 | 硬编码色、字号 scale、加载文案、图标精致度、商户工作台空态/绑定态 |
| 本轮缺口 | admin 运行态未 Playwright 验收（服务未起） |

**原则校正（合并时已采纳）：**

1. **M02**：不能简单把 consumer `.card` 改成四边 `page-gutter`（会双倍水平边距）。要对齐的是**布局模型**。  
2. **C01**：原「双标题重复」已消；残留是 `.hero` / `hero-sub` 结构，属收口而非复开旧 bug。  
3. **A01**：已有 200×200 空态/骨架，不是「完全没做」；补 `min-height` 防弱网/首屏高度不稳。  
4. **深底白字 / rgba(255,255,255,x)**：合法特效，抽 `--on-deep-opacity-*`，勿零容忍删光。

---

## 一、状态总表（合并后）

### 1.1 已关 / 可维持

| ID | 说明 |
|----|------|
| G01 | 三端品牌 `#0f766e` / `#134e4a` |
| G06 / G09 组件主体 | shared-uni empty/error + AppButton 主体 |
| G08 箭头 | 无字面量 `›`/`‹`，`app-link-chevron` |
| X05 | H5 `--phone-w: clamp(...)` |
| C08–C11 / C07 等 | 弹窗按钮、遮罩、resume 实心卡、tip token |
| A06 / A10 / A11 | 侧栏顶栏、暗色字、卡片 padding |
| A07（仅全局强制居中） | `main.css` 全局 `center !important` 已取消；**列级 align 仍开，见下** |
| M01 / M07 | 品牌对齐、TabBar `borderStyle` |

### 1.2 半到位（报告曾标已修，须打实）

| ID | 问题 | 正确理解 | Wave A |
|----|------|----------|--------|
| **M02** | consumer / merchant `.card` 用法曾不一致 | 已统一为「水平 gutter 在 page-body；`.card` 仅纵向 16rpx」 | **已打实** |
| **C01′** | `help.vue` 曾有 `.hero` + `hero-sub` | 已删除 hero 块 | **已打实** |
| **A01′** | `.qr-body` 无 `min-height` | 已加 `min-height: 240px` | **已打实**（admin 运行态待补验） |
| **A07′** | 大量 `align="center"` | DeviceDetail + 全库 `col-text` 冲突已清；裸居中债务 632 → T-B2 STRICT / Wave C | **冲突已打实** |
| **A05** | `label-width` 混用 | admin 源码已无 `NNpx`，统一 `auto` | **已打实** |
| **A04** | Tabs / breadcrumb 层级弱 | DeviceDetail 已加强 header/Tabs 层级 | **本页已打实** |

### 1.3 仍开（R2 + 复核）

| ID | 严重度 | 摘要 |
|----|--------|------|
| G03 / R2-G01 | P1 | 硬编码色（业务页为主；图表可单独色板） |
| G04 / R2-G02 | P1 | `nearby.vue` 等裸字号 |
| G08 / R2-C01 | P1 | 帮助中心单字圆标 |
| G09 / R2-C03 | P2 | 空态默认 `∅` |
| R2-M01 / R2-M02 | P1 | 商户工作台双空态 + 「未绑定」文案冲突 |
| R2-M03 | P1 | 商户详情/报表未用 `app-status` |
| X04 / R2-C06 / R2-M04 | P2 | 加载文案未尽 `UI_COPY` |
| R2-G04 / R2-M05 | P2 | 登录玻璃态 |
| G07 / R2-G05 | P2 | `.action-btn` 残留 |
| G10 / X01–X02 | P2 | a11y 未全覆盖 |

---

## 二、修改任务清单（按波次）

### Wave A — 打实「已修」标签（优先，约 1～2 天）

| 任务 ID | 内容 | 状态 | 触及文件 |
|---------|------|------|----------|
| **T-A1** | M02：两端 `.card` 仅纵向 `0 0 16rpx`；全局 `.page-body` 水平 gutter；devices/alerts 无 page-body 页补水平 margin | **已做** | 两端 `App.vue`、`devices.vue`、`alerts.vue` |
| **T-A2** | C01′：删除 help `.hero` / `hero-sub` | **已做** | `help.vue` |
| **T-A3** | A01′：`.qr-body { min-height: 240px }` | **已做** | `DeviceDetailView.vue` |
| **T-A4** | A07′+本页 A05：DeviceDetail 文本列去 `align="center"` 挂 `col-text`；金额 `col-money`；表单 `label-width`→`auto` | **已做** | `DeviceDetailView.vue` |

> Wave A 完成后用 Playwright 回归 consumer 帮助 / merchant 工作台·柜机；admin 需服务起来后目检 QR。

### Wave B — 工程化收口（约 2～3 天）

| 任务 ID | 内容 | 状态 | 触及文件 |
|---------|------|------|----------|
| **T-B1** | A05：全 admin `label-width="NNpx"` → `auto`；`main.css` 增 `--admin-form-label-width` | **已做** | 49 处 / 29 文件 + `main.css` |
| **T-B2** | A07：去掉 `col-text`+`align=center` 冲突（229）；eslint `local/no-col-text-align-center`；`pnpm check:admin-table-align`（债务 632 列 `align=center` 待 STRICT） | **已做** | 43 views + eslint + script |
| **T-B3** | A04：DeviceDetail page-header 分隔 + Tabs 间距/字重/active 色 | **已做** | `DeviceDetailView.vue` |
| **T-B4** | `--on-deep-opacity-50/78/88/92/95`；index/mine 深底白字迁 token | **已做** | `theme.css`、`index.vue`、`mine.vue` |

### Wave C — 用户可感知体验（与 R2 对齐）

| 任务 ID | 内容 | 状态 |
|---------|------|------|
| **T-C1** | 帮助中心单字图标 → SVG mask 图标 | **已做** |
| **T-C2** | 空态去掉 `∅`/单字，改 CSS 几何 glyph（shared + 两端副本） | **已做** |
| **T-C3** | 商户工作台：未绑定文案互斥；空态文案区分绑定态 | **已做** |
| **T-C4** | `device-detail` / `business` 接入 `app-status` + `onlineLabel` | **已做** |
| **T-C5** | `nearby.vue` 字号全部 `--font-size-*` | **已做** |
| **T-C6** | 加载文案 → `UI_COPY` / `loadingLabel()` | **已做** |
| **T-C7** | 硬编码色大清：`theme.css` 增 soft/chart/`--white`；`migrate-hex-tokens.mjs` 两轮共迁高频 hex；**`#fff/#ffffff` → `var(--white)` 238 处 / 60 文件**（裸白已清零）；定义行与 `var(..., #fallback)` 保留 | **已做** |
| **T-C8** | 商户/消费者登录去玻璃模糊，改实心底 | **已做**（商户完成；消费者同步） |
| **T-C9** | `.action-btn` → `app-btn-flex` | **已做** |

### Wave D — 补验与门禁（并行 / 随后）

| 任务 ID | 内容 | 状态 / 验收标准 |
|---------|------|----------|
| **T-D1** | Playwright 验收 admin：登录、设备列表、设备详情 QR、Dashboard | **已做**（见下表与附录；须 `node scripts/build-admin.mjs` 后 gateway 挂载新 static） |
| **T-D2** | a11y 抽检脚本（**先不做 95% 硬门槛**）：`pnpm check:mp-a11y`（默认 `MIN_ROLE_PCT=70`） | **已做**；实测 clickables 383/392（97.7%）、icons 35/37（94.6%） |
| **T-D3** | 更新本文件任务状态；Playwright 回归 consumer 首页/帮助、merchant 工作台 | **已做**（见附录截图；API 500 为后端未起，UI 本身正常） |

---

## 三、明确不做或降级（避免范围膨胀）

| 项 | 决定 |
|----|------|
| 强制一周内 a11y ≥95% CI | **降级**为 T-D2 抽检，阈值分期 |
| 图表色全部清零 | 单独 `--chart-*`，不与业务 token 混谈 |
| 把 consumer `.card` 直接改成四边 `page-gutter` | **禁止**（双倍 gutter）；走 T-A1 方案 A |
| 把 C01′ 写成「原 C01 完全没修」 | **禁止**；按结构收口跟踪 |

---

## 四、建议执行顺序（一句话）

```
T-A1 → T-A2 → T-A3 → T-A4 → T-B1/B3 → T-C3/C1/C2/C4 → T-C5/C6 → T-C7 → T-B2/B4 → T-C8/C9 → T-D1/D2
```

说「继续」时可收口提交；残留多为营销渐变特例色与 `var(..., #fallback)`，不必强清。

---

## 五、附录：关键证据索引

| 证据 | 位置 |
|------|------|
| consumer `.card` 仅下边距 | `clients/consumer-mp/src/App.vue`（含防双 gutter 注释） |
| merchant `.card` 四边 gutter | `clients/merchant-mp/src/App.vue` |
| help 无 hero-sub、SVG 入口图标 | `clients/consumer-mp/src/pages/help/help.vue`；截图 `wave-c-d-consumer-help.png` |
| qr-empty / `.qr-body` min-height | `DeviceDetailView.vue`；重建后实测 `min-height: 240px` |
| col-text 左对齐 | `main.css`；重建后 `td.col-text` → `text-align: left` |
| hex→token 迁移脚本 | `scripts/migrate-hex-tokens.mjs`（`pnpm migrate:hex-tokens`） |
| mp a11y 抽检 | `scripts/check-mp-a11y-coverage.mjs`（`pnpm check:mp-a11y`） |
| R2 Playwright 结论 | `docs/ui-audit-2026-09-12-r2.md` §五 |
| Wave C/D 回归截图 | 本地验收后已删除；结论见 T-D3 / Playwright 记录 |
| T-D1 admin 截图 | 本地验收后已删除；结论见上表（勿提交根目录 `td1-*.png`） |

### T-D1 Playwright 结论（2026-09-12）

| 步骤 | 结果 |
|------|------|
| 登录 `13900000001` + Redis captcha | PASS → `/admin/dashboard` |
| Dashboard | PASS（KPI/卡片可见） |
| 设备列表 | PASS（6 行；表头含设备编号/状态/…） |
| 设备详情 `330449777078` | PASS；页头「返回 / 在线 / 入库 / 已锁机」 |
| QR `.qr-body` | **重建前**旧 hash `min-height:0`；**`build-admin` 后** `DeviceDetailView-DDrT_xq7.css` → **`min-height: 240px`**，高度约 380px |
| `col-text` | **重建后**会话/订单号列 `text-align: left`（不再 `is-center` 冲突） |

说明：gateway 挂载 `services/trade-service/.../static/admin`；验收源码 UI 改动前须重建 admin。

---

*合并人：Cursor Agent · 2026-09-12；T-D1 完成同日*
