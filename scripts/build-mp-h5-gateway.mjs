/**
 * 把消费者/商户 H5 打到网关子路径（`/consumer/`、`/merchant/`）。
 *
 * 日常联调仍用 `dev:h5`（:3002 / :3001）。本脚本只服务 docker-full 网关托管。
 * 构建会临时改 manifest `h5.router.base`，失败也会还原。
 */
import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

function patchManifest(app, base) {
  const file = join(root, 'clients', app, 'src/manifest.json');
  const json = JSON.parse(readFileSync(file, 'utf8'));
  json.h5 = json.h5 || {};
  json.h5.router = { mode: 'history', base };
  writeFileSync(file, `${JSON.stringify(json, null, 2)}\n`);
}

function buildApp(app, base, destRel) {
  const dest = join(root, destRel);
  patchManifest(app, base);
  try {
    const r = spawnSync(process.execPath, ['./node_modules/@dcloudio/vite-plugin-uni/bin/uni.js', 'build'], {
      cwd: join(root, 'clients', app),
      env: { ...process.env, UNI_H5_BASE: base },
      stdio: 'inherit'
    });
    if (r.status !== 0) {
      throw new Error(`${app} H5 build failed`);
    }
    mkdirSync(dest, { recursive: true });
    cpSync(join(root, 'clients', app, 'dist/build/h5'), dest, { recursive: true });
    console.log(`[build-mp-h5-gateway] copied ${app} → ${destRel}`);
  } finally {
    patchManifest(app, '/');
  }
}

buildApp('consumer-mp', '/consumer/', 'infra/gateway/mp-h5/consumer');
buildApp('merchant-mp', '/merchant/', 'infra/gateway/mp-h5/merchant');
console.log('[build-mp-h5-gateway] OK。然后：docker exec ai-cabinet-gateway-1 nginx -s reload');
