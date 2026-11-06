#!/usr/bin/env node
/**
 * 短信到达率巡检（只读）——按 (手机号, 发送日) 拉阿里云 QuerySendDetails，聚合 DELIVERED / 失败码分布。
 *
 * 为什么是这个形态（2026-11-06 探针实证后定）：
 *   ① 返回体里**没有 BizId** ⇒ 回执无法精确对回 `notification_log` 的某一行，同号同日多次发送分不开；
 *   ② 查询窗口只有几天 ⇒ 事后补扫来不及，只能近实时看；
 *   ③ `notification_log` 目前 SMS 行数 = 0，真正有量的是验证码链（`sms_verification_code`）。
 *   ⇒ 「给 notification_log 回填 delivered_at」这条原设计废弃；改为聚合口径的到达率巡检。
 *
 * 后台化（定时任务 + ops-alert 通道）留到上线有量再做：`check:scheduled-task-seed` 要求任何
 * tryBegin(TASK_KEY) 都必须有注册表 + 种子行，为 0 流量先造一条链路就是死代码。
 *
 * 凭据纪律：AccessKey 只从**运行中的容器 env** 现取（docker exec printenv），绝不打印；
 * 号码只打印脱敏形式；失败码是运营商侧字符串，可原样输出（排障要用）。
 *
 * 用法：
 *   node scripts/sms-delivery-report.mjs [--days=3] [--fail-ratio=0.2] [--json] [--container=NAME]
 *   node scripts/sms-delivery-report.mjs --selftest        （合成夹具验聚合与阈值真能红）
 *
 * 退出码：0 正常；2 超过失败占比阈值（报警语义）；3 凭据/容器不可用（无法判定，不当绿）。
 */
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';

const CONTAINER =
  (process.argv.find((a) => a.startsWith('--container=')) || '').split('=')[1] ||
  process.env.SMS_REPORT_CONTAINER ||
  'ai-cabinet-trade-service-1';
// 🔴 psql 只在 postgres 容器里（trade-service 那层没有 psql 二进制）⇒ 两个容器分开取
const DB_CONTAINER =
  (process.argv.find((a) => a.startsWith('--db-container=')) || '').split('=')[1] ||
  process.env.SMS_REPORT_DB_CONTAINER ||
  'ai-cabinet-postgres-1';
const DAYS_RAW = Number(
  (process.argv.find((a) => a.startsWith('--days=')) || '').split('=')[1] || 3
);
const DAYS = Number.isFinite(DAYS_RAW) && DAYS_RAW > 0 ? DAYS_RAW : 3;
const FAIL_RATIO = Number(
  (process.argv.find((a) => a.startsWith('--fail-ratio=')) || '').split('=')[1] || 0.2
);
const AS_JSON = process.argv.includes('--json');

// ── 阿里云 RPC 签名（逐字对齐 AliyunSmsSender#doSend 的那套参数序）──
const pct = (s) =>
  encodeURIComponent(String(s)).replace(/\+/g, ' ').replace(/\*/g, '%2A').replace(/%7E/g, '~');

function buildSignedUrl(creds, params) {
  const all = {
    AccessKeyId: creds.ak,
    Format: 'JSON',
    RegionId: creds.region,
    SignatureMethod: 'HMAC-SHA1',
    SignatureNonce: crypto.randomUUID(),
    SignatureVersion: '1.0',
    Timestamp: new Date().toISOString().replace(/\.\d+Z$/, 'Z'),
    Version: '2017-05-25',
    ...params
  };
  const canonical = Object.keys(all)
    .sort()
    .map((k) => `${pct(k)}=${pct(all[k])}`)
    .join('&');
  const stringToSign = `POST&${pct('/')}&${pct(canonical)}`;
  const sig = crypto
    .createHmac('sha1', creds.sk + '&')
    .update(stringToSign)
    .digest('base64');
  return `Signature=${pct(sig)}&${canonical}`;
}

const envOf = (container, key) => {
  try {
    return execFileSync('docker', ['exec', container, 'printenv', key], {
      encoding: 'utf8'
    }).trim();
  } catch {
    return '';
  }
};

function loadCreds(container) {
  const ak = envOf(container, 'SMS_ALIYUN_ACCESS_KEY_ID');
  const sk = envOf(container, 'SMS_ALIYUN_ACCESS_KEY_SECRET');
  return {
    ak,
    sk,
    region: envOf(container, 'SMS_ALIYUN_REGION_ID') || 'cn-hangzhou',
    ready: !!ak && !!sk
  };
}

