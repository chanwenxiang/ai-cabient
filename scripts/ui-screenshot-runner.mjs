/**
 * UI 截图基线 runner（本地 pre-push + 交付前人工复核；**不进 CI**）。
 *
 * 为什么在本地：CI 只有 e2e-h5，admin 作业带 `-Pskip-admin-ui`，微信端截图需要
 * 开发者工具实例（9420/9421）——三端截图在 CI 里跑不起来，所以定位为人工复核辅助。
 *
 * 用法：
 *   node scripts/ui-screenshot-runner.mjs --surface=all                 # 截图 + 健康检查 + 与基线比对
 *   node scripts/ui-screenshot-runner.mjs --surface=admin --theme=dark
 *   node scripts/ui-screenshot-runner.mjs --promote                      # 把本轮 current 认成新基线（改动被接受后）
 *   node scripts/ui-screenshot-runner.mjs --surface=merchant-mp --mode=check
 *
 * 判读口径：
 *   FAIL  = 白屏 / console error / 骨架屏卡死 ⇒ 真回归，必须修
 *   CHANGED = 与基线字节不同 ⇒ 交人工看 current vs baseline 两张图（数据变化也会触发，不必然缺陷）
 *   截图与基线都在 `.tmp/ui-baseline/`（gitignore，**不入库**，与既有拍板一致）
 *
 * 前置：后端网关在跑；admin dev server（默认 http://127.0.0.1:3100/admin，可用
 * ADMIN_APP 覆盖）；微信端需 `cli.bat auto --project <dist/dev/mp-weixin> --auto-port 942x`。
 * 🔴 网络变更后先重烤 mp env（sync-consumer-mp-api.mjs），否则请求连不到网关、
 * 列表会一直停在加载态，被误判成骨架屏 bug。
 */
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const require = createRequire(import.meta.url);
const ROOT = path.resolve(import.meta.dirname, '..');
const BASE = path.join(ROOT, '.tmp', 'ui-baseline');
const PAGES = JSON.parse(
  fs.readFileSync(path.join(ROOT, 'scripts', 'ui-screenshot-pages.json'), 'utf8')
);

const argv = process.argv.slice(2);
/** 同时支持 `--k=v` 与 `--k v` 两种写法 */
const argOf = (name, dflt = '') => {
  const eq = argv.find((a) => a.startsWith(`--${name}=`));
  if (eq) return eq.slice(eq.indexOf('=') + 1);
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : dflt;
};
const surfaces = (
  argOf('surface', 'all') === 'all'
    ? ['admin', 'consumer-mp', 'merchant-mp']
    : argOf('surface').split(',')
).filter((s) => PAGES[s]);
const themeArg = argOf('theme', 'both');
const themes = themeArg === 'both' ? ['light', 'dark'] : themeArg.split(',');
const promote = argv.includes('--promote');
const mode = argOf('mode', 'capture');

const API = process.env.UI_BASE_API || 'http://127.0.0.1';
const ADMIN_APP = process.env.ADMIN_APP || 'http://127.0.0.1:3100/admin';
const MP_PORT = { 'consumer-mp': '9421', 'merchant-mp': '9420' };

