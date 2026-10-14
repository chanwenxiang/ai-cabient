-- MIGRATION_KIND: backfill（DDL + 出账任务种子行随迁移登记）
-- COMPETITOR_REF: N/A(押金按月退还=对齐老系统 OptMachineRentCashGuaranteeRecord 生命周期，
-- 2026-10-14 用户拍板推翻 CB-034「不采纳按月退还」决策；所有金额/月数参数不写死、登记时可填)
-- 老系统模型：收取=从商户余额扣；第 refund_start_month(3) 个月起每月固定退 cashReturnUnitAmount；
-- 退完(receiptStatus=true)当月发期满奖励(老系统仅¥2000档¥200，新系统登记时可填、默认0)；
-- 全部进出商户余额。新系统差异（有意）：档位/月退额/起始月/奖励额全部登记时按柜填写（不写死）；
-- 存量线下收缴登记行 charge_mode=OFFLINE 不参与自动退还（避免双退），仅新登记行 ONLINE。
-- charge_mode: ONLINE=线上收取(真扣余额,参与按月退还) / OFFLINE=线下收缴登记(存量,不自动退还)
-- monthly_refund_cents: 每月退还额(分,登记时按柜填写,null=不自动退还)
-- refund_start_month: 自登记月第 N 个月开始退(默认3,登记时可改)
-- expire_reward_cents: 期满奖励(分,登记时可填,默认0)
ALTER TABLE device_rental_deposit
    ADD COLUMN IF NOT EXISTS charge_mode VARCHAR(16) NOT NULL DEFAULT 'ONLINE',
    ADD COLUMN IF NOT EXISTS monthly_refund_cents BIGINT NULL,
    ADD COLUMN IF NOT EXISTS refund_start_month INT NULL,
    ADD COLUMN IF NOT EXISTS expire_reward_cents BIGINT NULL DEFAULT 0;

-- 存量行均为线下收缴登记（收据化），线上自动退还只对新登记行生效
UPDATE device_rental_deposit SET charge_mode = 'OFFLINE';

INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('deposit-monthly-refund', '押金按月退还（对齐老系统）', 'TRADE', '每月 1 日 01:30',
 '每月 1 日遍历未结清押金（charge_mode=ONLINE、月退额>0）：自登记月第 refund_start_month 个月起每月退 monthly_refund_cents 入商户余额（refId=押金+月序 幂等），退完置结清并按 expire_reward_cents 发期满奖励；金额参数登记时按柜填写不写死')
ON CONFLICT (task_key) DO NOTHING;
