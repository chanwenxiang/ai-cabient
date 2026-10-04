/**
 * mp-weixin 产物修补：
 * 1. app.json 补 lazyCodeLoading
 * 2. 共享组件拍平到根 `components/`（微信把 `packages/`/`pkg/` 当特殊空间）。
 * 3. usingComponents 一律写成相对当前 json 的路径。
 *    开发者工具 2.02 会把 `/components/x` 收成页目录下的 `components/x`
 *    → 登录页报 Component is not found。
 */
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  statSync,
  writeFileSync
} from 'node:fs';
import { dirname, join, relative, normalize } from 'node:path';

const roots = process.argv.slice(2);
const targets =
  roots.length > 0
    ? roots
    : [join(process.cwd(), 'dist/dev/mp-weixin'), join(process.cwd(), 'dist/build/mp-weixin')];

const REQUIRED_SHARED_COMPONENTS = [
  'app-nav-bar',
  'app-button',
  'empty-state',
  'error-state',
  'privacy-consent-modal'
];
const SHARED_COMPONENTS = [...REQUIRED_SHARED_COMPONENTS, 'app-underline-tabs'];

const SHARED_COMPONENT_RE = /(?:^|\/)(?:packages|pkg)\/shared-uni\/src\/components\/([^/"']+)/;

function walkJson(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (name === 'node-modules' || name === 'node_modules' || name === 'packages' || name === 'pkg')
      continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walkJson(p, out);
    else if (name.endsWith('.json')) out.push(p);
  }
  return out;
}

function remapSharedUniPath(spec) {
  let s = String(spec).replace(/\\/g, '/');
  const m = s.match(SHARED_COMPONENT_RE);
  if (m) return `/components/${m[1]}`.replace(/\.vue$/i, '');
  return s.replace(/^components\//, '/components/');
}

function toPageRelativeComponentPath(mpRoot, jsonFile, spec) {
  const s = remapSharedUniPath(spec);
  if (!s || s.startsWith('plugin://') || s.startsWith('wx://')) return s;
  const fromDir = dirname(jsonFile);
  let resolved;
  if (s.startsWith('/')) {
    resolved = normalize(join(mpRoot, s.replace(/^\//, '').replace(/\.vue$/i, '')));
  } else {
    resolved = normalize(join(fromDir, s.replace(/\.vue$/i, '')));
  }
  const remappedRel = remapSharedUniPath(`/${relative(mpRoot, resolved).replace(/\\/g, '/')}`);
  if (remappedRel.startsWith('/components/')) {
    resolved = normalize(join(mpRoot, remappedRel.replace(/^\//, '').replace(/\.(vue|js)$/i, '')));
  }
  const noExt = resolved.replace(/\.(vue|js)$/i, '');
  if (!existsSync(`${noExt}.js`) && !existsSync(`${noExt}.json`)) return s;
  let rel = relative(fromDir, noExt).replace(/\\/g, '/');
  if (!rel.startsWith('.')) rel = `./${rel}`;
  return rel.replace(/\.(vue|js)$/i, '');
}

function rewriteUsingComponents(mpRoot, jsonFile, obj) {
  const uc = obj?.usingComponents;
  if (!uc || typeof uc !== 'object') return false;
  let changed = false;
  for (const [k, v] of Object.entries(uc)) {
    if (typeof v !== 'string') continue;
    const next = toPageRelativeComponentPath(mpRoot, jsonFile, v);
    if (next !== v) {
      uc[k] = next;
      changed = true;
    }
  }
  return changed;
}

function copySharedComponentsToRoot(mpRoot) {
  const srcDir = join(mpRoot, 'packages', 'shared-uni', 'src', 'components');
  const dstDir = join(mpRoot, 'components');
  if (!existsSync(srcDir)) {
    console.warn(`[patch-mp-weixin] skip flatten: missing ${relative(process.cwd(), srcDir)}`);
    return false;
  }
  mkdirSync(dstDir, { recursive: true });
  let copied = 0;
  for (const name of SHARED_COMPONENTS) {
    const srcJs = join(srcDir, `${name}.js`);
    if (!existsSync(srcJs)) continue;
    for (const ext of ['.js', '.json', '.wxml', '.wxss']) {
      const src = join(srcDir, `${name}${ext}`);
      if (!existsSync(src)) continue;
      const dst = join(dstDir, `${name}${ext}`);
      if (ext === '.js') {
        const text = readFileSync(src, 'utf8').replace(
          /require\("(?:\.\.\/)+common\/vendor\.js"\)/g,
          'require("../common/vendor.js")'
        );
        writeFileSync(dst, text);
      } else if (ext === '.json') {
        const json = JSON.parse(readFileSync(src, 'utf8'));
        rewriteUsingComponents(mpRoot, dst, json);
        writeFileSync(dst, `${JSON.stringify(json, null, 2)}\n`);
      } else {
        copyFileSync(src, dst);
      }
      copied += 1;
    }
  }
  return copied > 0;
}

/** 拍平失败时直接红，避免开发者工具只剩 Component is not found */
function assertFlattenedComponents(mpRoot) {
  const missing = [];
  for (const name of REQUIRED_SHARED_COMPONENTS) {
    for (const ext of ['.js', '.json', '.wxml']) {
      const p = join(mpRoot, 'components', `${name}${ext}`);
      if (!existsSync(p)) missing.push(`components/${name}${ext}`);
    }
  }
  if (missing.length) {
    throw new Error(
      `[patch-mp-weixin] flattened shared components missing under ${mpRoot}:\n  - ${missing.join('\n  - ')}\n` +
        'Close WeChat DevTools lock on dist/dev and rebuild (patch must run AFTER sync).'
    );
  }
}

function assertNoReservedComponentPaths(mpRoot, jsonFile, obj) {
  const uc = obj?.usingComponents;
  if (!uc || typeof uc !== 'object') return;
  for (const v of Object.values(uc)) {
    if (typeof v !== 'string') continue;
    const norm = v.replace(/\\/g, '/');
    if (/(?:^|\/)(?:packages|pkg)\//.test(norm)) {
      throw new Error(
        `usingComponents still points at reserved path "${v}" in ${relative(mpRoot, jsonFile)}`
      );
    }
    // 根绝对路径在 2.02 会被收成页内相对路径，禁止再写出 `/components/...`
    if (norm.startsWith('/components/')) {
      throw new Error(
        `usingComponents still uses root-absolute "${v}" in ${relative(mpRoot, jsonFile)} (use ../components/...)`
      );
    }
  }
}

let patched = 0;
for (const dir of targets) {
  const appFile = join(dir, 'app.json');
  if (!existsSync(appFile)) continue;

  const flattened = copySharedComponentsToRoot(dir);
  if (flattened) console.log(`flattened  ${relative(process.cwd(), join(dir, 'components'))}`);
  assertFlattenedComponents(dir);

  const app = JSON.parse(readFileSync(appFile, 'utf8'));
  let appChanged = false;
  if (app.lazyCodeLoading !== 'requiredComponents') {
    app.lazyCodeLoading = 'requiredComponents';
    appChanged = true;
  }

  const globalUc = { ...(app.usingComponents || {}) };
  for (const name of SHARED_COMPONENTS) {
    const rel = `components/${name}`;
    if (!existsSync(join(dir, `${rel}.js`))) continue;
    const pageRel = `./${rel}`;
    if (globalUc[name] !== pageRel) {
      globalUc[name] = pageRel;
      appChanged = true;
    }
  }
  app.usingComponents = globalUc;
  if (rewriteUsingComponents(dir, appFile, app)) appChanged = true;
  assertNoReservedComponentPaths(dir, appFile, app);

  for (const file of walkJson(dir)) {
    if (file === appFile) continue;
    let json;
    try {
      json = JSON.parse(readFileSync(file, 'utf8'));
    } catch {
      continue;
    }
    const changed = rewriteUsingComponents(dir, file, json);
    assertNoReservedComponentPaths(dir, file, json);
    if (!changed) continue;
    writeFileSync(file, `${JSON.stringify(json, null, 2)}\n`);
    patched += 1;
    console.log(`patched-uc  ${relative(dir, file)}`);
  }

  if (appChanged) {
    writeFileSync(appFile, `${JSON.stringify(app, null, 2)}\n`);
    patched += 1;
    console.log(`patched  ${appFile}`);
  } else {
    console.log(`ok  ${appFile}`);
  }

  for (const cfgName of ['project.config.json', 'project.private.config.json']) {
    const cfgFile = join(dir, cfgName);
    if (!existsSync(cfgFile)) continue;
    const cfg = JSON.parse(readFileSync(cfgFile, 'utf8'));
    cfg.setting = { ...(cfg.setting || {}), ignoreDevUnusedFiles: false };
    writeFileSync(cfgFile, `${JSON.stringify(cfg, null, 2)}\n`);
    console.log(`patched-setting  ${relative(process.cwd(), cfgFile)} ignoreDevUnusedFiles=false`);
  }
}
if (!patched && targets.every((d) => !existsSync(join(d, 'app.json')))) {
  console.warn('no app.json found under', targets.join(', '));
}
