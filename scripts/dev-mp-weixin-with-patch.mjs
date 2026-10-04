/**
 * uni -p mp-weixin 热更会把 usingComponents 写回 packages/shared-uni/...，
 * 微信基础库认不到该路径。本脚本在 watch 同时监听 dist，变更后自动跑 patch。
 */
import { spawn } from 'node:child_process';
import { existsSync, watch } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const cwd = process.cwd();
const scriptsDir = dirname(fileURLToPath(import.meta.url));
const patchScript = join(scriptsDir, 'patch-mp-weixin-appjson.mjs');
const dist = join(cwd, 'dist/dev/mp-weixin');

let patchTimer = null;
let patching = false;

function runPatch(reason) {
  if (patching) return;
  clearTimeout(patchTimer);
  patchTimer = setTimeout(() => {
    patching = true;
    const child = spawn(process.execPath, [patchScript, 'dist/dev/mp-weixin'], {
      cwd,
      stdio: 'inherit'
    });
    child.on('exit', (code) => {
      patching = false;
      if (code === 0) {
        console.log(`[dev-mp-weixin-with-patch] patched (${reason})`);
      }
    });
  }, 600);
}

function ensureWatcher() {
  if (!existsSync(dist)) {
    setTimeout(ensureWatcher, 1000);
    return;
  }
  try {
    watch(dist, { recursive: true }, (_event, filename) => {
      const name = filename ? String(filename).replace(/\\/g, '/') : '';
      if (!name || name.endsWith('.json') || name.includes('packages/shared-uni')) {
        runPatch(name || 'dist-change');
      }
    });
    runPatch('initial');
  } catch (err) {
    console.warn('[dev-mp-weixin-with-patch] watch failed:', err.message);
    setInterval(() => runPatch('poll'), 5000);
  }
}

const uni = spawn('uni', ['-p', 'mp-weixin'], { cwd, stdio: 'inherit', shell: true });
uni.on('exit', (code) => process.exit(code ?? 0));
ensureWatcher();
