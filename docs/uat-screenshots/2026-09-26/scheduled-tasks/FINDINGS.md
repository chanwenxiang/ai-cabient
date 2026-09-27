# 定时任务 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`SCHEDULED_TASKS_FULL_BROWSER_UAT.md`](../../../uat/SCHEDULED_TASKS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0 · SKIP 1（行开关硬写未拨）**

列表共 **32**。关键词无命中「暂无定时任务」后重置。新增/编辑（任务标识 disabled）/立即执行「补偿任务处理」/批量停用/批量执行均→**取消**。本环境无自定义任务可删（内置不可删）。行内启停开关可见但**未拨**（无确认即写库）。导出 CSV 成功。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

## 数据对照

| 面 | UI |
|----|-----|
| 全部 | 共 **32** |
| 补偿任务处理 | 每 30 秒 · 成功 |
| 优惠券过期 | 每日 02:00 |

## 本轮缺陷

无 FAIL / FINDING。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 行内 `el-switch` 无二次确认、直接 `PUT …/enabled` → 软写 UAT **禁止拨动**。 |
| 2 | 内置 `registryBound` 不可删除；删除仅自定义元数据任务。 |
| 3 | 「立即执行」与「批量执行」均有 MessageBox，可取消。 |
| 4 | 本页无导入/下载模板。 |

## 证据

`st-01`…`st-11` · `st-ux-*` · `st-99-end`
|
