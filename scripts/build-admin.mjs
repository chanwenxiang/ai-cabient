/**
 * Build admin-vue → trade-service static/admin.
 * Uses node to invoke vue-tsc/vite directly (avoids pnpm script-shell issues on Windows).
 *
 * Usage:
 *   node scripts/build-admin.mjs
 *   node scripts/build-admin.mjs --skip-typecheck
 */
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const adminDir = path.join(root, 'clients', 'admin-vue');
const skipTypecheck = process.argv.includes('--skip-typecheck');

function runStep(label, binRel, args = []) {
  const bin = path.join(adminDir, 'node_modules', binRel);
  if (!existsSync(bin)) {
    console.error(`[build-admin] missing ${bin}; run pnpm install at repo root first`);
    process.exit(1);
  }
  console.log(`[build-admin] ${label}...`);
  const result = spawnSync(process.execPath, [bin, ...args], {
    cwd: adminDir,
    stdio: 'inherit',
    env: process.env
  });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

if (!skipTypecheck) {
  runStep('type-check', 'vue-tsc/bin/vue-tsc.js', ['--noEmit']);
}
runStep('vite build', 'vite/bin/vite.js', ['build']);
console.log('[build-admin] done → services/trade-service/src/main/resources/static/admin');
