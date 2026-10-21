#!/usr/bin/env node
/**
 * 门禁：infra/docker-compose*.yml 的端口映射必须绑定回环（127.0.0.1 / ::1），
 * 防止服务绕过网关直接暴露宿主（审计 H04b/M16）。
 *
 * 审计 P0-6 四形态修法（此前只覆盖形态①，其余 35 条从不检查）：
 *   ① 块形态 `ports:` + `- "host:ctr"` 列表（注释/空行不再截断块——原 :39 缺陷）
 *   ② 标签块 `ports: !override` / `ports: !reset`（win-ports 备用栈在用）
 *   ③ 内联数组 `ports: ["host:ctr", ...]`（full.yml 14 条在用）
 *   ④ host 侧 `${VAR:-127.0.0.1}` 变量默认值——取默认值判定；无默认值或默认非回环 → 违规
 *
 * 豁免（ALLOWLIST）：面向外部的业务入口。新增暴露端口：要么绑回环，要么带书面理由加入豁免。
 *   A6 防无声滥加：ALLOWLIST 命中只是半个条件——compose 里对应端口行**必须**带
 *   `# EXPOSE-ALLOW: <理由>` 行尾注释（双处留痕：脚本侧登记 + compose 侧 diff 可见），
 *   缺标记即 FAIL。只改脚本一行无法再无声放行新暴露端口。
 *
 * 用法：
 *   node scripts/check-compose-ports.mjs             # 检查真实 compose 文件
 *   node scripts/check-compose-ports.mjs --self-test # 负向自测（四形态各 1 例违规必须变红）
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const INFRA = 'infra';

/** 豁免清单：键 = "文件名:宿主端口"，值 = 理由 */
const ALLOWLIST = new Map([
  ['docker-compose.full.yml:80', 'gateway 业务入口（全栈部署对外唯一 HTTP）'],
  ['docker-compose.yml:80', 'gateway 业务入口（base 栈）']
]);

/** 解析单文件 → [{file, line, spec, comment}]，覆盖四形态。注释/空行对块形态透明。 */
export function parsePortEntries(src) {
  const lines = src.split(/\r?\n/);
  const entries = [];
  let inBlock = false;
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^(\s*)ports:(?:\s+(!\S+))?\s*(\[\s*\]\s*)?$/);
    if (m) {
      // 形态②：标签块（!override / !reset），后续 `- ` 行照收
      inBlock = true;
      continue;
    }
    const inline = lines[i].match(/^\s*ports:\s*(\[.*\]|!\S+\s*\[.*\])\s*$/);
    if (inline) {
      // 形态③：内联数组
      for (const spec of inline[1].matchAll(/"([^"]+)"/g)) {
        entries.push({ line: i + 1, spec: spec[1], comment: '' });
      }
      inBlock = false;
      continue;
    }
    if (inBlock) {
      const s = lines[i].trim();
      if (s.startsWith('- ')) {
        const entry = s.match(/^-\s+"?([^"]+?)"?\s*(?:#\s*(.*))?\s*$/);
        if (entry) {
          entries.push({ line: i + 1, spec: entry[1], comment: (entry[2] || '').trim() });
        }
        continue;
      }
      if (!s || s.startsWith('#')) continue; // 审计 P0-6：注释/空行不再截断块
      inBlock = false;
    }
  }
  return entries.map((e) => ({ ...e, file: '' }));
}

/** 判定 host 侧是否回环。支持字面量 IP 与 ${VAR:-127.0.0.1} 变量默认值（④）。 */
export function isLoopbackHost(hostPart) {
  if (/^(127\.0\.0\.1|::1|\[::1\])/.test(hostPart)) return true;
  const m = hostPart.match(/^\$\{[^:}]+:-(.+)\}$/);
  return Boolean(m && /^(127\.0\.0\.1|::1)$/.test(m[1].trim()));
}

/** 单条 spec → {host, hostPort, container}。
 *  逐段扫描、括号感知：host/端口/容器任一段都可为 `${VAR:-默认}`（默认值可含冒号）；
 *  container 允许两段式 "80:80" 与协议前缀 "tcp/..."。 */
