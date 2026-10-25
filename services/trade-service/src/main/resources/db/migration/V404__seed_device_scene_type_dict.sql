-- MIGRATION_KIND: backfill
-- device_scene_type 场景字典端到端后台可配（2026-10-25 用户拍板，CB-066 后续批）：
-- 前端 shared-dict 将 device_scene_type 挪进运营可配集合（静态 9 值保留为 runtime 未加载/为空的兜底）；
-- 后端 SkuSceneLimitService 的 KNOWN_SCENES 硬编码白名单改为「字典 ACTIVE 项为准，字典无 ACTIVE 项时
-- 回落内置 9 值（fail-safe 不放开任意值）」。本迁移把内置 9 值种入 sys_dict_data 作为初始 ACTIVE 集。
-- 停用语义（用户拍板）：只拦新写入（设备建档/换场景/限额配置 400），存量设备与已配限额不动不告警。
-- sys_dict_data.dict_type 有 FK → sys_dict_type：先种类型行（幂等）。
INSERT INTO sys_dict_type (dict_type, dict_name, status, remark, sort_order)
VALUES ('device_scene_type', '投放场景', 'ACTIVE', 'V404：场景字典后台可配（初始集=内置 9 值）', 94)
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark) VALUES
    ('device_scene_type', 'OFFICE',   '写字楼/办公室', 10, 'ACTIVE', '场景字典初始集（V404）'),
    ('device_scene_type', 'CAMPUS',   '校园',          20, 'ACTIVE', '场景字典初始集（V404）'),
    ('device_scene_type', 'FACTORY',  '工厂',          30, 'ACTIVE', '场景字典初始集（V404）'),
    ('device_scene_type', 'HOSPITAL', '医院',          40, 'ACTIVE', '场景字典初始集（V404）'),
    ('device_scene_type', 'TRANSIT',  '交通枢纽',      50, 'ACTIVE', '场景字典初始集（V404）'),
    ('device_scene_type', 'MALL',     '商场',          60, 'ACTIVE', '场景字典初始集（V404）'),
    ('device_scene_type', 'RESIDENCE','社区/公寓',     70, 'ACTIVE', '场景字典初始集（V404）'),
    ('device_scene_type', 'HOTEL',    '酒店',          80, 'ACTIVE', '场景字典初始集（V404）'),
    ('device_scene_type', 'GYM',      '健身房',        90, 'ACTIVE', '场景字典初始集（V404）');
