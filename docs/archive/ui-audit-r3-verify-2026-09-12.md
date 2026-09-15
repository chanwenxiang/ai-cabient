# ai-cabinet 三端 UI 验证报告（第四轮：R3 整改验证 + 收口）

> 验证时间：2026-09-12 18:48
> 验证方式：源码逐项核验 + 门禁脚本实跑（`check:admin-table-align` / `check:mp-a11y`）
> 对照基线：`docs/ui-audit-r3-2026-09-12.md`（第三轮，6 项修复清单）
>
> **结论：6/6 项全部验证通过。其中 3 项超额完成。本轮无新增 P1/P2 问题，三端 UI 债务收口至 P3 级。**

---

## 一、R3 清单逐项验证结果

### 1. R3-A01 admin 登录卡去玻璃态 —— ✅ 已修复（超额）

| 验证点 | 结果 |
|--------|------|
| `.login-card` 主卡 | `background: rgba(8, 24, 30, 0.82)` + `backdrop-filter: none`（L752-753），附 R3-A01 注释 |
| 输入框 | `backdrop-filter: none`（L822） |
| 页脚/reset 弹窗等次级面 | **额外** 4 处全部改为 `backdrop-filter: none` + 实心底（L895、L997、L1008、L1044、L1066） |
| 全库 blur 残留 | 仅剩 `ChartBox.vue:337` 图表浮动 tip 的 `blur(8px)`——深色 tooltip 上加微模糊属**可接受的特效层**，非登录卡玻璃态；可豁免或下轮顺手清 |

### 2. R3-M01 merchant 补货状态徽章变体 —— ✅ 已修复（超额）

`replenishment.vue:2570-2594` 四个状态变体全部补齐，且默认样式语义已校正：

```css
.status            /* 默认=PENDING：警告橙，注释明确「避免 CANCELLED 误用警告色」 */
.status.pending    /* 警告橙（显式） */
.status.in_progress /* 品牌绿 color-mix */
.status.completed  /* 品牌绿 soft */
.status.cancelled  /* 石板灰 color-mix —— 原警告色误用已消除 */
```

注释中说明了默认样式归属逻辑，后续加状态不会回退。此前 R3 提出的「徽章仅颜色单通道」问题：状态文字仍由 `displayLabel()` 输出完整文案（`replenishment.vue:147`），颜色+文字双通道满足。

### 3. R3-A02 金额列 `col-money` 配对 —— ✅ 已修复（超额）

| 指标 | R3 时 | 本轮 |
|------|-------|------|
| `class-name="col-money"` | 24 | **137**（26 个 view） |
| 金额语义 label 未配对 | 79 | **0**（门禁实跑确认） |
| 配对写法 | 仅 `class-name` | `class-name` + `label-class-name` 双挂（表头/表体同步右对齐） |

重灾文件全部覆盖：`SalesReportsView`(20)、`LineManagerView`(18)、`MerchantWithdrawView`(16)、`OrderListView`(14)、`WarehouseView`(10)、`FinanceView`(9)、`MarketingRoiView`(8)、`FootfallView`(8) 等。

### 4. R3-A03 z-index 分层 token —— ✅ 已修复

`main.css:72-78` 新增完整分层：

```css
--z-sticky: 10;  --z-map-control: 500;  --z-dropdown: 1000;
--z-fixed-tip: 4000;  --z-context-menu: 5000;
--z-fullscreen: 9999;  /* 存量 BigScreen；语义层 ≥9000 */
--z-skip-link: 10000;
```

存量高值全部挂 token（数值不变）：`BigScreenView:605`、`AdminLayout:1158`、`ChartBox:327`、`DeviceMapView`×3、`main.css:23` skip-link。全库扫描 `z-index: NNN(≥100)` 裸写已清零（布局内部 2/3/30 微调值合理保留）。新代码可直接引用 token。

### 5. R3-C02 color 类裸白透明度迁移 —— ✅ 已修复

全量扫描两端 `color: rgba(255,255,255,0.x)`：从 31 处降到 **4 处**，且逐一核验后均属豁免类：

| 位置 | 值 | 性质 |
|------|-----|------|
| consumer `index.vue:3019` | `border-color: rgba(255,255,255,0.16)` | 边框，theme.css 豁免范围 |
| consumer `index.vue:3079` | `border-color: rgba(255,255,255,0.4)` | 边框，豁免 |
| merchant `settlements.vue:487` | `color: rgba(255,255,255,0.8)` | 深绿渐变卡（brand→brand-deep 实心底）上的次要文字，0.8≈token 0.78 档，**建议顺手换 `var(--on-deep-opacity-78)`**，P3 |
| merchant `settlements.vue:496` | `color: rgba(255,255,255,0.7)` | 同卡负数金额文字，P3 |

