-- V398：校准 operator_user_id_seq —— 修「新增运营账号静默覆盖已有账号」
--
-- 成因：V47 只在它执行那一刻把序列 setval 到当时的 MAX(user_id)。此后 V116（100000007）、
-- V117（100000008、100000009）、V140+V162（100000006、100000011）都用**显式 user_id** 插种子
-- 运营账号，而 Postgres 不会因为显式主键去推序列 ⇒ 全新库里序列停在 100000005、
-- MAX(user_id) 已是 100000011。UserInfoMapper.nextOperatorUserId()=nextval 于是把已被占用的号
-- 发给「新增运营账号」，BaseTradeMapper.save() 又按「主键已存在 ⇒ updateById」处理，
-- 一次点击就静默覆盖一个在用的账号。
-- UAT 2026-10-23 实测：user_info/user_account 行数不变、新账号 created_at 仍是建库时刻，
-- 3 个种子演示账号（13800138006 / 13900000002 / 13900000003）查无此号，连带 ops_user_role
-- 旧绑定被清、ops_user_department 残留旧主部门、V254 的一条 PENDING 审批收件人串到新建账号。
-- 配对修复（防复发）：OpsRbacService.doCreateOperator / MerchantTeamAdminService /
-- OpsAdminBootstrapService 三处分配点由 save() 改真 insert，撞号即报错回滚，不再覆盖。
--
-- ⚠️ 与 V47/V397 的写法差一处：必须取 GREATEST(当前 last_value, MAX(user_id))，不能只取 MAX。
--    正常在用的库里 nextval 已推进到 MAX 之后（发过号、账号又被物理删掉），照抄 V47 的
--    「setval 到 MAX」会把序列**往回拉**，反而当场制造撞号。setval 惯法同 V106/V108/V110。
--    序列是业务侧 nextval 的字面量名（UserInfoMapper.xml），名字不可改。

SELECT setval(
    'operator_user_id_seq',
    GREATEST(
        (SELECT last_value FROM operator_user_id_seq),
        COALESCE((SELECT MAX(user_id) FROM user_info), 100000000),
        100000000
    ),
    TRUE
);
