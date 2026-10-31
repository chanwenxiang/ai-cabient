# 造数脚本（module-audit 共享）

> 纪律源：[../README.md](../README.md)「造数纪律」＋ [../PLAN.md](../PLAN.md) 工作法第 5 步。
> **每个脚本必须成对**：`Xxx.sql`（插入 + 自证计数）与 `Xxx-cleanup.sql`（删除 + 断言 0）。只留插入脚本的批次不算收口。

## 跑法（本地 dev 栈，库 `aicabinet` @ 127.0.0.1:15433）

```bash
# 插入（-i 必带，否则 docker exec 读不到 stdin 的 heredoc/管道，静默 0 行）
docker exec -i ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet \
  -v ON_ERROR_STOP=1 -f - < docs/module-audit/seeds/R01-user-behavior-dormant.sql

# 清理 + 断言三个 0
docker exec -i ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet \
  -v ON_ERROR_STOP=1 -f - < docs/module-audit/seeds/R01-user-behavior-dormant-cleanup.sql
```

## 三条硬规矩

1. **前缀**：所有造出来的 ID/编号/姓名必须带 `AUDIT-`（本目录脚本用 `AUDIT-R1-*`、user_id 走 `1990000000+g` 合成段），否则清理脚本按前缀删不干净。
2. **自证 + 恢复双打印**：插入脚本末尾 SELECT 三个 count，清理脚本同样 SELECT（期望全 0）。🔴 光看 SQL 计数不够——**再重打一次被测 API**，确认业务口径回到基线（例：`GET /api/v2/ops/admin/growth/user-analysis?days=30` 的 `dormantUsers30d/totalUsers`）。
3. **只碰合成实体**：不 UPDATE/DELETE 真实数据；需要「已有数据不满足判据」时，宁可新建带前缀的行，也不改既有行。

## 已知可复用的坑位知识

- `cabinet_order` 的触发器 `assert_cabinet_order_inventory_link`：`created_at < 2026-09-10 00:00:00+08` 整体跳过断言；且**没有 order_line 时直接放行**。⇒ 造「只有历史订单、不动库存」的样本，最省事的做法是不建 `cabinet_order_line`（R01 脚本就是这么写的），不必回填到 cutoff 之前。
- `shopping_session.state='COMPLETED'` + `cabinet_order.status='PAID'` 是行为分析读得到的最小组合。
- 沉睡用户窗口＝末单落在 **[今天-90, 今天-30)**（`UserBehaviorAnalyticsService`，CB-081 固定窗口口径）⇒ 脚本用 `now() - interval '45 days'`，任何一天跑都落在窗口内。
