# S3-D · 客服与运维壳（软写 · 2026-09-27）

消费者 `13800138000` · 柜 `166813762350`。H5 `/consumer/` 未挂载 → 页 UI SKIP，走 API + Admin。

## S3-D1 故障报修 / 反馈

| 步骤 | 结果 |
|------|------|
| `POST /feedback` `{feedbackType:SUGGESTION, content}` | feedbackId **13** · **PENDING** |
| `GET /feedback/mine` | 同单可见 |
| Admin `/admin/feedback` | 待处理意见可见（软，未回复） |
| `POST /devices/{id}/fault-report` `{issueType:DOOR_OPEN}` | reportId **6** |
| Admin `/admin/exceptions` | 待处理「消费者设备报修」· 柜 166813762350 · 用户 10001 |

截图：`s3d-feedback.png` · `s3d-exceptions.png`

字段铁律：feedback 用 `feedbackType`；报修用 `issueType`（如 `DOOR_OPEN`），不是 category/faultType。

## S3-D2 Live 购物车

| 步骤 | 结果 |
|------|------|
| — | **SKIP**（本轮未开 Live 会话；S2 购物已覆盖结算主链） |

## S3-D3 帮助 / 条款 / 导出

| 步骤 | 结果 |
|------|------|
| `GET /public/help` `/public/policies` | **404**（无公开 API 或路径变更） |
| 导出 | **SKIP**（Admin 导出属各业务页，S2/S3-C 已点过刷新/列表） |

## 结论

| ID | 结果 |
|----|------|
| D1 | **PASS**（软写 API + Admin 可见） |
| D2 | **SKIP** |
| D3 | **PARTIAL**（帮助/条款 API 404；未深测静态页） |
