#!/usr/bin/env node
/**
 * 将邑真机联调 day-1 自检（**只读探测**，不写厂商云、不改任何配置）。
 *
 * 用法：
 *   node scripts/jiangyi-day1-check.mjs                          # 打正式域名
 *   node scripts/jiangyi-day1-check.mjs --base=https://dev-cabinet.easygovm.com
 *   node scripts/jiangyi-day1-check.mjs --token=<设备JWT>         # 追加自钉正向断言
 *   JIANGYI_DAY1_TOKEN=… node scripts/jiangyi-day1-check.mjs     # 等价（token 勿写进命令行历史）
 *
 * 为什么第一组探测要看 **401 而不是 200**：
 * 自钉把 43 条路径的 base 全交给 `/jiangyi/api`，前提是**公网入口的 `/jiangyi/` 与 `/websocket/`
 * 两条 location 真能到网关**。宿主机 nginx vhost 在仓库外（仓库内三份 infra/gateway/nginx*.conf
 * 已配），所以只能从外部证明它——不带 token 打设备面，**401 = 请求穿到了 DeviceAuthInterceptor**
 * （路由通）；**404/502/HTML = 路由断** ⇒ 此时若开自钉，等于把整批柜子钉到一个域名对、路径 404 的地址，
 * 比不钉更糟（DomainConfigController 的 fail-safe 就是为这个写的）。
 *
 * 退出码：0 通过（含 SKIP）；1 有 FAIL；2 用法/参数错。
 */
import process from 'node:process';
import http from 'node:http';
import https from 'node:https';
import crypto from 'node:crypto';

const args = process.argv.slice(2);
const flagValues = new Map();
for (const a of args) {
  const m = /^--([^=]+)=(.*)$/.exec(a);
  if (m) flagValues.set(m[1], m[2]);
}
const usage = (msg) => {
  console.error(`[day1-check] ${msg}`);
  process.exit(2);
};

const rawBase =
  flagValues.get('base') ?? process.env.JIANGYI_DAY1_BASE_URL ?? 'https://cabinet.easygovm.com';
let base;
try {
  base = new URL(rawBase);
} catch {
  usage(`--base 无法解析：${rawBase}`);
}
if (!/^https?:$/.test(base.protocol)) usage(`--base 协议须 http(s)：${rawBase}`);
const API_TAIL = '/jiangyi/api';
// 自钉口径：控制器会补 API_TAIL，所以探测也按补好的算（已带尾段就不重复）
const apiBase = base.pathname.replace(/\/+$/, '').endsWith(API_TAIL)
  ? base.origin + base.pathname.replace(/\/+$/, '')
  : base.origin + base.pathname.replace(/\/+$/, '') + API_TAIL;
const token = flagValues.get('token') ?? process.env.JIANGYI_DAY1_TOKEN ?? '';
const identifier =
  flagValues.get('identifier') ?? process.env.JIANGYI_DAY1_IDENTIFIER ?? 'CQYB10720';
const timeoutMs = Number(flagValues.get('timeout') ?? 12000);

const results = [];
const record = (name, ok, detail, skipped = false) => {
  results.push({ name, ok, detail, skipped });
  console.log(`${skipped ? '· SKIP' : ok ? '✓ PASS' : '✗ FAIL'}  ${name}\n          ${detail}`);
};

async function post(path, { bearer, body = '{}' } = {}) {
  const url = apiBase + path;
  const headers = { 'Content-Type': 'application/json', Accept: 'application/json' };
  if (bearer) headers.Authorization = bearer.startsWith('Bearer ') ? bearer : `Bearer ${bearer}`;
  const res = await fetch(url, {
    method: 'POST',
    headers,
    body,
    signal: AbortSignal.timeout(timeoutMs),
    redirect: 'manual'
  });
  const text = await res.text();
  return { url, status: res.status, text };
}

/** 只验 HTTP 码：设备面路由门（401=通，404/502=断）。 */
function routeGate(name, r, expect = 401) {
  const looksHtml = /<html|<!DOCTYPE/i.test(r.text.slice(0, 200));
  const detail =
    `${r.status}${looksHtml ? ' (HTML 错误页=没进网关)' : ''} ← ${r.url}` +
    (r.status === expect ? '' : `；期望 ${expect}`);
  record(name, r.status === expect && !looksHtml, detail);
}

