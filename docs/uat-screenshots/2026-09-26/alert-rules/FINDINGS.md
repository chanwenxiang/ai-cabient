# 告警规则 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`ALERT_RULES_FULL_BROWSER_UAT.md`](../../../uat/ALERT_RULES_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0 · SKIP 1（测试发送）**

列表共 **25**。关键词无命中「暂无告警规则」后重置。新增→诚实 toast「白名单键均已存在，请直接编辑列表项」（无弹窗）。编辑配置键 disabled→**取消**。自定义键删除确认→**取消**。勾选内置后批量删除→诚实 toast（内置不可批删）。「测试发送」可见但**未点**（会打 Webhook）。导出 CSV 成功；无导入/模板。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

## 数据对照

| 面 | UI |
|----|-----|
| 全部 | 共 **25** |
| 示例 | 设备离线锁机 10 分钟 · auto_unlock 开 · 温控 8℃ · 争议 SLA 48h |

## 本轮缺陷

无 FAIL / FINDING。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 与参数配置同源；仅白名单键可「新增」；满员时 info toast，非缺陷。 |
| 2 | 行删除仅 `isCustomKey`（非 BUILTIN_GROUPS）可见；本环境如 `order.unpaid.auto_blacklist`。 |
| 3 | 「测试发送」会调用 `systemConfigAlertTest` 打渠道 Webhook → 软写 UAT **禁止点击**。 |
| 4 | 本页无导入/下载模板。 |

## 证据

`ar-01`…`ar-10` · `ar-ux-*` · `ar-99-end`
|