const results = [];
const rec = (surface, page, status, detail) => {
  results.push({ surface, page, status, detail });
  console.log(`${status.padEnd(7)} ${surface}/${page} ${detail || ''}`);
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function ensureDirs() {
  for (const s of surfaces) {
    fs.mkdirSync(path.join(BASE, 'current', s), { recursive: true });
    fs.mkdirSync(path.join(BASE, 'baseline', s), { recursive: true });
  }
}

function hashOf(file) {
  return createHash('sha256').update(fs.readFileSync(file)).digest('hex').slice(0, 12);
}

/** current 与 baseline 比对；无基线记 NEW，不同记 CHANGED（人工复核），相同 SAME */
function compareToBaseline(shotFile, surface, label) {
  const rel = path.relative(path.join(BASE, 'current'), shotFile);
  const baseFile = path.join(BASE, 'baseline', rel);
  if (!fs.existsSync(baseFile)) return rec(surface, label, 'NEW', path.basename(shotFile));
  const same = hashOf(shotFile) === hashOf(baseFile);
  return rec(
    surface,
    label,
    same ? 'SAME' : 'CHANGED',
    same ? '' : `看 current/${rel} vs baseline/${rel}`
  );
}

function promoteAll() {
  let n = 0;
  for (const s of surfaces) {
    const cur = path.join(BASE, 'current', s);
    const bas = path.join(BASE, 'baseline', s);
    if (!fs.existsSync(cur)) continue;
    fs.mkdirSync(bas, { recursive: true });
    for (const f of fs.readdirSync(cur)) {
      if (!f.endsWith('.png')) continue;
      fs.copyFileSync(path.join(cur, f), path.join(bas, f));
      n++;
    }
  }
  console.log(`[promote] 已把 ${n} 张 current 认作新基线`);
}

async function apiFetch(p, init) {
  const r = await fetch(API + p, init);
  const t = await r.text();
  try {
    return { status: r.status, body: JSON.parse(t) };
  } catch {
    return { status: r.status, body: { raw: t.slice(0, 200) } };
  }
}

function redisGet(key) {
  return execFileSync(
    'docker',
    ['exec', 'ai-cabinet-redis-1', 'redis-cli', '-a', 'devredis', '--no-auth-warning', 'GET', key],
    { encoding: 'utf8' }
  )
    .trim()
    .replaceAll('"', '');
}

function psqlOne(sql) {
  return execFileSync(
    'docker',
    ['exec', 'ai-cabinet-postgres-1', 'psql', '-U', 'aicabinet', '-d', 'aicabinet', '-tAc', sql],
    { encoding: 'utf8' }
  ).trim();
}

/** 运营后台登录：图形验证码码值直接从 redis 取（本机 dev 栈口令 devredis） */
async function adminLogin() {
  const cap = await apiFetch('/api/v2/auth/captcha');
  const captchaId = cap.body?.data?.captchaId;
  if (!captchaId) throw new Error('captcha 获取失败：后端网关是否在跑？');
  const captchaCode = redisGet(`aicabinet:captcha:${captchaId}`);
  const r = await apiFetch('/api/v2/auth/admin-password-login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      phoneNumber: process.env.UI_ADMIN_PHONE || '13900000001',
      password: process.env.UI_ADMIN_PASSWORD || '123456',
      captchaId,
      captchaCode
    })
  });
  const d = r.body?.data;
  if (!d?.token) throw new Error('admin 登录失败：' + JSON.stringify(r.body).slice(0, 200));
  return d;
}

