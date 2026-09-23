-- V286: 演示夹具改名 —— 去掉「测试」字样，改为拟真名称
-- MIGRATION_KIND: backfill
-- LOCK_RISK: low
-- NOTES: 全部幂等；条件限定「当前值仍等于旧占位值」才改写，重放与多环境均安全
-- TABLES: device_info (~2), user_info (~1)
-- =====================================================================
-- 背景（2026-09-23）
--   V2 无条件写入的演示夹具会随迁移链落到**任何**环境：
--     device_info: CAB-001  -> '测试柜-001'
--     user_info  : 10001    -> '测试用户'
--   而 V44 写入 CAB-OTHER -> '测试柜-OTHER'，V57 更会把 CAB-001 的名字**改回**'测试柜-001'。
--   ⇒ 一个新的生产库跑完迁移链后，运营台上会出现「测试柜-001 / 测试用户」，
--     上线时还得再改一次。这就是本迁移要消除的东西。
--
--   按 forward-only 原则：V2 / V44 / V57 **一律不动**（改写已应用迁移 = checksum 漂移），
--   改名前移到本迁移；全链只多这一支。
--
-- 🔴 本迁移**刻意不加 `seed_env` 守卫**（与 V244 / V252 / V283 不同）：
--   那几支守的是「只在 UAT/local 生效的演示种子」，生产跳过它们是对的；
--   而这里要修的病根恰恰是「V2 已经把旧名写进了每一个环境」——
--   若加 `'${seed_env}' IN ('local','dev','uat')`，生产会**继续**显示「测试柜-001」，
--   与本次目的完全相反。
--
-- 🔴 条件写死「当前值仍等于旧占位值」⇒ 运营已经手工改过的设备名/用户名不会被覆盖。
--
-- ⚠️ 三处新名称与 Java 侧保持同源（改动时请一起改）：
--      device_info -> DeviceNameSupport.KNOWN_NAMES（唯一运行时权威）
--      user_info   -> DemoDataService.DEMO_CONSUMER_NAME
-- =====================================================================

UPDATE device_info
SET device_name = '门店一号柜',
    updated_at  = NOW()
WHERE device_id = 'CAB-001'
  AND (
    device_name IS NULL
    OR device_name = device_id
    OR device_name LIKE '%???%'
    OR device_name = '测试柜-001'
  );

UPDATE device_info
SET device_name = '门店二号柜',
    updated_at  = NOW()
WHERE device_id = 'CAB-OTHER'
  AND (
    device_name IS NULL
    OR device_name = device_id
    OR device_name LIKE '%???%'
    OR device_name = '测试柜-OTHER'
  );

UPDATE user_info
SET name       = '陈晓',
    updated_at = NOW()
WHERE user_id = 10001
  AND (
    name IS NULL
    OR name = '测试用户'
  );
