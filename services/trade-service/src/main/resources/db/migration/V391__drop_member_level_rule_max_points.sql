-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: member_level_rule（~几行，每个等级一行）
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = ALTER TABLE member_level_rule ADD COLUMN max_points INT;
-- COMPETITOR_REF: CB-058
-- NOTES: §10 G10 拍板 E（2026-10-18 用户选「撤旋钮：删 max_points」）。
--   member_level_rule.max_points 自 V163 重新加列以来**全仓零读取**：判级走
--   MemberService.calculateMemberLevel(totalSpent, netRecharge)，压根不读积分；
--   同表的 min_points 唯一作用是 PointsRedeemService 算 C 端「距下一档还差多少分」的展示分母。
--   对照 CB-058：农行/京豆的活动积分与会员等级是两套规则，思迅天店文档把「积分设置」与
--   「会员等级/权益」分列 ⇒ 积分上限不是行业通行的等级判定输入。
--   留着它的代价不是空耗一列，而是后台「累计积分区间」让运营**误以为填了生效**（死旋钮）。
--   消费者同批删除：实体字段 / DTO 组件 / 两处映射 / openapi.ts / 后台表单与列表列。
--   DROP COLUMN 只重写表元数据，小表锁瞬时；已应用环境无需 repair（本文件是新增迁移，不改历史 checksum）。

ALTER TABLE member_level_rule DROP COLUMN max_points;
