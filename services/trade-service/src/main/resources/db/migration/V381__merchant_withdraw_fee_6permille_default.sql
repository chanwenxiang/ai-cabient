-- MIGRATION_KIND: backfill
-- 商户提现手续费默认档 0 → 60 bps（6‰）——2026-10-16 用户拍板「提现手续费我们也要收商户的，
-- 不写死、可按商户配」，费率档取老系统标准 6‰（ego-automat `ConstantUtils.DEDUCTION_PER_MILLAGE_RATE=6`）。
--
-- 为什么这是「补一个默认值」而不是「改一次定价模型」：
--   · 扣法早已与老系统同形——申请额全额出账（`MerchantWalletService.consumeFrozenSplit` 一次扣 net+fee，
--     记 WITHDRAW_PAID -net 与 WITHDRAW_FEE -fee 两行），到账=申请额−手续费，差额留平台侧。
--   · 按商户覆盖也早已可用：`WithdrawPolicyResolver.merchantFeeBpsFor` 先读
--     `merchant.withdraw.fee_bps.{merchantId}`，没配才回退本 key。
--   · 唯一缺口就是<b>全局种子是 0</b>（运营不改就等于没收）⇒ 只翻这一个默认值。
--   · 封顶键 `merchant.withdraw.fee_cap_cents` 保持 0=不封顶：本仓已有单笔下限 ¥10 /
--     单笔上限 ¥10000 / 单日 ¥40000 三重天花板，6‰ 在 ¥10000 上最多 ¥60，
--     配 ¥25/¥50 封顶几乎永不触发=死参数（支付宝/拉卡拉没有单笔上限才必须封顶）。
--
-- 为什么不会误伤运营的主动选择：WHERE 只在<b>值仍是旧种子 0</b> 时翻档——
-- 运营若已按合同配过非 0 值（含按商户覆盖 key）一概不动。
-- 本项目尚未上线（dev 现网 fee_bps 仍为默认 0，2026-10-16 实测），不存在历史计费口径需要迁移。
--
-- 说明文案同步刷新：让运营台「系统参数」页看到 6‰ 的真实语义与覆盖入口，
-- 避免被 WithdrawFeeCalculator 注释里的「有 bps 强烈建议配封顶」带偏成死配置。

-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: system_config (~200 行)
-- NOTES: system_config 不在 check-migration-safety 的 HOT_TABLES；两条 UPDATE 各命中 1 行主键，
--        无 ALTER/索引。应用侧 Java 种子（SystemConfigService.ensureDefaults）同步改为 60，
--        全新空库由 Flyway 建表 + 首启种子直接得到 60，本迁移在服务已有库上补齐。
-- ROLLBACK: UPDATE system_config SET config_value='0' WHERE config_key='merchant.withdraw.fee_bps'
--           （回滚=恢复「默认免收」，按商户覆盖 key 不受影响）

UPDATE system_config
   SET config_value = '60',
       description = '商户提现手续费比例（万分比，60=6‰，2026-10-16 拍板对齐老系统标准档）；'
                     || '个别商户可用 merchant.withdraw.fee_bps.{商户号} 覆盖；0=免收',
       updated_at = NOW()
 WHERE config_key = 'merchant.withdraw.fee_bps'
   AND config_value = '0';

UPDATE system_config
   SET description = '商户提现手续费单笔封顶（分），0=不封顶（2026-10-16 拍板留 0：单笔上限 ¥10000/'
                     || '单日 ¥40000 已是天花板，6‰ 最多 ¥60，配 ¥25/¥50 封顶几乎永不触发=死参数；日后抬上限再配）',
       updated_at = NOW()
 WHERE config_key = 'merchant.withdraw.fee_cap_cents';
