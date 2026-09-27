# 录像上传 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`UPLOAD_QUEUE_FULL_BROWSER_UAT.md`](../../../uat/UPLOAD_QUEUE_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

本页为设备自动上传**状态队列**（非人工上传）。本机 `state=WAITING_UPLOAD` 共 **0**，空态与「仅滞留」空态文案诚实；筛选/深链/别名路由/状态下拉中文均通过。结束视口 **1366×768**。

## 数据对照

| 筛 | UI | API |
|----|-----|-----|
| 默认 WAITING_UPLOAD | 共 **0** | total=0 |
| 仅滞留 | 共 0 · 专用空态 | stuckOnly total=0 |
| uploadStatus=FAILED | 共 0 | total=0 |
| 无匹配 q | 共 0 | total=0 |

对照：全量会话列表另有数据（本机约 57），说明队列过滤 `WAITING_UPLOAD` 生效，非接口全空。

## 本轮缺陷

无 FAIL。无需改码。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 本页**无新增/删除/重试写路径**；「播放」需有 `videoUri` 行，空队列 SKIP。 |
| 2 | 深链键：`keyword`/`q`/`deviceId`/`sessionId`/`stuck`/`uploadStatus`。 |
| 3 | 滞留 SLA=**30** 分钟，与工作台「录像滞留」一致。 |
| 4 | 历史路由 `/videos`、`/uploads` 重定向到 `/upload-queue`。 |
| 5 | 行会话/设备深链、播放须有队列数据才能点测；本轮用 CAB-001 / 真实 sessionId 验深链回显。 |

## 证据

`uq-01`…`uq-20` · `uq-ux-*` · `uq-99-end`
|
