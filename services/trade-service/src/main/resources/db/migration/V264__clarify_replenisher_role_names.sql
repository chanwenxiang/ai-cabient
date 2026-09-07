-- BUG-016/017：区分运营后台补货员 vs 商户 H5 补货员，降低运营账号误绑概率
UPDATE ops_role
SET role_name = '运营补货员（后台）',
    remark = '运营后台履约仓储；商户 H5 现场补货请用「商户补货员（H5）」并绑定商户范围'
WHERE role_key = 'replenisher';

UPDATE ops_role
SET role_name = '商户补货员（H5）',
    remark = '商户端补货作业；须在「商户范围」绑定至少一个商户'
WHERE role_key = 'merchant_replenisher';

UPDATE ops_role
SET remark = '勿轻易授予；新建运营账号默认不勾选此角色'
WHERE role_key = 'admin';
