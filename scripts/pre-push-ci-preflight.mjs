#!/usr/bin/env node
/**
 * 推送前 CI 预检（本机可跑的子集，与 ci.yml 对齐口径）。
 *
 * 用法：
 *   node scripts/pre-push-ci-preflight.mjs           # 默认：format + lint + audit-gates + CI-only 门禁
 *   node scripts/pre-push-ci-preflight.mjs --quick   # 仅 format + lint + line-endings
 *   node scripts/pre-push-ci-preflight.mjs --full    # 默认 + 改动面类型检查 / admin 产物
 *
 * 设计：
 * - 一律 `node …` / `node node_modules/…` 直调，避免本机 pnpm script-shell 假绿（见 ~/.workbuddy MEMORY）。
 * - 不能替代 e2e-h5（需整栈）；推前声明该限制，禁止宣称「CI 必绿」。
 * - 失败 exit 1；Agent / 人工推送前必须跑通本脚本（规则 pre-push-ci-green）。
 */
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = new Set(process.argv.slice(2));
const quick = args.has('--quick');
const full = args.has('--full') || (!quick && args.has('--with-build'));

const node = process.execPath;

function run(label, cmd, cmdArgs, opts = {}) {
  console.log(`\n── ${label} ──`);
  console.log(`$ ${cmd} ${cmdArgs.join(' ')}`);
  const r = spawnSync(cmd, cmdArgs, {
    cwd: root,
    stdio: 'inherit',
    shell: false,
    env: { ...process.env, ...opts.env }
  });
  const code = r.status ?? 1;
  if (code !== 0) {
    console.error(`\n✗ FAIL: ${label} (exit ${code})`);
    process.exit(code);
  }
  console.log(`✓ ${label}`);
}

function gitDiffNames(pattern) {
  const r = spawnSync('git', ['diff', '--name-only', 'origin/dev...HEAD'], {
    cwd: root,
    encoding: 'utf8',
    shell: false
  });
  // also include unstaged/staged working tree vs HEAD
  const r2 = spawnSync('git', ['diff', '--name-only', 'HEAD'], {
    cwd: root,
    encoding: 'utf8',
    shell: false
  });
  const r3 = spawnSync('git', ['diff', '--name-only', '--cached'], {
    cwd: root,
    encoding: 'utf8',
    shell: false
  });
  const names = new Set(
    `${r.stdout || ''}\n${r2.stdout || ''}\n${r3.stdout || ''}`
      .split(/\r?\n/)
      .map((s) => s.trim())
      .filter(Boolean)
  );
  if (!pattern) return [...names];
  return [...names].filter((n) => pattern.test(n));
}

const prettier = resolve(root, 'node_modules/prettier/bin/prettier.cjs');
const eslint = resolve(root, 'node_modules/eslint/bin/eslint.js');

if (!existsSync(prettier) || !existsSync(eslint)) {
  console.error('缺少 node_modules（prettier/eslint）。先: pnpm install 或在有依赖的环境跑。');
  process.exit(1);
}

console.log('pre-push-ci-preflight: mode =', quick ? 'quick' : full ? 'full' : 'default');
console.log('注意: 本脚本不跑 e2e-h5 / mvn verify；那些仍依赖 CI 或本机整栈。');

// 1) Format（与 ci.yml `pnpm format:check` 同 globs）
run('format:check', node, [
  prettier,
  '--check',
  'clients/**/*.{ts,vue,js,mjs,css,html,json}',
  'packages/**/*.{ts,json}',
  'scripts/**/*.mjs'
]);

if (quick) {
  run('line-endings', node, [resolve(root, 'scripts/check-line-endings.mjs')]);
  console.log('\n✓ quick 预检通过。推前建议再跑默认档（无 --quick）。');
  process.exit(0);
}

// 2) Lint（与 ci `pnpm lint` 对齐：eslint .）
run('lint', node, [eslint, '.']);

// 3) 审计门禁链（解析 package.json check:audit-gates）
run('audit-gates', node, [resolve(root, 'scripts/run-audit-gates.mjs')]);

// 4) CI-only 门禁（不在本机聚合链内，但 build job 会跑）
run('migration-safety', node, [resolve(root, 'scripts/check-migration-safety.mjs')]);
run('flyway-seed-separation', node, [resolve(root, 'scripts/check-flyway-seed-separation.mjs')]);

// 5) 按改动面加检
const changed = gitDiffNames();
const adminSrcChanged = changed.some((n) => n.startsWith('clients/admin-vue/src/'));
const openApiSurface = changed.some(
  (n) =>
    /services\/trade-service\/.*Controller\.java$/.test(n) ||
    /services\/common\/common-core\/.*\.java$/.test(n) ||
    n.includes('packages/shared-types/src/generated')
);

if (adminSrcChanged || full) {
  console.log('\n── admin 源码有改动 → 重建产物并核对（对齐 admin-artifacts job）──');
  run('build-admin', node, [resolve(root, 'scripts/build-admin.mjs')]);
  const st = spawnSync(
    'git',
    [
      'status',
      '--porcelain',
      '-uall',
      '--',
      'services/trade-service/src/main/resources/static/admin'
    ],
    {
      cwd: root,
      encoding: 'utf8',
      shell: false
    }
  );
  const dirty = (st.stdout || '').trim();
  if (dirty) {
    console.error('✗ static/admin 与源码不同步。请提交重建产物：\n' + dirty);
    process.exit(1);
  }
  console.log('✓ static/admin 与源码一致');
}

if (openApiSurface) {
  console.log('\n⚠ 检测到 Controller/DTO/generated 改动。');
  console.log('  CI 会跑 check:openapi-types（可能要起 trade 拉 /v3/api-docs）。');
  console.log('  请确认已: 起 trade → pnpm gen:api-types → 提交 packages/shared-types。');
  if (full && existsSync(resolve(root, '.tmp/live-openapi.json'))) {
    run('openapi-types', node, [resolve(root, 'scripts/check-openapi-types.mjs')], {
      env: { OPENAPI_CHECK_REGEN: '1', OPENAPI_FILE: '.tmp/live-openapi.json' }
    });
  } else if (full) {
    console.warn(
      '  --full 但缺少 .tmp/live-openapi.json，跳过 regen 校验（结构性 gate 仍建议本地 pnpm check:openapi-types）。'
    );
  }
}

if (full) {
  const mpChanged = changed.some((n) => n.includes('consumer-mp') || n.includes('merchant-mp'));
  if (mpChanged) {
    console.log('\n⚠ mp 源码有改动：请本机另跑（对齐 CI mini-programs）：');
    console.log('  pnpm --filter @aicabinet/consumer-mp run type-check');
    console.log('  pnpm --filter @aicabinet/merchant-mp run type-check');
    console.log(
      '  （本机 pnpm 不可靠时，进对应 clients/*-mp 用其 package.json type-check 脚本直调）'
    );
  }
}

console.log(`
════════════════════════════════════════
✓ 预检通过（format / lint / audit-gates / migration 门禁${adminSrcChanged || full ? ' / admin 产物' : ''}）
仍不覆盖: e2e-h5、mvn verify、真 Docker IT。
推送后请看 Actions；若仅 e2e-h5 红，按该 job 日志修，勿上调 UAT_MAX_FAIL。
════════════════════════════════════════
`);
