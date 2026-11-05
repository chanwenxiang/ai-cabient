#!/usr/bin/env node
/**
 * 将邑 V16 第七章「只读探针」——用于真机不可用期间验证我方能真实打通出向通道。
 *
 * 只调用无副作用的接口：
 *   §7.19 /product/categories      类目
 *   §7.7  /phoneCapture/policy     拍摄要求（张数必须以此为准）
 *   §7.10 /product/status          批量查状态（轮询用这个，7.5 有写副作用不在此列）
 *   §7.13 /restock/shelf           当前货道（文档缺字段表，本探针原样打印 data 形状）
 *
 * 🔴 刻意不调用 §7.5 lookup 与 §7.6 create：前者「首次查询会建立采集任务」，后者会建档，
 *    两者都会在厂商侧留痕，且新版没有删除接口。
 *
 * 用法（凭据从环境变量注入，脚本绝不打印 token/手机号）：
 *   JIANGYI_MERCHANT_MOBILE=... JIANGYI_MERCHANT_PASSWORD=... \
 *   node scripts/jiangyi-restock-probe.mjs [--device CQYB10720] [--barcodes 6928804011208]
 */

const BASE = process.env.JIANGYI_API_BASE_URL || 'https://lingshouapi.hunanjysmart.com';
const PREFIX = '/merchant/openapi/v2/restock';

const args = process.argv.slice(2);
const arg = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const device = arg('--device');
const barcodes = (arg('--barcodes') || '').split(',').map((s) => s.trim()).filter(Boolean);

const mobile = process.env.JIANGYI_MERCHANT_MOBILE;
const password = process.env.JIANGYI_MERCHANT_PASSWORD;
if (!mobile || !password) {
  console.error('缺少 JIANGYI_MERCHANT_MOBILE / JIANGYI_MERCHANT_PASSWORD（未配置则不发送任何请求）');
  process.exit(2);
}

async function post(path, body, token) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body ?? {}),
  });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    json = { status: -1, msg: `非 JSON 响应: ${text.slice(0, 120)}` };
  }
  return { http: res.status, ...json };
}

function shape(value, depth = 0) {
  if (value === null) return 'null';
  if (Array.isArray(value)) {
    if (value.length === 0) return '[]';
    return `Array(${value.length}) of ${shape(value[0], depth + 1)}`;
  }
  if (typeof value === 'object') {
    return `{${Object.keys(value).join(',')}}`;
  }
  return typeof value;
}

function report(title, resp) {
  console.log(`\n=== ${title} ===`);
  console.log(`HTTP ${resp.http}  status=${resp.status}  msg=${resp.msg ?? ''}`);
  if (resp.data !== undefined) {
    console.log(`data: ${shape(resp.data)}`);
    console.log(`data(原始，截断 800 字): ${JSON.stringify(resp.data).slice(0, 800)}`);
  }
}

(async () => {
  const login = await post('/merchant/authentication/login2', { mobile, password });
  if (login.status !== 200 || typeof login.data !== 'string' || !login.data) {
    console.error(`login2 失败: status=${login.status} msg=${login.msg ?? ''}`);
    process.exit(1);
  }
  const token = login.data;
  console.log(`login2 OK（token 长度 ${token.length}，不打印内容）`);

  report('§7.19 商户类目 /product/categories', await post(`${PREFIX}/product/categories`, {}, token));
  report('§7.7 拍摄要求 /phoneCapture/policy', await post(`${PREFIX}/phoneCapture/policy`, {}, token));

  if (barcodes.length) {
    report(`§7.10 批量查状态 /product/status (${barcodes.length} 条)`,
      await post(`${PREFIX}/product/status`, { barCodes: barcodes.slice(0, 100) }, token));
  } else {
    console.log('\n=== §7.10 批量查状态 === 跳过（未传 --barcodes）');
  }

  if (device) {
    report(`§7.13 当前货道 /restock/shelf (${device})`,
      await post(`${PREFIX}/restock/shelf`, { deviceIdentifier: device }, token));
  } else {
    console.log('\n=== §7.13 当前货道 === 跳过（未传 --device）');
  }
})().catch((e) => {
  console.error('探针异常:', e.message);
  process.exit(1);
});
