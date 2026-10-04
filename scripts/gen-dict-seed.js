const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..');
const src = fs.readFileSync(path.join(root, 'packages/shared-dict/src/index.ts'), 'utf8');
const m = src.match(/export const DICT = ({[\s\S]*?}) as const/);
if (!m) {
  console.error('DICT not found');
  process.exit(1);
}
const obj = Function('return ' + m[1])();
const typeNames = {
  device_type: '设备类型',
  session_state: '会话状态',
  upload_status: '上传状态',
  dispute_status: '争议状态',
  pay_channel: '支付渠道',
  split_status: '分账状态',
  merchant_status: '商户状态',
  online_status: '在线状态',
  supplier_status: '供应商状态',
  purchase_order_status: '采购单状态',
  warehouse_status: '仓库状态',
  warehouse_outbound_status: '出库单状态',
  handover_status: '交接状态',
  in_transit_status: '在途状态',
  warehouse_movement_type: '库存变动类型',
  business_reference_type: '业务关联类型',
  replenishment_route_status: '补货路线状态',
  replenishment_task_status: '补货任务状态',
  replenishment_request_status: '补货申请状态',
  inventory_lot_status: '批次状态',
  exception_severity: '异常级别',
  exception_status: '异常状态',
  exception_type: '异常类型',
  ops_exception_action: '异常操作',
  reconciliation_status: '对账状态',
  sku_status: '商品状态',
  order_status: '订单状态'
};
const sql = [];
let sortType = 1;
for (const [type, map] of Object.entries(obj)) {
  const name = typeNames[type] || type;
  sql.push(
    `INSERT INTO sys_dict_type (dict_type, dict_name, status, sort_order) VALUES ('${type}', '${name}', 'ACTIVE', ${sortType++}) ON CONFLICT (dict_type) DO NOTHING;`
  );
  let i = 1;
  for (const [code, label] of Object.entries(map)) {
    const esc = String(label).replace(/'/g, "''");
    sql.push(
      `INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status) SELECT '${type}', '${code}', '${esc}', ${i++}, 'ACTIVE' WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type='${type}' AND d.dict_value='${code}');`
    );
  }
}
const out = path.join(root, 'services/trade-service/src/main/resources/db/migration/_dict_seed_fragment.sql');
fs.writeFileSync(out, sql.join('\n') + '\n');
console.log('types', Object.keys(obj).length, 'statements', sql.length);
