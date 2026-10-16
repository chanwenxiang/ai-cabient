-- MIGRATION_KIND: backfill
-- 平台分账费率 platform_rate_bps 归 0——2026-10-17 用户拍板「不确定有没有这笔费用，先配置为 0、先不收」
-- （日后要收：运营台「商户与分账 → 平台抽成（%，0=不抽成）」显式填，或按商户谈档位）。
--
-- 为什么连存量行一起翻，而不是只改列默认值：
--   列默认值只在 INSERT 缺列时生效，现网 2 家（2026-10-17 只读实测：MCH-DEFAULT=1000、
--   MCH-OTHER=1500）仍会按 10%/15% 抽成 ⇒ 只改默认值等于「先不收」没落地。
--   真正写库的那一份默认是 Java 实体（Merchant.platformRateBps），本次同步改 0；
--   MerchantService.doUpsertMerchant 的缺省、运营台新建商户表单同理。
--
-- 为什么历史 order_revenue_split 行**不回改**：
--   那三列记的是**当时**的口径，且已经跟着资金动作走过（LEDGER_ONLY 已入商户钱包 /
--   WECHAT_SUBMITTED 已提交微信）。回改=篡改已发生的资金事实，对账会多出无解释的差额。
--   改单/退款走 RevenueSplitService.resyncSplitForOrder，按当前费率（0）自然重算。
--
-- 0 的下游语义（本批代码同步落地，不是「算了个 0 放着」）：
--   0 抽成 ⇒ receiver.amount 等于订单毛额。微信服务商**是否允许全额分账未实证**
--   （CB-032 记的「服务商默认单订单分账比例上限 30%」只有 ⚪ 旁证），所以 0 抽成的单
--   **一律不发**分账请求：新建/重算直接落 LEDGER_ONLY，商户份额整笔入可提现钱包；
--   WeChatProfitSharingService.prepareSubmit 另在出口处兜一道（0 抽成 = 终态不发 HTTP），
--   挡住运营手工「提交分账」与失败重试这两个入口。

-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: merchant (2 行)
-- NOTES: merchant 不在 check-migration-safety 的 HOT_TABLES。ALTER COLUMN ... SET DEFAULT 只改
--        目录里的默认值（不重写行、不校验存量），ACCESS_EXCLUSIVE 为毫秒级；UPDATE 命中 2 行主键；
--        COMMENT ON COLUMN 纯元数据。全新空库路径同样收敛到 0：V15/V44 的 1000/1500 种子在前，
--        本迁移在所有既有迁移之后跑，空库首启后由 Java 默认（0）新建的商户天然是 0。
-- ROLLBACK: ALTER TABLE merchant ALTER COLUMN platform_rate_bps SET DEFAULT 1000;
--           UPDATE merchant SET platform_rate_bps = 1000 WHERE merchant_id = 'MCH-DEFAULT';
--           UPDATE merchant SET platform_rate_bps = 1500 WHERE merchant_id = 'MCH-OTHER';
--           （只恢复费率档位；已按 0 口径入钱包/记账的 order_revenue_split 行不回改）

ALTER TABLE merchant ALTER COLUMN platform_rate_bps SET DEFAULT 0;

UPDATE merchant
   SET platform_rate_bps = 0
 WHERE platform_rate_bps > 0;

COMMENT ON COLUMN merchant.platform_rate_bps IS
    '平台分账费率（万分比）：0=平台不抽成（2026-10-17 拍板默认）。'
    '0 时不向微信发分账请求（全额分账是否被服务商允许未实证），商户份额整笔入可提现钱包';
