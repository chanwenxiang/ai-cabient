-- 定时任务管理模块：任务注册表（启停开关 + 最近执行记录）
CREATE TABLE IF NOT EXISTS scheduled_task (
    task_key         VARCHAR(64)  PRIMARY KEY,
    task_name        VARCHAR(128) NOT NULL,
    task_group       VARCHAR(32)  NOT NULL DEFAULT 'SYSTEM',
    schedule_desc    VARCHAR(255),
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    last_run_at      TIMESTAMPTZ,
    last_result      VARCHAR(16),
    last_message     VARCHAR(512),
    last_duration_ms BIGINT,
    remark           VARCHAR(512),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE scheduled_task IS '定时任务注册表（启停开关与最近执行记录）';

INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('device-presence',          '设备离线巡检',       'DEVICE',   '每 60 秒',      '离线标记与超时自动锁机'),
('session-opening-expire',   '开门超时会话清理',   'TRADE',    '每 30 秒',      '开门指令未响应自动释放会话'),
('session-restock-expire',   '补货会话超时清理',   'TRADE',    '每 60 秒',      '补货购物态超时自动关闭'),
('session-recognizing-expire','识别结算超时升级',   'TRADE',    '每 60 秒',      '识别/结算超时转争议或失败'),
('ops-exception-scanner',    '异常卡点扫描',       'OPS',      '每 30 秒',      '开门/上传/识别/结算卡点告警'),
('compensation-process',     '补偿任务处理',       'SYSTEM',   '每 30 秒',      'TCC 补偿任务执行'),
('compensation-retry',       '补偿任务重试',       'SYSTEM',   '每 60 秒',      '失败事务自动重试'),
('replenishment-timeout',    '补货超时收口',       'WAREHOUSE','每 60 秒',      '签到超时补货任务自动收口'),
('data-consistency',         '数据一致性巡检',     'OPS',      '每 5 分钟',     '订单/支付/库存一致性检查'),
('unpaid-cancel',            '未付订单自动取消',   'TRADE',    '每 15 分钟',    '待支付订单超时关单并回滚库存'),
('recharge-cancel',          '充值单自动取消',     'TRADE',    '每 5 分钟',     '待支付充值单超时取消'),
('device-auto-unlock',       '稳定在线自动解锁',   'DEVICE',   '每 5 分钟',     '恢复稳定在线后自动解除销售锁'),
('merchant-notify',          '商户工作台通知',     'MERCHANT', '每 15 分钟',    '商户待办订阅消息推送'),
('dispute-sla',              '争议 SLA 巡检',      'OPS',      '每 15 分钟',    '争议工单 SLA 提醒与逾期告警'),
('profit-sharing-retry',     '分账重试',           'FINANCE',  '每 15 分钟',    '微信分账失败自动重试'),
('expiry-alert',             '库存临期预警',       'WAREHOUSE','每 60 分钟',    '库存批次临期扫描告警'),
('reconciliation',           '每日对账',           'FINANCE',  '每日 01:30',    '微信渠道日结对账'),
('line-commission',          '线长佣金入账',       'FINANCE',  '每日 00:20',    '前一日线长佣金结算入账'),
('finance-margin',           '财务保证金固化',     'FINANCE',  '每日 00:05',    '前一日毛利快照固化'),
('coupon-expire',            '优惠券过期处理',     'MARKETING','每日 02:00',    '未用优惠券到期置为过期'),
('sla-snapshot',             'SLA 日快照',         'OPS',      '每日 00:05',    '开门成功率/在线率日快照'),
('kpi-snapshot',             '设备可用性 KPI 快照','OPS',      '每日 01:10',    '锁机率/恢复时长等日 KPI')
ON CONFLICT (task_key) DO NOTHING;

-- 权限：与侧边栏一致 —— 查看(C)挂在「系统」导航(ops:nav:sys=403)下，路径与前端路由一致；
-- 启停/执行(F)挂在查看权限下，与 V132/V142 按钮权限惯例一致。
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 572, 403, 'ops:task:list', '定时任务', 'C', '/scheduled-tasks', 55, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:task:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 573,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:task:list' LIMIT 1),
       'ops:task:edit', '任务启停', 'F', NULL, 10, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:task:edit');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 574,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:task:list' LIMIT 1),
       'ops:task:run', '立即执行', 'F', NULL, 20, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:task:run');

-- 角色授权：拥有参数查看的角色可看任务；拥有参数编辑的角色可启停与执行；admin(role 1) 全量
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code = 'ops:task:list'
WHERE p_gate.perm_code = 'ops:config:list'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code IN ('ops:task:edit', 'ops:task:run')
WHERE p_gate.perm_code = 'ops:config:edit'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:task:list', 'ops:task:edit', 'ops:task:run')
ON CONFLICT DO NOTHING;
