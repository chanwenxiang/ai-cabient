-- MIGRATION_REVIEWED: yes
-- MIGRATION_KIND: backfill（要货申请功能彻底下线：DROP 两表+清附件引用+删任务外键列；含 scheduled_task 种子行 1 条）
-- LOCK_RISK: low（DROP 两张小表；file_attachment 按引用类型定向 DELETE；scheduled_task 单行 INSERT ON CONFLICT）
-- TABLES: merchant_replenishment_request (-1) | merchant_replenishment_request_line (-1) | file_attachment (-rows) | scheduled_task (+1)
-- NOTES: 要货申请（merchant replenishment request）全链下线——旧系统 easygo 无此概念，职责与「采购入库」重叠，
--        且把日常补柜卡在申请审批弯路（SATELLITE_WAREHOUSE.md 口径：日常补柜由补货员从分仓自主发起，要货不是日常入口）。
--        用户拍板：连表 DROP，不留死表。补货任务（replenishment_task）及其权限（merchant:replenishment:view/request）
--        与本功能无关，全部保留。附件仅删要货两类引用行（OSS 对象文件保留，属可接受孤儿）。
--        同时登记「仓库月结自动生成」定时任务（账期制月结，见 docs/SATELLITE_WAREHOUSE.md「月结对齐财务口径」节）。

-- ── 1. 清理要货证据附件引用（PENDING_* 为商户暂存态，REPLENISHMENT_REQUEST 为已绑定态）────────
DELETE FROM file_attachment
WHERE ref_type IN ('PENDING_REPLENISHMENT_REQUEST', 'REPLENISHMENT_REQUEST');

-- ── 2. 补货任务解挂（request_id 外键指向主表，须先删 FK/列再 DROP，否则 DROP 被引用表报 depend）──
ALTER TABLE replenishment_task
    DROP CONSTRAINT IF EXISTS replenishment_task_request_id_fkey;
ALTER TABLE replenishment_task
    DROP COLUMN IF EXISTS request_id;

-- ── 3. DROP 要货两张表（先明细后主表；行上的外键/索引随表删除）────────────────────────────
DROP TABLE IF EXISTS merchant_replenishment_request_line;
DROP TABLE IF EXISTS merchant_replenishment_request;

-- ── 4. 登记仓库月结自动任务（7 处同步之 ⑥）───────────────────────────────────────────────
-- 每日 02:10 幂等检查：上一已结束账期（26 日 00:00 ~ 25 日 23:59，标签=结束月）若尚未生成草稿则生成。
-- 账期实盘由 10 日导入的上月盘点数据回填；26 日补货数据导入完成后流水齐套（详见 SATELLITE_WAREHOUSE.md）。
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark)
VALUES ('warehouse-monthly-close', '仓库月结自动生成', 'WAREHOUSE', '每日 02:10',
        '账期制(26~25)月结草稿幂等生成：期初=上期期末实盘结转，差异自动计算，不扣款')
ON CONFLICT (task_key) DO NOTHING;