async function runAdmin() {
  const { chromium } = require('playwright');
  let d;
  try {
    d = await adminLogin();
  } catch (e) {
    return rec('admin', '-', 'SKIP', e.message.slice(0, 120));
  }
  const browser = await chromium
    .launch({ headless: true, channel: 'chrome' })
    .catch(() => chromium.launch({ headless: true }));
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  const page = await context.newPage();
  await page.goto(`${ADMIN_APP}/login`, { waitUntil: 'domcontentloaded' });
  await page.evaluate((tok) => {
    sessionStorage.setItem('admin_token', tok.token);
    sessionStorage.setItem(
      'admin_token_expires',
      String(Date.now() + (tok.expiresInSeconds || 1800) * 1000)
    );
    localStorage.setItem('admin_userId', String(tok.userId));
  }, d);

  for (const theme of themes) {
    for (const pg of PAGES.admin) {
      const errs = [];
      const onConsole = (m) => {
        if (m.type() === 'error') errs.push(m.text().slice(0, 160));
      };
      const onPageError = (e) => errs.push('pageerror: ' + e.message.slice(0, 160));
      page.on('console', onConsole);
      page.on('pageerror', onPageError);
      try {
        await page.goto(ADMIN_APP + pg.path, { waitUntil: 'networkidle' }).catch(() => {});
        if (theme !== 'light') {
          await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), theme);
        }
        await page.waitForTimeout(1800);
        const textLen = await page.evaluate(() => (document.body.innerText || '').trim().length);
        const label = theme === 'light' ? pg.name : `${pg.name}-${theme}`;
        const shotFile = path.join(BASE, 'current', 'admin', `${label}.png`);
        if (textLen < 50) {
          const staleDep = errs.some((t) =>
            /Outdated Optimize Dep|Failed to fetch dynamically imported module/i.test(t)
          );
          rec(
            'admin',
            label,
            'FAIL',
            staleDep
              ? `dev server 依赖预构建缓存过期（504 Outdated Optimize Dep）⇒ 重启 vite（或 --force），非页面缺陷`
              : `疑似白屏（文本 ${textLen} 字）`
          );
        } else if (errs.length) {
          rec('admin', label, 'FAIL', `console error ×${errs.length}: ${errs[0]}`);
        } else {
          await page.screenshot({ path: shotFile, fullPage: true });
          compareToBaseline(shotFile, 'admin', label);
        }
      } catch (e) {
        rec('admin', pg.name, 'FAIL', e.message.slice(0, 120));
      } finally {
        page.off('console', onConsole);
        page.off('pageerror', onPageError);
      }
    }
  }
  await browser.close();
}

/** 小程序登录：consumer 走短信（码值读库），merchant 走密码登录 */
async function consumerLoginToken() {
  const phone = process.env.UI_CONSUMER_PHONE || '13800138000';
  const cap = await apiFetch('/api/v2/auth/captcha');
  const captchaId = cap.body?.data?.captchaId;
  if (!captchaId) throw new Error('captcha 获取失败');
  const captchaCode = redisGet(`aicabinet:captcha:${captchaId}`);
  execFileSync(
    'docker',
    [
      'exec',
      'ai-cabinet-redis-1',
      'redis-cli',
      '-a',
      'devredis',
      '--no-auth-warning',
      'DEL',
      `aicabinet:sms:send:cd:${phone}`,
      `aicabinet:sms:send:hr:${phone}`
    ],
    { stdio: 'ignore' }
  );
  await apiFetch(
    `/api/v2/auth/sms-code?phoneNumber=${phone}&captchaId=${captchaId}&captchaCode=${captchaCode}`,
    { method: 'POST' }
  );
  const sms = psqlOne(
    `select code from sms_verification_code where phone_number='${phone}' order by created_at desc limit 1`
  );
  if (!sms) throw new Error('短信码未入库（后端是否在跑 / 是否命中发送频控）');
  const r = await apiFetch('/api/v2/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phoneNumber: phone, code: sms })
  });
  const d = r.body?.data;
  if (!d?.token) throw new Error('consumer 登录失败：' + JSON.stringify(r.body).slice(0, 160));
  const boot = await apiFetch('/api/v2/auth/server-boot');
  return {
    token: d.token,
    storage: [
      ['consumer_token', d.token],
      ['consumer_user_id', String(d.userId ?? '')],
      ['consumer_token_expires', String(d.expiresAt ?? Date.now() + 86400000)],
      ['consumer_server_boot', String(boot.body?.data?.serverBootEpoch ?? '')]
    ]
  };
}

async function merchantLoginToken() {
  const r = await apiFetch('/api/v2/auth/merchant-password-login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      phoneNumber: process.env.UI_MERCHANT_PHONE || '13800138001',
      password: process.env.UI_MERCHANT_PASSWORD || '123456'
    })
  });
  const d = r.body?.data;
  if (!d?.token) throw new Error('merchant 登录失败：' + JSON.stringify(r.body).slice(0, 160));
  return {
    token: d.token,
    storage: [
      ['merchant_token', d.token],
      ['merchant_user_id', String(d.userId ?? '')],
      ['merchant_server_boot', '1']
    ]
  };
}