/** 验信封：云侧怪形态 status/msg 双 null（Jackson 若不序列化 null，自钉就失效）。 */
function nullEnvelope(name, r) {
  if (r.status !== 200) {
    record(
      name,
      false,
      `HTTP ${r.status}（期望 200）← ${r.url}\n          body: ${r.text.slice(0, 240)}`
    );
    return { failed: true };
  }
  const wireNull = /"status"\s*:\s*null/.test(r.text) && /"msg"\s*:\s*null/.test(r.text);
  let data;
  try {
    data = JSON.parse(r.text).data;
  } catch {
    record(name, false, `响应非 JSON：${r.text.slice(0, 240)}`);
    return { failed: true };
  }
  record(
    name,
    wireNull,
    `HTTP 200, 线上字面量 status/msg=${wireNull ? 'null' : '非 null'}${wireNull ? '' : '（Jackson 把 null 吃了）'}, data=${JSON.stringify(data)}`
  );
  return wireNull ? { data } : { failed: true };
}

/** WS 握手探测：只关心入口是否把 /websocket/ 送到网关（401=通；101=已通且鉴权放行，本脚本不带合法 token 不该看到）。 */
function wsProbe(path) {
  const url = new URL(path, apiBase.replace(/\/jiangyi\/api$/, ''));
  const mod = url.protocol === 'https:' ? https : http;
  const key = crypto.randomBytes(16).toString('base64');
  return new Promise((resolve) => {
    const settle = (out) => {
      clearTimeout(timer);
      resolve(out);
    };
    const timer = setTimeout(
      () => settle({ status: 'TIMEOUT', detail: `${timeoutMs}ms 内无响应` }),
      timeoutMs
    );
    const req = mod.request(
      url,
      {
        method: 'GET',
        headers: {
          Connection: 'Upgrade',
          Upgrade: 'websocket',
          'Sec-WebSocket-Version': '13',
          'Sec-WebSocket-Key': key
        }
      },
      (res) => settle({ status: res.statusCode, detail: `HTTP ${res.statusCode} ← ${url.href}` })
    );
    req.on('upgrade', () =>
      settle({ status: 101, detail: `101 Switching Protocols ← ${url.href}` })
    );
    req.on('error', (e) =>
      settle({ status: 'ERROR', detail: `${e.code ?? e.name}: ${e.message}` })
    );
    req.end();
  });
}

console.log(
  `\n[day1-check] 目标 ${apiBase}（超时 ${timeoutMs}ms，token ${token ? `已提供/${token.length} 字符` : '未提供'}）`
);

/* ── 1. 路由门（无需凭据） ───────────────────────────────── */
try {
  routeGate(
    '路由门 · domainConfig 无 token 须 401',
    await post('/domainConfig/getConfigStatusByIdentifier')
  );
} catch (e) {
  record('路由门 · domainConfig', false, `请求异常：${e.message}（域名不可达 / DNS / 证书？）`);
}
try {
  // 未注册路径也必须吃 401：证明 catch-all 没绕过鉴权（探测面不收窄）
  routeGate('路由门 · catch-all 无 token 须 401', await post('/feature/getFeatureByDevice'));
} catch (e) {
  record('路由门 · catch-all', false, `请求异常：${e.message}`);
}
try {
  const ws = await wsProbe(`/websocket/device/${identifier}?token=day1-probe`);
  const ok = ws.status === 401;
  record(
    '路由门 · WS 握手无合法 token 须 401',
    ok,
    `${ws.detail}${ok ? '' : '；404/502=入口 nginx 没转 /websocket/，101=该 token 被误放行（真缺陷）'}`
  );
} catch (e) {
  record('路由门 · WS 握手', false, `探测异常：${e.message}`);
}

