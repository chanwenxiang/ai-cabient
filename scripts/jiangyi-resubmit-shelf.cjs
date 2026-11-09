// 重提将邑真实在架清单（§7.11，一次提交=该层最终库存，未列出即下架）。
// 用法：
//   1) 编辑 .tmp/jy-real-shelf.json 填真实数量/售价（元）；
//   2) 干跑：node scripts/jiangyi-resubmit-shelf.cjs            （只打印，不写厂商）
//   3) 真提交：node scripts/jiangyi-resubmit-shelf.cjs --commit  （需显式确认下架语义）
// 凭据/登录走本地 ops（13900000001 + redis 验证码），不打印密码/token。
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const BASE = process.env.BASE_URL || 'http://127.0.0.1';
const DEVICE = process.env.DEVICE_ID || 'EG943322621391';
const SHELF_FILE = process.argv[2] && !process.argv[2].startsWith('--') ? process.argv[2] : '.tmp/jy-real-shelf.json';
const COMMIT = process.argv.includes('--commit');

const j = async (r) => { const t = await r.text(); try { return { status: r.status, body: JSON.parse(t) }; } catch { return { status: r.status, body: { raw: t.slice(0, 200) } }; } };

async function login() {
  const cap = await j(await fetch(`${BASE}/api/v2/auth/captcha`));
  const id = cap.body?.data?.captchaId ?? cap.body?.data?.id;
  const code = execSync(`docker exec ai-cabinet-redis-1 redis-cli -a devredis --no-auth-warning GET "aicabinet:captcha:${id}"`).toString().trim();
  const pw = (fs.readFileSync(path.join(__dirname, '../.tmp/admin-gather.mjs'), 'utf8').match(/password: '([^']+)'/) || [])[1];
  const r = await j(await fetch(`${BASE}/api/v2/auth/admin-password-login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phoneNumber: '13900000001', password: pw, captchaId: id, captchaCode: code }) }));
  if (!r.body?.data?.token) { console.error('LOGIN FAIL'); process.exit(1); }
  return r.body.data.token;
}

(async () => {
  if (!fs.existsSync(SHELF_FILE)) { console.error('缺少清单文件：' + SHELF_FILE + '（先填真实数量/售价）'); process.exit(2); }
  const shelf = JSON.parse(fs.readFileSync(SHELF_FILE, 'utf8'));
  const lines = (shelf.lines || []).map(l => ({ barCode: String(l.barCode), stockNum: Number(l.stockNum), salePriceCents: Math.round(Number(l.salePriceYuan) * 100) }));
  if (!lines.length) { console.error('清单为空：§7.11 空清单清层语义未证实，拒绝提交'); process.exit(2); }
  for (const l of lines) {
    if (!l.barCode || !Number.isFinite(l.stockNum) || l.stockNum < 0 || !Number.isFinite(l.salePriceCents) || l.salePriceCents <= 0) { console.error('行不合法：' + JSON.stringify(l)); process.exit(2); }
  }
  console.log(`设备=${DEVICE} 层=${shelf.floorNum || 1} 行数=${lines.length}`);
  for (const l of lines) console.log(`  ${l.barCode}  库存=${l.stockNum}  售价=${(l.salePriceCents / 100).toFixed(2)}元`);
  console.log('⚠️ 该层未列出的商品将被下架。');
  if (!COMMIT) { console.log('\n[dry-run] 未提交。确认无误后加 --commit 真提交。'); return; }

  const token = await login();
  const H = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };
  const sub = await j(await fetch(`${BASE}/api/v2/ops/admin/devices/${DEVICE}/jiangyi/restock`, { method: 'POST', headers: H, body: JSON.stringify({ floorNum: shelf.floorNum || 1, lines, confirmDelist: true }) }));
  if (sub.body?.code !== 0) { console.error('提交失败：', sub.status, JSON.stringify(sub.body).slice(0, 300)); process.exit(1); }
  const historyNo = sub.body.data.historyNo;
  console.log('已提交 historyNo=' + historyNo + '，轮询 §7.12…');
  for (let i = 0; i < 24; i++) {
    await new Promise(r => setTimeout(r, 5000));
    const pr = await j(await fetch(`${BASE}/api/v2/ops/admin/devices/${DEVICE}/jiangyi/restock/${historyNo}`, { headers: H }));
    const d = pr.body?.data || {};
    console.log(`#${i + 1} stage=${d.stage} ready=${d.ready} ${d.applied}/${d.total}`);
    if (d.ready) { console.log('✅ 识别参数已应用，可正常购物/验识别。'); return; }
  }
  console.log('⚠️ 2 分钟内未 ready，请稍后在后台「同步上架」面板查看进度。');
})().catch(e => { console.error('ERR', e.message); process.exit(1); });