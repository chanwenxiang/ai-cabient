-- =============================================================================
-- 业务/产品点位信息 —— 批量导出（易购老系统，全量柜机一次跑完）
-- =============================================================================
-- 用途：微信支付分申请邮件附件第 5 项「业务/产品点位信息（包括但不限于城市、地址、数量、场景图等）」
-- 数据源：easygo 老系统 MySQL（阿里云 RDS）
--   ego_machine_base_info        柜机主表（machine_code / machine_name / route_operator / online / label）
--   ego_opt_position_base_info   点位表（position_name / position_pic_name / position_pic_mobile / route_manager / rent）
--   ego_opt_machine_derive_detail 柜机-城市-区域关联（city_id / area_id → 城市名/区域名）
--   ego_city_info / ego_area_info  城市 / 区域字典
--   ego_machine_location_info      柜机经纬度（同一 machine_code 可能多行历史坐标，取 gmt_modified 最新）
--
-- 执行方式：阿里云 DMS → 新建 SQL 查询 → 粘贴本段 → 执行 → 结果集右上角「导出文件」选 CSV
-- 兼容性：单条 SELECT、无会话变量、无窗口函数，兼容 MySQL 5.7 / 8.0
-- ⚠️ 不加 WHERE 柜号条件 —— 这是**全量**导出（申请材料要「已覆盖点位数量」）
-- =============================================================================

SELECT
    m.machine_code                                          AS `柜子编号`,
    m.machine_id                                            AS `机器ID`,
    m.machine_name                                          AS `柜机名称`,
    m.route_no                                              AS `线长编号`,
    m.route_operator                                        AS `线长`,
    m.online                                                AS `在线状态`,
    m.label                                                 AS `标签`,

    -- 点位
    p.position_name                                         AS `点位名称`,
    p.position_level                                        AS `点位等级`,
    p.position_org_belong                                   AS `所属组织`,
    p.route_manager                                         AS `点位负责人`,
    p.position_rent_rate                                    AS `点位租金费率`,
    p.position_pic_name                                    AS `点位照片PC`,
    p.position_pic_mobile                                  AS `点位照片移动端`,

    -- 地址（城市 + 区域 + 详细地址）
    CONCAT_WS('', IFNULL(cy.name, ''), IFNULL(a2.name, ''),
              IFNULL(NULLIF(d.address_detail, ''), IFNULL(p.position_name, '')))  AS `完整地址`,
    cy.name                                                 AS `城市`,
    a2.name                                                 AS `区域`,
    d.address_detail                                        AS `详细地址`,

    -- 经纬度（同机多行历史坐标时取最新一条）
    loc.location_latitude                                   AS `纬度`,
    loc.location_longitude                                  AS `经度`

FROM ego_machine_base_info m

LEFT JOIN ego_opt_position_base_info p
       ON p.machine_code = m.machine_code
LEFT JOIN ego_opt_machine_derive_detail d
       ON d.machine_code = m.machine_code
LEFT JOIN ego_area_info a2
       ON a2.area_id = d.area_id
LEFT JOIN ego_city_info cy
       ON cy.city_id = d.city_id
LEFT JOIN (
    SELECT l1.machine_code, l1.location_latitude, l1.location_longitude
    FROM ego_machine_location_info l1
    INNER JOIN (
        SELECT machine_code, MAX(gmt_modified) AS mx
        FROM ego_machine_location_info
        GROUP BY machine_code
    ) l2 ON l2.machine_code = l1.machine_code AND l2.mx = l1.gmt_modified
    GROUP BY l1.machine_code, l1.location_latitude, l1.location_longitude
) loc ON loc.machine_code = m.machine_code

WHERE m.is_delete = 0
  AND m.asset_name = 1
ORDER BY cy.name, a2.name, m.machine_code;

-- =============================================================================
-- 使用说明
-- =============================================================================
-- 1) 本 SQL 返回**全部在册柜机**（含离线/停用）。若只要在线运营的，
--    在 WHERE 里加一行：AND m.online = 1
--    （申请材料建议用**全部在册**，因为官方要「已覆盖点位数量」，在册更保守可信）
--
-- 2) 「点位照片PC / 点位照片移动端」是**已存在的点位现场照片**字段（position_pic_name /
--    position_pic_mobile）—— 导出后即可批量下载，作为「场景图」的第一批素材，
--    不用重新拍 1109 个点位。可选做法：把这两个字段里能打开的图整理成
--    `<城市序号>_<柜子编号>.jpg` 命名，与本表「场景图文件名」列一一对应。
--
-- 3) 导出后用本仓脚本转换成官方要求的 13 字段表格：
--        python scripts/easygo_sites_to_payscore_table.py <导出的csv>
--    脚本会自动：按地址聚合点位、同址多台合并成一行、生成图片文件名、算柜机数量。
--
-- 4) 地址为空的柜机（p/d 都没值）需要人工补地址：脚本会把它们单独列在「待补地址」区段。
--
-- 5) 城市字段 `cy.name` 为空的柜机，说明柜机未关联城市字典（d.city_id 为空），
--    需按详细地址人工判断城市。
-- =============================================================================
