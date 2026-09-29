-- V291: ops_org_node.node_type labels (HQ | REGION | BRANCH) for admin org tree dialog.
-- 字典即权威白名单：OrgService.doUpsertNode 校验 nodeType 必须落在本字典 ACTIVE 项内。

INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order, remark)
SELECT 'org_node_type', '组织类型', 'ACTIVE', 0, 'ops_org_node.node_type'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'org_node_type');

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE', v.remark
FROM (VALUES
    ('org_node_type', 'HQ',     '总部',   1, '组织树根节点'),
    ('org_node_type', 'REGION', '区域',   2, '中间层级，如华南区'),
    ('org_node_type', 'BRANCH', '分公司', 3, '叶子层级，如深圳分公司')
) AS v(dict_type, dict_value, dict_label, sort_order, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);

COMMENT ON COLUMN ops_org_node.node_type IS '组织类型；见 org_node_type 字典（HQ|REGION|BRANCH）';