async function runMp(surface) {
  const automator = require('miniprogram-automator');
  const port = MP_PORT[surface];
  let mp;
  try {
    mp = await automator.connect({ wsEndpoint: `ws://127.0.0.1:${port}` });
  } catch {
    return rec(
      surface,
      '-',
      'SKIP',
      `连不上 ws://127.0.0.1:${port}——先跑 cli.bat auto --auto-port ${port}`
    );
  }
  let sess;
  try {
    sess = surface === 'consumer-mp' ? await consumerLoginToken() : await merchantLoginToken();
    for (const [k, v] of sess.storage) await mp.callWxMethod('setStorageSync', k, v);
  } catch (e) {
    await mp.disconnect();
    return rec(surface, '-', 'SKIP', e.message.slice(0, 120));
  }
  /** 单页采集；automator 偶发超时（页面 text 节点几百个，逐个 round-trip）⇒ 失败重试一次 */
  async function captureOne(pg) {
    await mp.reLaunch(pg.path);
    let texts = 0;
    let stuck = -1;
    for (let i = 0; i < 8; i++) {
      await sleep(900);
      const page = await mp.currentPage();
      if (page.path !== pg.path.replace(/^\//, '')) break;
      stuck = (await page.$$('.sk-card')).length;
      if (stuck === 0) {
        const nodes = await page.$$('text');
        for (const n of nodes.slice(0, 60)) {
          const s = await n.text();
          if (s && s.trim()) texts++;
        }
        break;
      }
    }
    if (stuck > 0) {
      return rec(
        surface,
        pg.name,
        'FAIL',
        '骨架屏 7s 未复位（多半是 API 基址连不到：网络变更后要重烤 env；或 dist 被交叉写坏）'
      );
    }
    if (texts <= 3) {
      return rec(surface, pg.name, 'FAIL', `疑似空渲染（text 节点 ${texts}）`);
    }
    const shotFile = path.join(BASE, 'current', surface, `${pg.name}.png`);
    await mp.screenshot({ path: shotFile });
    compareToBaseline(shotFile, surface, pg.name);
  }

  for (const pg of PAGES[surface]) {
    try {
      await captureOne(pg);
    } catch (e) {
      try {
        await sleep(1200);
        await captureOne(pg);
      } catch (e2) {
        rec(
          surface,
          pg.name,
          'FAIL',
          `首次 ${e.message.slice(0, 40)} ⇒ 重试仍失败 ${e2.message.slice(0, 60)}`
        );
      }
    }
  }
  await mp.disconnect();
}

(async () => {
  ensureDirs();
  console.log(
    `[ui-baseline] surfaces=${surfaces.join(',')} mode=${mode} theme=${themes.join(',')}`
  );
  if (promote) {
    promoteAll();
    return;
  }
  if (mode === 'check') {
    for (const s of surfaces) {
      const cur = path.join(BASE, 'current', s);
      if (!fs.existsSync(cur)) continue;
      for (const f of fs.readdirSync(cur).filter((x) => x.endsWith('.png'))) {
        compareToBaseline(path.join(cur, f), s, path.basename(f, '.png'));
      }
    }
  } else {
    for (const s of surfaces) {
      if (s === 'admin') await runAdmin();
      else await runMp(s);
    }
  }
  const fail = results.filter((r) => r.status === 'FAIL').length;
  const changed = results.filter((r) => r.status === 'CHANGED').length;
  const skip = results.filter((r) => r.status === 'SKIP').length;
  console.log(
    `\n[ui-baseline] FAIL=${fail} CHANGED=${changed}（人工复核）SKIP=${skip} 共 ${results.length} 项；目录 ${path.relative(ROOT, BASE)}`
  );
  process.exit(fail ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
