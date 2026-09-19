// P0-10 A/B 漂移验证：证明「两端 mp 的新单测」真的抓得住回归（不是恒绿摆设）
// 用法（容器内）：node /out/ab-drift.mjs
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const BUILD = '/build';
const OUT = '/out/ab-drift.txt';

const CASES = [
  {
    id: 'M1',
    pkg: '@aicabinet/merchant-mp',
    file: `${BUILD}/clients/merchant-mp/src/utils/todo-list.ts`,
    desc: 'structuredExpiry 互斥失守：hasExpiryApi 恒真',
    from: 'const hasExpiryApi = expiryItems.length > 0;',
    to: 'const hasExpiryApi = true;',
    expectRed: true
  },
  {
    id: 'M2',
    pkg: '@aicabinet/merchant-mp',
    file: `${BUILD}/clients/merchant-mp/src/utils/todo-list.ts`,
    desc: '临期行不再按 OPEN 过滤（终态行混进待办）',
    from: ".filter((e) => String(e.status || 'OPEN').toUpperCase() === 'OPEN')",
    to: '.filter(() => true)',
    expectRed: true
  },
  {
    id: 'M3',
    pkg: '@aicabinet/merchant-mp',
    file: `${BUILD}/clients/merchant-mp/src/utils/todo-list.ts`,
    desc: '反假红：只改注释，逻辑零变化',
    from: '/** 与待办页同一套合并/去重，供角标与列表共用 */',
    to: '/** 与待办页同一套合并/去重，供角标与列表共用（comment-only probe）*/',
    expectRed: false
  },
  {
    id: 'C1',
    pkg: '@aicabinet/consumer-mp',
    file: `${BUILD}/clients/consumer-mp/src/utils/dispute-form.ts`,
    desc: 'C-10 失守：无 chip 时客户端开始"猜"库存回补',
    from: '  if (chip && typeof chip.restoreInventory === \'boolean\') {\n    return chip.restoreInventory;\n  }\n  return undefined;',
    to: '  if (chip && typeof chip.restoreInventory === \'boolean\') {\n    return chip.restoreInventory;\n  }\n  return _reason.length > 0;',
    expectRed: true
  },
  {
    id: 'C2',
    pkg: '@aicabinet/consumer-mp',
    file: `${BUILD}/clients/consumer-mp/src/utils/account.ts`,
    desc: 'C-21 失守：可用余额负值被钳制为 0',
    from: '  return bal - frz;',
    to: '  return Math.max(0, bal - frz);',
    expectRed: true
  },
  {
    id: 'C3',
    pkg: '@aicabinet/consumer-mp',
    file: `${BUILD}/clients/consumer-mp/src/utils/account.ts`,
    desc: '反假红：只改注释，逻辑零变化',
    from: 'export type EntryChannel =',
    to: '// comment-only probe\nexport type EntryChannel =',
    expectRed: false
  }
];

function sha256(text) {
  return createHash('sha256').update(text).digest('hex').slice(0, 16);
}

function runTests(pkg) {
  const res = spawnSync('sh', ['-c', `cd ${BUILD} && pnpm --filter ${pkg} test 2>&1`], {
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024
  });
  const out = `${res.stdout || ''}${res.stderr || ''}`;
  const exitCode = typeof res.status === 'number' ? res.status : -1;
  const testsLine = (out.match(/^\s*Tests\s+.*$/m) || ['(no Tests line)'])[0].trim();
  const filesLine = (out.match(/^\s*Test Files\s+.*$/m) || ['(no Test Files line)'])[0].trim();
  const failedCases = out
    .split('\n')
    .filter((l) => l.includes('×') || /FAIL\s/.test(l))
    .map((l) => l.trim())
    .filter(Boolean)
    .slice(0, 40);
  return { exitCode, testsLine, filesLine, failedCases, raw: out };
}

const lines = [];
const push = (s = '') => {
  lines.push(s);
  console.log(s);
};

push('P0-10 A/B 漂移验证（容器 node:24-slim + pnpm 9.15.9）');
push(`生成时间：${new Date().toISOString()}`);
push('');

// ---------- 基线 ----------
push('=== 基线（未漂移） ===');
for (const pkg of ['@aicabinet/consumer-mp', '@aicabinet/merchant-mp']) {
  const r = runTests(pkg);
  push(`[${pkg}] exit=${r.exitCode} | ${r.filesLine} | ${r.testsLine}`);
}
push('');

// ---------- 逐用例 ----------
const results = [];
for (const c of CASES) {
  const original = readFileSync(c.file, 'utf8');
  const beforeHash = sha256(original);
  const occurrences = original.split(c.from).length - 1;
  if (occurrences !== 1) {
    results.push({ ...c, verdict: `❌ 锚点出现 ${occurrences} 次（应为 1），本用例作废` });
    push(`[${c.id}] ❌ 锚点出现 ${occurrences} 次，跳过`);
    continue;
  }
  writeFileSync(c.file, original.replace(c.from, c.to));
  const patched = readFileSync(c.file, 'utf8');
  const patchedOk = patched !== original;

  const r = runTests(c.pkg);
  const wentRed = r.exitCode !== 0;

  // 无条件还原，并校验逐字还原
  writeFileSync(c.file, original);
  const restored = readFileSync(c.file, 'utf8');
  const afterHash = sha256(restored);
  const restoredOk = afterHash === beforeHash && restored === original;

  const match = wentRed === c.expectRed;
  push(`[${c.id}] ${c.desc}`);
  push(`      pkg=${c.pkg} expectRed=${c.expectRed} actualExit=${r.exitCode} wentRed=${wentRed}`);
  push(`      ${r.filesLine} | ${r.testsLine}`);
  push(`      patchApplied=${patchedOk} restoreSha256=${beforeHash}->${afterHash} restoreOk=${restoredOk}`);
  for (const fc of r.failedCases) push(`      失败：${fc}`);
  push(`      VERDICT: ${match ? '✅ 符合期望' : '❌ 不符期望'}`);
  push('');

  results.push({ ...c, exitCode: r.exitCode, wentRed, match, restoredOk, beforeHash, afterHash, testsLine: r.testsLine });
}

push('=== 汇总 ===');
const ok = results.filter((r) => r.match).length;
push(`${ok}/${results.length} 条符合期望`);
for (const r of results) {
  push(`  ${r.id} ${r.desc} → exit=${r.exitCode} 期望红=${r.expectRed} ${r.match ? '✅' : '❌'}`);
}

writeFileSync(OUT, `${lines.join('\n')}\n`);
console.log(`\n[written] ${OUT}`);
