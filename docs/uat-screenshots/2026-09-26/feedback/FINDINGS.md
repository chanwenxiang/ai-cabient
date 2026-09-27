# 用户反馈 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`FEEDBACK_FULL_BROWSER_UAT.md`](../../../uat/FEEDBACK_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0**

列表共 **8**，与 `GET /api/v2/ops/feedback` 一致（本机均为待处理·建议）。hint 明示回复仅为运营备注、不推送用户。状态筛中文；`?status=PENDING` 深链与筛同步。回复弹层→**取消**；删除 MessageBox→**取消**。用户链 `10001`→用户余额；设备链 `CAB-001`→设备详情。导出 CSV 成功。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

## 数据对照

| 面 | UI / API |
|----|----------|
| 全部 | 共 **8** · 均 PENDING / SUGGESTION→建议 |
| 待处理筛 | 共 **8** · `?status=PENDING` |
| 样例 | #1… 用户 `10001`；含设备 `CAB-001` |

## 本轮缺陷

无 FAIL / FINDING。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 本地按反馈编号升降序（`sort.mode=local`）；API 默认序可能不同，以编号为准。 |
| 2 | 仅 `PENDING` 行显示「回复」；删除始终可见（有权限时）。 |
| 3 | 回复成功文案强调「仅运营备注，未推送用户」——产品口径。 |
| 4 | C 端「我的反馈」：见 [`MINIPROGRAM_BACKLOG.md`](../../../uat/MINIPROGRAM_BACKLOG.md)。 |

## 证据

`fb-01`…`fb-11` · `fb-09b-device-link` · `fb-ux-*` · `fb-99-end`
|
