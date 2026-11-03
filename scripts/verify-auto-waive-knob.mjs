// 争议自动免单「后台旋钮」活栈核验（dispute.auto_waive.enabled）
//
// 判据：
//   K1 参数配置里已有该键，且 seed 默认 false（与注册表逐字一致，门禁 R5 同款约束）
//   K2 功能开关注册表把它挂在「争议工单」组、类型 BOOLEAN
//   K3 **配置优先于 env**：dev compose 里 AICABINET_DISPUTE_AUTO_WAIVE_ENABLED=true，
//      但库里写 false ⇒ 「立即执行」必须一律不免单（台账写明「参数配置关闭」），OPEN 工单数一支不掉
//   K4 写 true 后确实进入扫描分支（把阈值临时抬到 999999 小时 ⇒ 「候选 0 张」，零免单风险）
//   K5 每次改值都留痕：system_config_history + admin_audit_log
//   K6 收尾复位（enabled=false、hours 回原值）
//
// 用法：node scripts/verify-auto-waive-knob.mjs   （需本机活栈 + postgres/redis 容器）
import { execSync } from 'node:child_process';
import { captchaFromRedis } from './lib/redis-captcha.mjs';

const BASE = process.env.E2E_BASE_URL || 'http://localhost';
const KEY = 'dispute.auto_waive.enabled';
const HOURS_KEY = 'dispute.auto_waive.hours';
const TASK = 'dispute-auto-waive';
const fails = [];
const ok = (cond, msg) => {
  console.log(`${cond ? 'PASS' : 'FAIL'} ${msg}`);
  if (!cond) fails.push(msg);
};
const j = async (r) => {
  const t = await r.text();
  try {
    return { status: r.status, body: JSON.parse(t) };
  } catch {
    return { status: r.status, body: { raw: t.slice(0, 240) } };
  }
};
const psql = (sql) =>
  execSync(
    `docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -tA -c ${JSON.stringify(sql.replace(/\s+/g, ' '))}`,
    { encoding: 'utf8' }
  ).trim();

