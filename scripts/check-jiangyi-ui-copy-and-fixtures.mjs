#!/usr/bin/env node
/**
 * 将邑界面文案禁令 + 夹具设备隔离门禁（D+E，2026-10-27）。
 *
 * D 文案禁令：admin-vue 将邑相关界面的可读文本里，禁止出现——
 *   ① 厂商文档编号 §x.x.x（2026-10-27 用户要求全部人话化，代码注释里保留可）；
 *   ② 过时口径「将邑商户 App 完成」（采集编排已由后台采集台接管）；
 *   ③ 旧张数口径「约 2000 张/商品」（实测口径：每摄 ≥500 张轨迹裁图，窗口约 45 秒）。
 * 模板/字符串里命中即 FAIL；`//` 注释与 JSDoc 不算可读文本，跳过。
 *
 * E 夹具隔离：jiangyi_device 里 device_sn LIKE 'AUDIT%' 的审计夹具必须保持 RETIRED——
 *   2026-10-26 用户质询「演示智能柜都是假的为啥还能有接入」。防止自动化测试把
 *   夹具写回 BOUND 混进运营列表。有本地连接时实查 dev 库；无库连接时退化为
 *   源码扫描（测试代码不得把 AUDIT 夹具置回 BOUND）。
 *
 * fail-closed：目标文件缺失/零扫描按失败处理。
 *   node scripts/check-jiangyi-ui-copy-and-fixtures.mjs
 */
import { runAsync } from './lib/async-spawn.mjs';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-jiangyi-ui-copy-and-fixtures]';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

// ── D：界面文案禁令 ──────────────────────────────────────────────────────
const UI_FILES = [
  'clients/admin-vue/src/views/vision/LearningLibraryView.vue',
  'clients/admin-vue/src/views/vision/VisionMappingView.vue',
  'clients/admin-vue/src/views/skus/SkuVisionEnrollView.vue',
  'clients/admin-vue/src/components/device/DeviceJiangyiCard.vue',
  'clients/admin-vue/src/components/device/DeviceJiangyiControlCard.vue',
  'clients/admin-vue/src/components/vision/VisionFlowSteps.vue',
  'clients/merchant-mp/src/pages/device-detail/device-detail.vue'
];
const BANNED = [
  { re: /§\d+\.\d+/u, why: '厂商文档编号 §x.x（要求全部人话化）' },
  { re: /将邑商户 App 完成/, why: '过时口径：采集编排已由后台「开始采集」接管' },
  { re: /约 2000 张/u, why: '旧张数口径（实测：每摄 ≥500 张轨迹裁图，窗口约 45 秒）' }
];
let scannedFiles = 0;
for (const rel of UI_FILES) {
  const file = join(root, rel);
  if (!existsSync(file)) fail(`目标界面文件缺失：${rel}`);
  scannedFiles++;
  const src = readFileSync(file, 'utf8');
  // 剥注释（/* */、//、<!-- -->），只留模板与字符串字面量
  const noComments = src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/^\s*\/\/.*$/gm, '');
  for (const { re, why } of BANNED) {
    const m = noComments.match(re);
    if (m) fail(`${rel} 出现禁用文案「${m[0]}」——${why}`);
  }
}
console.log(`${TAG} D: ${scannedFiles} 个界面文件文案禁令零命中`);

// ── E：夹具设备隔离 ─────────────────────────────────────────────────────
// 🔴 子进程一律走 async-spawn（lessons #292：同步 execSync 在 WorkBuddy 下 EBUSY 假红）
let eMode = 'skip';
const runE = async () => {
  const db = await runAsync(
    'docker',
    [
      'exec',
      'ai-cabinet-postgres-1',
      'psql',
      '-U',
      'aicabinet',
      '-d',
      'aicabinet',
      '-t',
      '-A',
      '-c',
      "SELECT device_id || '|' || status FROM jiangyi_device WHERE device_sn LIKE 'AUDIT%'"
    ],
    { cwd: root }
  );
  if (db.spawnError || db.status !== 0) {
    // 无 docker/无库（CI 等）→ 退化源码扫描：测试代码不得把 AUDIT 夹具置回 BOUND
    const grep = await runAsync(
      'git',
      ['grep', '-n', '-E', 'AUDIT[^"]*', '--', 'services/trade-service/src/test'],
      { cwd: root }
    );
    const hits = (grep.stdout || '').split('\n').filter((l) => /BOUND/.test(l) && /AUDIT/.test(l));
    if (hits.length) {
      fail(`测试源码把 AUDIT 夹具置回 BOUND：\n${hits.join('\n')}`);
    }
    eMode = '无库连接，退化源码扫描零命中';
    return;
  }
  const rows = (db.stdout || '').split('\n').filter(Boolean);
  for (const row of rows) {
    const [deviceId, status] = row.split('|');
    if (status !== 'RETIRED') {
      fail(`审计夹具设备 ${deviceId} 状态为 ${status}（必须 RETIRED）——测试夹具混进了运营面`);
    }
  }
  eMode = `dev 库实查 ${rows.length} 台夹具全部 RETIRED`;
};
await runE();
console.log(`${TAG} E: ${eMode}`);
console.log(`${TAG} OK：文案禁令与夹具隔离全部通过`);