function psql(container, sql) {
  try {
    return execFileSync(
      'docker',
      ['exec', container, 'psql', '-U', 'aicabinet', '-d', 'aicabinet', '-tAc', sql],
      {
        encoding: 'utf8'
      }
    )
      .split('\n')
      .map((l) => l.trim())
      .filter(Boolean);
  } catch (e) {
    throw new Error(
      `读库失败（容器 ${container} 里没有 psql？本机活栈没起？）：${String(e.message).split('\n')[0].slice(0, 120)}`
    );
  }
}

const mask = (p) => {
  const s = String(p);
  return s.length > 7 ? `${s.slice(0, 3)}****${s.slice(-4)}` : `${s.slice(0, 2)}****`;
};

// ── 聚合（与网络无关，可被 --selftest 直接复用）──
export function aggregate(rows) {
  const st = { total: rows.length, delivered: 0, pending: 0, failed: 0, codes: {}, byDate: {} };
  for (const r of rows) {
    const d = String(r.SendDate || '').slice(0, 10) || 'unknown';
    st.byDate[d] = (st.byDate[d] || 0) + 1;
    const code = r.ErrCode || (r.SendStatus === 1 ? 'PENDING' : 'UNKNOWN');
    st.codes[code] = (st.codes[code] || 0) + 1;
    if (r.SendStatus === 3 && code === 'DELIVERED') st.delivered++;
    else if (r.SendStatus === 1) st.pending++;
    else st.failed++;
  }
  st.failRatio = st.total ? st.failed / st.total : 0;
  return st;
}

// ── 合成自检：证明聚合口径正确且阈值真能红（不是只能绿的旋钮）──
function selfTest() {
  const mk = (status, errCode, date) => ({ SendStatus: status, ErrCode: errCode, SendDate: date });
  const healthy = aggregate([
    mk(3, 'DELIVERED', '2026-11-06 10:00:00'),
    mk(3, 'DELIVERED', '2026-11-06 10:01:00'),
    mk(1, null, '2026-11-06 10:02:00')
  ]);
  const broken = aggregate([
    mk(3, 'DELIVERED', '2026-11-06 10:00:00'),
    mk(2, 'CONTENT_KEYWORD', '2026-11-05 09:00:00'),
    mk(2, 'CONTENT_KEYWORD', '2026-11-05 09:10:00'),
    mk(2, 'MOBILE_NOT_ON_SERVICE', '2026-11-05 09:20:00')
  ]);
  const checks = [
    [
      '健康样本 delivered=2 pending=1 failed=0',
      healthy.delivered === 2 && healthy.pending === 1 && healthy.failed === 0
    ],
    ['健康样本失败占比 0 ⇒ 不报警', healthy.failRatio === 0],
    [
      '失败样本 failed=3 total=4 ⇒ ratio=0.75',
      broken.failed === 3 && Math.abs(broken.failRatio - 0.75) < 1e-9
    ],
    ['失败码分桶可见（CONTENT_KEYWORD=2）', broken.codes.CONTENT_KEYWORD === 2],
    ['阈值 0.2 ⇒ 失败样本必须判超阈值', broken.failRatio > 0.2],
    ['阈值 0.2 ⇒ 健康样本必须判未超', !(healthy.failRatio > 0.2)],
    ['空输入不炸、ratio=0', aggregate([]).total === 0 && aggregate([]).failRatio === 0]
  ];
  let bad = 0;
  for (const [why, ok] of checks) {
    if (!ok) bad++;
    console.log(`  [${ok ? 'ok' : 'BAD'}] ${why}`);
  }
  console.log(bad ? '[sms-delivery-report] selftest FAIL' : '[sms-delivery-report] selftest 通过');
  process.exit(bad ? 1 : 0);
}

async function queryPage(creds, phone, sendDate, page) {
  const body = buildSignedUrl(creds, {
    Action: 'QuerySendDetails',
    PhoneNumber: phone,
    SendDate: sendDate,
    PageSize: '50',
    CurrentPage: String(page)
  });
  const res = await fetch('https://dysmsapi.aliyuncs.com/', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body
  });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new Error(`非 JSON 响应 http=${res.status}: ${text.slice(0, 200)}`);
  }
  if (json.Code !== 'OK')
    throw new Error(`Code=${json.Code} msg=${(json.Message || '').slice(0, 160)}`);
  return {
    total: Number(json.TotalCount || 0),
    rows: json.SmsSendDetailDTOs?.SmsSendDetailDTO || []
  };
}