function splitSpec(spec) {
  spec = spec.replace(/^\w+\//, '');
  // 整条 spec 就是一个带默认值的变量（`${GATEWAY_PUBLISH:-80:80}`，默认值本身是 host:port:port 形态）：
  // 先把默认值展开再逐段扫，否则只会切出 1 段 ⇒ 误报「无法解析为 host:container 形态」。
  // 只展开「整条都是变量」这一种：`${BIND:-0.0.0.0}:7004:80` 仍走原路径（host 段交给 isLoopbackHost）。
  // 展开后判定逻辑一字不变 ⇒ 非回环默认值照样要撞 ALLOWLIST + EXPOSE-ALLOW，不会因能解析就放行。
  const whole = spec.match(/^\$\{[^:}]+:-(.+)\}$/);
  if (whole && whole[1].includes(':')) spec = whole[1];
  const segs = [];
  let i = 0;
  while (i < spec.length && segs.length < 4) {
    if (spec[i] === '$') {
      const end = spec.indexOf('}', i);
      if (end < 0) return null;
      segs.push(spec.slice(i, end + 1));
      i = end + 2; // 跳过 '}:'
    } else {
      const j = spec.indexOf(':', i);
      if (j < 0) {
        segs.push(spec.slice(i));
        i = spec.length + 1;
      } else {
        segs.push(spec.slice(i, j));
        i = j + 1;
      }
    }
  }
  if (segs.length < 2) return null;
  const container = segs[segs.length - 1];
  const hostPort = segs[segs.length - 2];
  const host = segs[0];
  if (!/^\d+$/.test(container)) return null;
  return { host, hostPort, container };
}

function checkSources(sources) {
  const violations = [];
  let checked = 0;
  for (const { file, src } of sources) {
    const rel = file;
    for (const { line, spec, comment } of parsePortEntries(src)) {
      const split = splitSpec(spec);
      if (!split) {
        violations.push(`${INFRA}/${rel}:${line}  "${spec}"  （无法解析为 host:container 形态）`);
        continue;
      }
      const { host, hostPort } = split;
      if (isLoopbackHost(host)) {
        checked++;
        continue;
      }
      const key = `${rel}:${hostPort}`;
      if (ALLOWLIST.has(key)) {
        // A6 防无声滥加：豁免必须双处留痕——compose 行内注释 EXPOSE-ALLOW: 理由
        if (!/EXPOSE-ALLOW\s*:/.test(comment || '')) {
          violations.push(
            `${INFRA}/${rel}:${line}  "${spec}"  （在豁免清单 ${key}，但缺 compose 侧 "# EXPOSE-ALLOW: 理由" 行尾标记——防无声滥加）`
          );
          continue;
        }
        checked++;
        continue;
      }
      violations.push(`${INFRA}/${rel}:${line}  "${spec}"  （宿主侧未绑回环；豁免键 "${key}"）`);
    }
  }
  return { violations, checked };
}

function runReal() {
  const files = readdirSync(join(ROOT, INFRA))
    .filter((f) => f.startsWith('docker-compose') && f.endsWith('.yml'))
    .sort();
  const sources = files.map((file) => ({
    file,
    src: readFileSync(join(ROOT, INFRA, file), 'utf8')
  }));
  const { violations, checked } = checkSources(sources);
  if (violations.length > 0) {
    console.error('[check-compose-ports] 以下端口映射未绑回环且不在豁免清单：');
    for (const v of violations) console.error(`  - ${v}`);
    console.error(
      '  修复：改为 "127.0.0.1:端口:端口"（或 ${VAR:-127.0.0.1}／${VAR:-127.0.0.1:端口:端口} 形态）；' +
        '确需对外暴露则在本脚本 ALLOWLIST 带理由登记，并在 compose 行尾加 "# EXPOSE-ALLOW: 理由"。'
    );
    process.exit(1);
  }
  console.log(
    `[check-compose-ports] OK：${checked} 条端口映射全部绑回环或在豁免清单（四形态解析，${files.length} 个 compose 文件）`
  );
}

