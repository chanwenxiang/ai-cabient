# 消息记录 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`NOTIFICATIONS_FULL_BROWSER_UAT.md`](../../../uat/NOTIFICATIONS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 2 · BLOCK 0**

列表共 **2**，与 `GET /api/v2/ops/admin/growth/notifications?page=0&size=20` 一致。发送站内信弹层（消费者↔商户字段切换）→**取消**；编辑→**取消**；行删 MessageBox→**取消**；批删 MessageBox→**取消**。导出 CSV 成功。窄视口 900 无横滚；结束 **1366×768 DPR=1**。未提交任何写操作。

## 数据对照

| 面 | UI / API |
|----|----------|
| 全部 | 共 **2** |
| #1 | audience=`OPS` · biz=`MERCHANT_REPLEN_REQUEST` · 审批提醒要货 |
| #2 | audience=`CONSUMER`→消费者 · biz=`OPS_MANUAL`→运营手工 · 「完整轮站内信」 |

## 本轮缺陷

| ID | 严重度 | 现象 | 根因 | 必须怎么做 |
|----|--------|------|------|------------|
| FINDING-1 | 中 | 受众列显示裸码 **OPS**（非中文） | `audienceLabel()` 仅映射 CONSUMER/MERCHANT，其它回退原串 | 补「运营」等映射；或 dict；禁止前台裸英文码 |
| FINDING-2 | 中 | 业务列 #1 显示「未知」 | `notification_biz_type` dict 无 `MERCHANT_REPLEN_REQUEST`（该码在别的 dict） | 将要货等 biz_type 补进 `notification_biz_type`，或映射到已有「补货/商户通知」 |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 本页无关键词筛；服务端 `page/size` 分页；本地可按 ID 升降序。 |
| 2 | 发送仅支持消费者（用户ID）/商户（商户编号）；系统 OPS 信不经发送弹层创建。 |
| 3 | 关联单号 #1 显示裸 `1`（`displayBizNo` 对纯数字未改写）——可后续美化，非本轮 FAIL。 |
| 4 | C 端收信展示：见 [`MINIPROGRAM_BACKLOG.md`](../../../uat/MINIPROGRAM_BACKLOG.md)。 |

## 证据

`ntf-01`…`ntf-10` · `ntf-ux-*` · `ntf-99-end`
|
