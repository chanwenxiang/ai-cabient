# 用户余额 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`USERS_FULL_BROWSER_UAT.md`](../../../uat/USERS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0**

列表共 **15**，与 `GET /api/v2/ops/admin/users` 一致。余额分→元（`¥x.xx`）；实名「已实名/未实名」、角色中文。关键词空态诚实；userId 命中与 `?keyword=` 深链一致。调整余额弹层「变动金额（元）/调整原因」→**取消**（未进二次确认、未 POST）。未实名行「核验实名」MessageBox →**取消**。导出 CSV 成功。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

## 数据对照

| 面 | UI / API |
|----|----------|
| 全部 | 共 **15** |
| userId `100000039` | 共 **1**（商户补货员 · ¥0.00 · 已实名） |
| 样例余额 | `10001` 陈晓 **¥193.00**（已实名） |
| 未实名 | 如 userId `0` / `10002` 可见「核验实名」 |

## 本轮缺陷

无 FAIL / FINDING。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **软写**：调账仅测到弹层取消；真调账需二次确认，本轮禁止。 |
| 2 | 核验 MessageBox 标题含 userId（如「核验用户 0」）；姓名可留空——产品口径，非缺陷。 |
| 3 | 关键词按手机号 / 姓名 / 用户ID 分类；本轮用纯数字 userId。 |
| 4 | 消费者端余额可见性：见 [`MINIPROGRAM_BACKLOG.md`](../../../uat/MINIPROGRAM_BACKLOG.md) §1（用户余额 P0）。 |

## 证据

`usr-01`…`usr-10` · `usr-ux-*` · `usr-99-end`
|
