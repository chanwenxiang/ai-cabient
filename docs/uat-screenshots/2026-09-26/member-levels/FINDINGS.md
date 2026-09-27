# 会员等级规则 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`MEMBER_LEVELS_FULL_BROWSER_UAT.md`](../../../uat/MEMBER_LEVELS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 1 · BLOCK 0**

列表共 **4**，与 `GET /api/v2/ops/admin/growth/member-levels` 一致：普通/白银/黄金/铂金（编码中文展示名）。hint 明示修改后即时生效。新建等级弹层→**取消**；编辑弹层编码 **disabled**→**取消**。勾选后批量停用 MessageBox「确认批量停用选中的 1 条等级规则？」→**取消**。行「停用」可见但因无二次确认 **未点**（FINDING-1）。工具仅刷新/列设置，无导出。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

## 数据对照

| 面 | UI / API |
|----|----------|
| 全部 | 共 **4** · 均 ACTIVE |
| 普通 | NORMAL · 普通会员 · ¥0.00 ~ ¥1000.00 · 倍率 1 |
| 白银 | SILVER · 银卡会员 · ¥1000.00 ~ ¥5000.00 |
| 黄金 | GOLD · 金卡会员 · ¥5000.00 ~ ¥10000.00 |
| 铂金 | PLATINUM · 白金会员 · ¥10000.00 ~ + |

## 本轮缺陷

| ID | 严重度 | 现象 | 根因 | 必须怎么做 |
|----|--------|------|------|------------|
| FINDING-1 | 中 | 行「启用/停用」无二次确认，一点即 POST status | `toggleStatus()` 无 `ElMessageBox`（`batchSetStatus` 有确认） | UAT **禁止**盲点行启停；产品应补确认 |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 列表接口无分页：整表拉取后前端切片；本页无关键词筛。 |
| 2 | 编辑时 `levelCode` 禁用（内部编码不可改）；展示名由 dict `member_level` 映射。 |
| 3 | 本页无 CSV 导出（仅刷新/列设置）——产品口径，非缺陷。 |
| 4 | 批量启停对「已是目标状态」的行会 toast「选中项均已启用/停用」并跳过。 |
| 5 | C 端会员俱乐部展示：见 [`MINIPROGRAM_BACKLOG.md`](../../../uat/MINIPROGRAM_BACKLOG.md)。 |

## 证据

`ml-01`…`ml-07` · `ml-ux-*` · `ml-99-end`
|