async function fetchAllFor(creds, phone, sendDate) {
  const out = [];
  let page = 1;
  for (;;) {
    const r = await queryPage(creds, phone, sendDate, page);
    out.push(...r.rows);
    if (out.length >= r.total || r.rows.length === 0) break;
    if (page++ > 20) break; // 防御：单日单号最多取 20 页
  }
  return out;
}

async function main() {
  const creds = loadCreds(CONTAINER);
  if (!creds.ready) {
    console.error(
      `[sms-delivery-report] 无法从容器 ${CONTAINER} 取到 SMS_ALIYUN_ACCESS_KEY_ID/SECRET ⇒ 不判定为绿`
    );
    process.exit(3);
  }
  // 待查组合：验证码链 + （若有）notification_log 的 SMS 行
  const pairs = new Set();
  for (const l of psql(
    DB_CONTAINER,
    `select phone_number||'@'||to_char(created_at at time zone 'UTC','YYYYMMDD') from sms_verification_code where created_at > now() - interval '${Math.max(1, Math.min(DAYS, 6))} days' group by 1`
  ))
    pairs.add(l);
  const notifCount = Number(
    psql(DB_CONTAINER, `select count(*) from notification_log where channel='SMS'`)[0] || 0
  );
  if (notifCount > 0) {
    for (const l of psql(
      CONTAINER,
      `select u.phone_number||'@'||to_char(n.created_at at time zone 'UTC','YYYYMMDD') from notification_log n join user_info u on u.user_id=n.user_id where n.channel='SMS' and n.created_at > now() - interval '${Math.max(1, Math.min(DAYS, 6))} days' group by 1`
    ))
      pairs.add(l);
  }

  const all = [];
  const perPair = [];
  let queryErrors = 0;
  for (const p of pairs) {
    const [phone, date] = p.split('@');
    try {
      const rows = await fetchAllFor(creds, phone, date);
      perPair.push({ phone: mask(phone), date, rows: rows.length });
      all.push(...rows);
    } catch (e) {
      queryErrors++;
      perPair.push({ phone: mask(phone), date, error: String(e.message).slice(0, 120) });
    }
  }

  const st = aggregate(all);
  const alert = st.failRatio > FAIL_RATIO;
  if (AS_JSON) {
    console.log(
      JSON.stringify({ container: CONTAINER, days: DAYS, pairs: pairs.size, ...st, alert, perPair })
    );
  } else {
    console.log(
      `[sms-delivery-report] 容器=${CONTAINER} 窗口=${DAYS}d 待查组合=${pairs.size} notification_log.SMS=${notifCount}`
    );
    console.log(
      `  回执行 total=${st.total} delivered=${st.delivered} pending=${st.pending} failed=${st.failed} 失败占比=${(st.failRatio * 100).toFixed(1)}%（阈值 ${(FAIL_RATIO * 100).toFixed(0)}%）`
    );
    if (Object.keys(st.codes).length) console.log(`  失败/状态码分布: ${JSON.stringify(st.codes)}`);
    if (Object.keys(st.byDate).length) console.log(`  按发送日: ${JSON.stringify(st.byDate)}`);
    for (const q of perPair.filter((x) => x.error))
      console.log(`  ⚠️ 查询失败 ${q.phone} ${q.date}: ${q.error}`);
    console.log(alert ? `  🔴 ALERT：失败占比超阈值 ⇒ 退出码 2` : '  正常');
  }
  if (queryErrors === pairs.size && pairs.size > 0) process.exit(3); // 全查失败＝无法判定，不当绿
  process.exit(alert ? 2 : 0);
}

// 只有直接执行才跑：本文件导出了 aggregate 供巡检/测试复用，import 时不能有副作用
// （与 check-mp-touch-target-ratchet.mjs 同一条约定）
const invokedDirectly = (process.argv[1] || '')
  .replaceAll('\\', '/')
  .endsWith('sms-delivery-report.mjs');
if (invokedDirectly) {
  if (process.argv.includes('--selftest')) selfTest();
  else
    main().catch((e) => {
      console.error(
        `[sms-delivery-report] 无法判定（不当绿）：${String(e.message).split(String.fromCharCode(10))[0].slice(0, 200)}`
      );
      process.exit(3);
    });
}
