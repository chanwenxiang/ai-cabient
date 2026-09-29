-- V292: ops_org_node.node_type 拓宽 varchar(16)→varchar(64)，对齐 sys_dict_data.dict_value 宽度。
-- 背景：node_type 以 org_node_type 字典为权威白名单（V291），运营可在运行期经字典管理扩值；
-- varchar(16) 只容得下种子值 HQ/REGION/BRANCH，手工扩入 DIRECTLY_OPERATED(17)/NON_DIRECTLY_OPERATED(20)
-- 后新增组织即 500（value too long for type character varying(16)，追踪号 3e2783c09a00…）。
-- 列宽 ≤ 字典值宽 ⇒ 运营扩字典必然踩雷；对齐 64 后任何 ACTIVE 字典值均可落库。

ALTER TABLE ops_org_node ALTER COLUMN node_type TYPE varchar(64);

COMMENT ON COLUMN ops_org_node.node_type IS '组织类型；见 org_node_type 字典（权威白名单，可运营侧扩展）；宽度对齐 sys_dict_data.dict_value';