`theme.css:81` 已按 R3 建议补注释：**「豁免：border / box-shadow / 渐变端点可继续裸写 rgba(255,255,255,0.x)」**，规则边界清晰。

### 6. R3-X01 a11y 尾量 + 门槛收紧 —— ✅ 已修复（超额）

| 指标 | R3 时 | 本轮（实跑） |
|------|-------|------|
| clickables with role/aria | 383/392 (97.7%) | **392/392 (100%)** |
| icons with aria-hidden/label | 35/37 (94.6%) | **37/37 (100%)** |
| `MIN_ROLE_PCT` 门槛 | 70 | **90**（脚本注释标注「R3-X01 从 70 收紧」） |

两率直接打到 100%，门槛抬至 90，比原计划（先清尾量、抬 90）一步到位。

---

## 二、门禁脚本实跑记录（本轮全部通过）

```
[admin-table-align] col-text+align=center conflicts: 0
[admin-table-align] bare align=center (debt): 0        ← R3 时为 632
[admin-table-align] money-label without col-money: 0   ← 新增检查项
[admin-table-align] ok

[mp-a11y] clickables with role/aria: 392/392 (100%)
[mp-a11y] icons with aria-hidden/label: 37/37 (100%)
[mp-a11y] threshold MIN_ROLE_PCT=90
[mp-a11y] ok
```

两点值得肯定：

1. **632 处裸 `align=center` 清到 0**，且方式正确——不是粗暴删居中，而是引入 `col-status` 语义类（`main.css:591-602`，居中 + 门禁豁免 + 注释说明），状态/枚举/数量列保留居中，文本列走 `col-text` 左对齐。抽查 `DisputeListView`「状态」列：`align="center" + class-name="col-status"` 结构规范。
2. **门禁加了第三条检查**（`money-label without col-money`，`check-admin-table-align.mjs:39`），R3 建议的「金额列未配对即提示」已落地，STRICT_MONEY 开关保留。

---

## 三、本轮新发现（仅 P3 级，不挡收口）

| 编号 | 问题 | 位置 | 建议 |
|------|------|------|------|
| R3-P3-1 | `ChartBox` 浮动 tip 仍有 `backdrop-filter: blur(8px)` | `ChartBox.vue:337` | 深色 tooltip 微模糊可豁免；如要彻底统一，删该行即可 |
| R3-P3-2 | merchant `settlements.vue` 渐变结算卡上 2 处 color 类裸白（0.8/0.7） | `settlements.vue:487,496` | 顺手换 `var(--on-deep-opacity-78)` / 就近档 |
| R3-P3-3 | consumer `index.vue` landing-error 卡 `border-color` 裸白 2 处 | `index.vue:3019,3079` | 属 theme.css 已声明的豁免类，可不动；如追求归一可抽 `--on-deep-border` |

以上均为写法归一性质，无视觉/语义缺陷。

> **收口更新（同日 19:01）**：P3 尾巴已顺手清完——
> 1. `ChartBox.vue` blur(8px) 已删；
> 2. `settlements.vue` 2 处 color 裸白已换 `var(--on-deep-opacity-78)`；
> 3. consumer `index.vue` 2 处 border-color 裸白维持豁免不动（theme.css 规则内）；
> 4. `MIN_ROLE_PCT` 已再收紧 **90 → 95**，实跑仍 100% 全绿。
> 此后三端 UI 无待办，剩余仅为日常维护约定（禁裸 hex / z-index 走 token / 新列挂语义 class）。

---

## 四、总评

| 维度 | 结论 |
|------|------|
| R3 修复清单（6 项） | **全部验证通过**，3 项超额（a11y 100%、金额列配对 137 处、blur 全清） |
| 门禁脚本 | 三条检查全绿；门槛已收紧（a11y 90）；STRICT/STRICT_MONEY 开关就绪 |
| 新增 P1/P2 | **无** |
| 三端 UI 债务 | 收口至 P3（写法归一 + 可选 STRICT），**建议本轮关闭** |

后续维护约定（沿用并强化既有报告）：
- 新代码禁止裸 hex（业务色）、禁止裸 `z-index` 数字（用 `--z-*`）、新表格列必须挂语义 class
- `pnpm check` 已覆盖表格对齐 + a11y 两条门禁，CI 走 `pnpm check` 即可
- P3 尾巴（R3-P3-1~3、STRICT_MONEY 开启、a11y 抬 95%）留给日常顺手清，不再单开任务

---

*审核人：WorkBuddy · 2026-09-12 18:48 · 6/6 通过，建议关闭本轮 UI 整改*
