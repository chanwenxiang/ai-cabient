# 手机验证流水 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`PHONE_VERIFY_FULL_BROWSER_UAT.md`](../../../uat/PHONE_VERIFY_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0**

列表共 **81**，与 `GET /api/v2/ops/admin/phone-verify/logs` 一致。渠道字典中文四档（短信验证码/短信重置密码/微信/支付宝）。手机号空态诚实；`13800138001` 命中共 5。登记/编辑弹层→**取消**；删除 MessageBox→**取消**（无硬写）。导出 CSV 成功。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

## 数据对照

| 面 | UI / API |
|----|----------|
| 全部 | 共 **81** |
| 手机 `13800138001` | 共 **5** |
| 样例 | logId 81 · SMS · userId 100000030 |

## 本轮缺陷

无 FAIL / FINDING。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **软写**：登记/编辑仅测到弹层取消；删除仅测到确认取消。 |
| 2 | 列表手机号本机为明文（如 `13800138001`），非脱敏——产品/权限口径；客诉审计页常见，不作 FAIL。 |
| 3 | 筛选区无「重置」按钮（与用户余额不同）；清条件靠清空输入 + 渠道 clearable。 |
| 4 | 侧栏/页签标题为「手机验证」，页内 title 为「手机验证流水」。 |
| 5 | C 端验证入口回补：见 [`MINIPROGRAM_BACKLOG.md`](../../../uat/MINIPROGRAM_BACKLOG.md)（若有绑定手机流程）。 |

## 证据

`pv-01`…`pv-11` · `pv-ux-*` · `pv-99-end`
|