/* ── 2. 自钉正向断言（需设备 token） ─────────────────────── */
if (!token) {
  record(
    '自钉 · getConfigStatusByIdentifier',
    true,
    '未提供 --token，跳过（上面三条门过了就已可判断自钉会不会把设备钉死）',
    true
  );
  record('自钉 · getConfigDomainByIdentifier', true, '未提供 --token，跳过', true);
  record('自钉 · getConfigSocketUrlByIdentifier', true, '未提供 --token，跳过', true);
} else {
  const statusEnv = nullEnvelope(
    '自钉 · getConfigStatusByIdentifier（恒 1 + null 信封）',
    await post('/domainConfig/getConfigStatusByIdentifier', { bearer: token })
  );
  if (!statusEnv.failed) {
    record(
      '自钉 · status 值',
      statusEnv.data === 1,
      `data=${JSON.stringify(statusEnv.data)}（期望整数 1：恒 1 才是自愈式重钉）`
    );
  }
  const domainEnv = nullEnvelope(
    '自钉 · getConfigDomainByIdentifier（下发我方 base）',
    await post('/domainConfig/getConfigDomainByIdentifier', {
      bearer: token,
      body: JSON.stringify({ identifier, doorNum: 0 })
    })
  );
  if (!domainEnv.failed) {
    const d = domainEnv.data;
    if (typeof d === 'string') {
      const host = new URL(d).host;
      const want = flagValues.get('expect-base');
      const tailOk = d.endsWith(API_TAIL);
      const hostOk = want ? new URL(want).host === host : host === base.host;
      record(
        '自钉 · 下发值形态与主机',
        tailOk && hostOk,
        `${d}｜尾段 ${tailOk ? '含 /jiangyi/api' : '缺 /jiangyi/api'}｜host ${host}${hostOk ? ' 与目标一致' : ' ≠ 期望（env 值或网关没重启？）'}`
      );
    } else {
      record(
        '自钉 · 下发值形态与主机',
        false,
        `data=${JSON.stringify(d)} ⇒ 网关侧 JIANGYI_PUBLIC_BASE_URL 未配/非法（设计值是三端点一起 404，回 200+null 属实现漂移）`
      );
    }
  }
  const socketEnv = nullEnvelope(
    '自钉 · getConfigSocketUrlByIdentifier（下发 wss 基址）',
    await post('/domainConfig/getConfigSocketUrlByIdentifier', {
      bearer: token,
      body: JSON.stringify({ identifier })
    })
  );
  if (!socketEnv.failed) {
    const s = socketEnv.data;
    if (typeof s === 'string') {
      let hostOk = false;
      let tailOk = false;
      try {
        const u = new URL(s);
        tailOk = /^wss?:$/.test(u.protocol) && u.pathname === '/websocket/device/';
        hostOk = u.host === base.host;
      } catch {
        /* 下面按失败报 */
      }
      record(
        '自钉 · socket 下发值形态与主机',
        tailOk && hostOk,
        `${s}｜形态 ${tailOk ? 'ws(s)://…/websocket/device/' : '不符（APK 要求可直接拼 device/<id> 的基址）'}｜host ${hostOk ? '与目标一致' : `≠ 期望 ${base.host}`}`
      );
    } else {
      record(
        '自钉 · socket 下发值形态与主机',
        false,
        `data=${JSON.stringify(s)} ⇒ 基址缺失时本端点应与另两条一起 404；回 200+null 属实现漂移`
      );
    }
  }
}

/* ── 3. 本机跑不了、必须上服务器核的只读命令 ─────────────── */
console.log(`
[day1-check] 下列三条只能服务器执行（本脚本不代跑，避免误触 prod）：
  1) nginx -T | grep -nE "location /(jiangyi|websocket)/"     # 入口 vhost 两条路由（本脚本已从外部证明，与此对照）
  2) docker compose --env-file infra/.env.production -f infra/docker-compose.yml -f infra/docker-compose.apps.yml \\
       -f infra/docker-compose.production.yml -f infra/docker-compose.prod-server.yml --profile apps \\
       exec jiangyi-gateway printenv JIANGYI_PUBLIC_BASE_URL   # 必须是正式域名，留空=自钉不生效（404）
  3) docker logs --since 30m ai-cabinet-jiangyi-gateway-1 2>&1 | grep -aE "JIANGYI_DOMAIN_PIN|JIANGYI_PATH_UNIMPLEMENTED"
       # PIN 未 armed 会打 JIANGYI_DOMAIN_PIN_DISABLED；404 台账就是给厂商排优先级的那份清单
`);

const fails = results.filter((r) => !r.ok && !r.skipped);
const skips = results.filter((r) => r.skipped).length;
console.log(
  `[day1-check] 结果：${results.length - fails.length - skips} 通过 / ${fails.length} 失败 / ${skips} 跳过`
);
if (fails.length) {
  console.error(
    `[day1-check] 🔴 路由或信封未过 ⇒ 不要开自钉、不要重启设备（钉错比不钉难查）。详见脚本头注释。`
  );
  process.exit(1);
}
console.log(`[day1-check] 提醒：明天可验收的是 **HTTP 面 + 自钉三端点 + 404 台账**。WS 端到端仍会被两堵墙挡住
            （NORMAL 拨号只认 prefs "socketurl"，空则回落内置 wss://lingshouapi——拉取到的 SOCKET_URL
             只喂 MZ/CARD_258/CD_WB 任务；且 WS 带的是将邑云 HS512 token，我方 DeviceHandshakeInterceptor
             只验自家密钥 ⇒ 401）——这两堵墙的钥匙都在厂商推送（sendDeviceDomain 对象格式 / sendDeviceOffDomain），
             不是回归。`);