function runSelfTest() {
  // 审计 P0-6 负向自测：四形态各 1 例非回环违规 + 1 例合规，违规必须全部被抓
  const cases = [
    {
      name: '①块形态+注释截断（原盲区）',
      src: 'services:\n  a:\n    ports:\n      # 注释夹在中间\n      - "0.0.0.0:7001:80"\n'
    },
    {
      name: '②!override 标签块（原盲区）',
      src: 'services:\n  a:\n    ports: !override\n      - "0.0.0.0:7002:80"\n'
    },
    { name: '③内联数组（原盲区）', src: 'services:\n  a:\n    ports: ["0.0.0.0:7003:80"]\n' },
    {
      name: '④变量默认值非回环',
      src: 'services:\n  a:\n    ports:\n      - "${BIND:-0.0.0.0}:7004:80"\n'
    },
    {
      name: '合规对照：回环+变量回环默认+整条变量默认含冒号',
      src:
        'services:\n  a:\n    ports:\n      # 注释\n      - "127.0.0.1:7005:80"\n      - "${BIND:-127.0.0.1}:7006:80"\n' +
        '      - "${PUBLISH:-127.0.0.1:7009:80}"\n'
    },
    {
      name: '⑦整条变量默认值含冒号且非回环',
      src: 'services:\n  a:\n    ports:\n      - "${PUBLISH:-0.0.0.0:7010:80}"\n'
    }
  ];
  let failed = 0;
  const sources = cases.map((c, idx) => ({ file: `case-${idx}.yml`, src: c.src }));
  const { violations } = checkSources(sources);
  const hitFile = (idx) => violations.some((v) => v.includes(`case-${idx}.yml`));
  // 违规用例（前 4 例 + 第 7 形态）必须各报 1 条；合规对照必须零报
  [0, 1, 2, 3, 5].forEach((idx) => {
    if (!hitFile(idx)) {
      console.error(`✗ 自测失败（违规未被识别）：${cases[idx].name}`);
      failed++;
    }
  });
  if (hitFile(4)) {
    console.error('✗ 自测失败：合规对照被误报');
    failed++;
  }
  // A6 防滥加负向自测：ALLOWLIST 命中但无 compose 侧 EXPOSE-ALLOW 标记 → 必须红
  ALLOWLIST.set('case-allow.yml:7007', '自测豁免');
  const allowCases = [
    { name: '⑤豁免无标记（必须红）', src: 'services:\n  a:\n    ports: ["0.0.0.0:7007:80"]\n' },
    {
      name: '⑥豁免带标记（必须绿）',
      src: 'services:\n  a:\n    ports: ["0.0.0.0:7008:80"] # EXPOSE-ALLOW: 自测理由\n'
    }
  ];
  ALLOWLIST.set('case-allow.yml:7008', '自测豁免');
  const allowSources = allowCases.map((c) => ({ file: `case-allow.yml`, src: c.src }));
  const allowResult = checkSources(allowSources);
  const noMarkHit = allowResult.violations.some((v) => v.includes(':7007'));
  const markedHit = allowResult.violations.some((v) => v.includes(':7008'));
  if (!noMarkHit) {
    console.error('✗ 自测失败：豁免缺 EXPOSE-ALLOW 标记未被识别');
    failed++;
  }
  if (markedHit) {
    console.error('✗ 自测失败：带 EXPOSE-ALLOW 标记的豁免被误报');
    failed++;
  }
  ALLOWLIST.delete('case-allow.yml:7007');
  ALLOWLIST.delete('case-allow.yml:7008');
  if (failed) {
    console.error(`check-compose-ports --self-test: ${failed} 项失败`);
    process.exit(1);
  }
  console.log(
    'check-compose-ports --self-test: 四形态 + 整条变量默认值（含冒号）共 5 例违规全部识别，合规对照无误报'
  );
}

if (process.argv.includes('--self-test')) {
  runSelfTest();
} else {
  runReal();
}