const cap = await j(await fetch(`${BASE}/api/v2/auth/captcha`));
const captchaId = cap.body?.data?.captchaId ?? cap.body?.data?.id;
const captchaCode = await captchaFromRedis(captchaId);
const login = await j(
  await fetch(`${BASE}/api/v2/auth/admin-password-login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      phoneNumber: process.env.E2E_OPS_PHONE || '13900000001',
      password: process.env.E2E_OPS_PASSWORD || '123456',
      captchaId,
      captchaCode
    })
  })
);
const token = login.body?.data?.token;
if (!token) {
  console.error('运营登录失败：', JSON.stringify(login.body).slice(0, 240));
  process.exit(1);
}
const H = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
const getCfg = async () =>
  (await j(await fetch(`${BASE}/api/v2/ops/admin/system-configs`, { headers: H }))).body?.data ??
  [];
const putCfg = async (key, value) =>
  j(
    await fetch(`${BASE}/api/v2/ops/admin/system-configs`, {
      method: 'PUT',
      headers: H,
      body: JSON.stringify({ configKey: key, configValue: value })
    })
  );
const runTask = async () =>
  j(
    await fetch(`${BASE}/api/v2/ops/admin/scheduled-tasks/${TASK}/run`, {
      method: 'POST',
      headers: H
    })
  );
const openCount = () => psql("SELECT count(*) FROM dispute_ticket WHERE status = 'OPEN'");

const list = await getCfg();
const row = list.find((r) => r.configKey === KEY);
ok(
  !!row,
  `K1 参数配置里已存在 ${KEY}（共 ${list.length} 个键；list 调用顺带触发 ensureDefaults 播种）`
);
ok(
  row?.configValue === 'false',
  `K1 seed 默认值=false（实得 ${row?.configValue}）⇒ 未显式打开就不放钱`
);

const catalog = (
  await j(await fetch(`${BASE}/api/v2/ops/admin/system-configs/feature-flags`, { headers: H }))
).body?.data;
const flags = catalog?.flags ?? catalog?.items ?? [];
const flag = flags.find((f) => f.key === KEY);
ok(
  flag?.group === '争议工单' && flag?.type === 'BOOLEAN',
  `K2 注册表分组/类型正确（${flag?.group} / ${flag?.type}）`
);
ok(flag?.default === 'false', `K2 注册表 default 与 seed 逐字一致（${flag?.default}）`);

const hoursWas =
  psql(`SELECT config_value FROM system_config WHERE config_key = '${HOURS_KEY}'`) || '72';

const openBefore = openCount();
ok(await putCfg(KEY, 'false').then((r) => +r.body?.code === 0), 'K3 写入 enabled=false 成功');
const offRun = await runTask();
const offMsg = offRun.body?.data?.message ?? offRun.body?.data?.summary ?? '';
const offLedger = psql(
  `SELECT COALESCE(last_message,'-') FROM scheduled_task WHERE task_key = '${TASK}'`
);
console.log(`    K3 run 返回=${JSON.stringify(offRun.body?.data)} 台账=${offLedger}`);
ok(
  /参数配置/.test(`${offMsg}${offLedger}`),
  `K3 env=true 但库里 false ⇒ 台账说明是「参数配置关闭」（实得 ${offLedger}）`
);
ok(openCount() === openBefore, `K3 未免单：OPEN 工单 ${openBefore} → ${openCount()}`);

ok((await putCfg(KEY, 'true')).body?.code === 0, 'K4 写入 enabled=true 成功');
ok(
  (await putCfg(HOURS_KEY, '999999')).body?.code === 0,
  'K4 阈值抬到 999999 小时（保证零候选，不动别人的工单）'
);
const onRun = await runTask();
const onLedger = psql(
  `SELECT COALESCE(last_message,'-') FROM scheduled_task WHERE task_key = '${TASK}'`
);
console.log(`    K4 run 返回=${JSON.stringify(onRun.body?.data)} 台账=${onLedger}`);
ok(/候选 0 张/.test(`${onLedger}`), `K4 走进扫描分支并返回「候选 0 张」（实得 ${onLedger}）`);
ok(openCount() === openBefore, `K4 仍零免单：OPEN 工单 ${openBefore} → ${openCount()}`);

// 强制留痕只覆盖「开关即资金动作」的键（MA-068）；hours 是普通调参键，
// 本库 ops.config.audit.enabled=false ⇒ 它按设计就是零留痕，不能拿它计数（否则判据必假红）。
const hist = Number(psql(`SELECT count(*) FROM system_config_history WHERE config_key = '${KEY}'`));
const hoursHist = Number(
  psql(`SELECT count(*) FROM system_config_history WHERE config_key = '${HOURS_KEY}'`)
);
const audit = Number(
  psql(
    `SELECT count(*) FROM admin_audit_log WHERE target_type='system_config' AND target_id = '${KEY}'`
  )
);
ok(
  hist >= 2,
  `K5 开关键无视审计总开关仍留痕：system_config_history ${hist} 条（本轮写了 false/true 两次）`
);
ok(audit >= 2, `K5 同一批写操作进 admin_audit_log：${audit} 条`);
console.log(
  `    K5 对照：普通调参键 ${HOURS_KEY} 的历史 ${hoursHist} 条（本库审计开关=${psql("SELECT config_value FROM system_config WHERE config_key='ops.config.audit.enabled'")}）`
);

ok((await putCfg(HOURS_KEY, hoursWas)).body?.code === 0, `K6 hours 复位为 ${hoursWas}`);
ok(
  (await putCfg(KEY, 'false')).body?.code === 0,
  'K6 enabled 复位为 false（默认关闭，需要时运营再打开）'
);
console.log(
  `    收尾：${KEY}=${psql(`SELECT config_value FROM system_config WHERE config_key='${KEY}'`)}，` +
    `${HOURS_KEY}=${psql(`SELECT config_value FROM system_config WHERE config_key='${HOURS_KEY}'`)}，OPEN 工单=${openCount()}`
);

console.log('');
if (fails.length) {
  console.log(`RESULT: FAIL（${fails.length} 条）`);
  fails.forEach((f) => console.log(`  - ${f}`));
  process.exit(1);
}
console.log('RESULT: PASS（争议自动免单后台旋钮：配置优先于 env、关闭即不放钱、改值留痕、可复位）');
