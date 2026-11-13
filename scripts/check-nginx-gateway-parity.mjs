#!/usr/bin/env node
/**
 * 门禁：infra/gateway/nginx*.conf 三份网关配置在「共同职责」上必须同源。
 *
 * 成因（P2-r19-27，2026-11-12）：这三份配置是同一个网关的三副面孔——nginx.conf 走
 * host.docker.internal（开发直连宿主）、nginx.compose.conf 走容器 DNS、nginx-full.conf
 * 是生产实际加载的那份。它们各自演化过，结果同一类缺陷反复只修一份：
 *   - R19-4 / AU-190：WS 握手 token 在 query string 里（TTL 7 天），只有
 *     nginx.compose.conf 加了 `access_log ... ws_noargs` ⇒ 另外两份长期把长效设备
 *     凭证明文落进 access.log。nginx-full 上一轮补了，nginx.conf 一直漏到本次。
 *   - R15-M6：server 级 add_header 会被 location 内的 add_header 整组吞掉，三份都
 *     必须在带 Cache-Control 的 location 里逐处补齐。
 * 文件头注释（nginx.conf 第 7 行）本就写着「三份配置需同步补 zone + location」，
 * 但没有任何机器约束 —— 人写的注释拦不住下一次只改一份。
 *
 * 判据：
 *   ① 每份都有 `server_tokens off`（版本号不外泄，零成本）
 *   ② 每份都有 `gzip on`（传输压缩；缺了只是性能退化，但漏配往往意味着整段被删）
 *   ③ 每份在**第一个 location 之前**（即 server 级）都有三条安全头：
 *      X-Content-Type-Options / X-Frame-Options / Referrer-Policy
 *   ④ `client_max_body_size` 三份取值一致（不一致 = 某份上传会 413，且极难定位）
 *   ⑤ `log_format ws_noargs` 定义体三份**逐字一致**（只比名字会放过格式漂移：
 *      比如有人把 $uri 改回 $request，token 就回来了）
 *   ⑥ 凡含 `location /websocket/` 的文件，该 location 块内必须有 `access_log ... ws_noargs`
 *   ⑦ `limit_req_zone` 的行集合三份一致（P2-23 的死 zone 就是因为只改一份）
 *
 * 用法：
 *   node scripts/check-nginx-gateway-parity.mjs             # 检查真实文件
 *   node scripts/check-nginx-gateway-parity.mjs --self-test # 负向自测
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const GATEWAY_DIR = join(ROOT, 'infra', 'gateway');
const FILES = ['nginx.conf', 'nginx.compose.conf', 'nginx-full.conf'];

const SECURITY_HEADERS = ['X-Content-Type-Options', 'X-Frame-Options', 'Referrer-Policy'];

/** 去掉注释行后按行返回（保留行号，便于报错指名） */
function stripComments(src) {
  return src.split(/\r?\n/).map((line, i) => ({
    line: i + 1,
    // 行内注释也剥掉：安全头/指令判据只看有效内容，避免注释里的示例写法假绿
    text: line.replace(/(^|\s)#.*$/, '')
  }));
}

/** 取 `location /xxx/ { ... }` 块的正文字符串（按大括号配平），返回 null 表示没有该 location */
function locationBlock(lines, key) {
  const startIdx = lines.findIndex((l) =>
    new RegExp(`^\\s*location\\s+${key}\\s*\\{`).test(l.text)
  );
  if (startIdx < 0) return null;
  let depth = 0;
  const body = [];
  for (let i = startIdx; i < lines.length; i += 1) {
    const t = lines[i].text;
    for (const ch of t) {
      if (ch === '{') depth += 1;
      else if (ch === '}') depth -= 1;
    }
    body.push(t);
    if (depth === 0 && i > startIdx) break;
  }
  return body.join('\n');
}

/** 第一条 location 之前的行 = server 级配置 */
function serverLevel(lines) {
  const idx = lines.findIndex((l) => /^\s*location\s/.test(l.text));
  return (idx < 0 ? lines : lines.slice(0, idx)).map((l) => l.text).join('\n');
}

function normalizedLogFormat(src) {
  const m = src.match(/log_format\s+ws_noargs\s+([\s\S]*?);/);
  if (!m) return null;
  // 空白归一：定义体允许换行缩进不同，但每个字符（含 $uri vs $request）必须一致
  return m[1].replace(/\s+/g, ' ').trim();
}

function limitZoneSet(lines) {
  return lines
    .map((l) => l.text.match(/limit_req_zone\s+(\S+)\s+zone=([A-Za-z0-9_]+):(\S+)\s+rate=(\S+);/))
    .filter(Boolean)
    .map((m) => `${m[2]}:${m[3]}:${m[4]}`)
    .sort();
}

function bodySizeOf(serverScope) {
  const m = serverScope.match(/client_max_body_size\s+(\S+);/);
  return m ? m[1] : null;
}

/**
 * 校验一组文件内容（{name, src}）→ { problems: string[], ok: boolean }
 * 抽成纯函数是为了让 --self-test 能直接喂构造文本。
 */
export function validate(sources) {
  const problems = [];
  const parsed = sources.map(({ name, src }) => {
    const lines = stripComments(src);
    return { name, src, lines };
  });

  const sizes = [];
  const zoneSets = [];
  const logFormats = [];

  for (const { name, src, lines } of parsed) {
    if (!/^\s*server_tokens\s+off\s*;/m.test(src)) {
      problems.push(`${name}: 缺 \`server_tokens off\` —— Server 头会暴露 nginx 版本号`);
    }
    if (!/^\s*gzip\s+on\s*;/m.test(src)) {
      problems.push(
        `${name}: 缺 \`gzip on\` —— 文本响应不压缩（若是有意关闭请在此门禁里写明理由）`
      );
    }

    const serverScope = serverLevel(lines);
    for (const h of SECURITY_HEADERS) {
      // 只认 server 级：location 内出现不算（location 的 add_header 会整组覆盖继承）
      if (!new RegExp(`add_header\\s+${h}\\s+`).test(serverScope)) {
        problems.push(`${name}: server 级缺安全头 ${h} —— location 内补的不能顶替（继承会被吞）`);
      }
    }

    const size = bodySizeOf(serverScope);
    if (!size) problems.push(`${name}: server 级缺 client_max_body_size`);
    else sizes.push({ name, size });

    const lf = normalizedLogFormat(src);
    if (lf) logFormats.push({ name, lf });

    // 有 /websocket/ 就必须有无 args 日志，否则 token 明文落盘
    const wsBody = locationBlock(lines, '/websocket/');
    if (wsBody !== null) {
      if (!/access_log\s+\S+\s+ws_noargs\s*;/.test(wsBody)) {
        problems.push(
          `${name}: 有 location /websocket/ 但未覆盖 access_log ... ws_noargs —— ` +
            'WS 握手 token 在 query string 里，combined 格式会把它整条落进 access.log'
        );
      }
      if (!lf) {
        problems.push(`${name}: 有 location /websocket/ 但未定义 log_format ws_noargs`);
      }
    }

    const zones = limitZoneSet(lines);
    if (zones.length) zoneSets.push({ name, zones });
  }

  // 跨文件一致性
  const uniqSizes = [...new Set(sizes.map((s) => s.size))];
  if (uniqSizes.length > 1) {
    problems.push(
      `client_max_body_size 三份取值不一致：${sizes.map((s) => `${s.name}=${s.size}`).join('，')}` +
        ' —— 上传在某个入口会莫名 413'
    );
  }
  const uniqLog = [...new Set(logFormats.map((l) => l.lf))];
  if (uniqLog.length > 1) {
    problems.push(
      'log_format ws_noargs 定义体不一致（名字相同但格式漂移）：' +
        logFormats.map((l) => `${l.name}=«${l.lf}»`).join('，') +
        ' —— 若有人把 $uri 改回 $request，token 就重新落盘了'
    );
  }
  const uniqZones = [...new Set(zoneSets.map((z) => z.zones.join('|')))];
  if (uniqZones.length > 1) {
    problems.push(
      'limit_req_zone 集合不一致：' +
        zoneSets.map((z) => `${z.name}=[${z.zones.join(',')}]`).join('，') +
        ' —— 只改一份会造出定义无引用的死 zone，或引用未定义的 zone（nginx 起不来）'
    );
  }

  return { problems, ok: problems.length === 0 };
}

// ── 自测 ────────────────────────────────────────────────────────────────
const BASE = `limit_req_zone $binary_remote_addr zone=api_ratelimit:10m rate=30r/s;
limit_req_zone $binary_remote_addr zone=auth_ratelimit:10m rate=5r/s;
log_format ws_noargs '$remote_addr [$time_local] '
                     '"$request_method $uri" $status';

server {
    listen 80;
    client_max_body_size 20m;
    server_tokens off;
    gzip on;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;

    location /websocket/ {
        access_log /var/log/nginx/access.log ws_noargs;
        proxy_pass http://x$uri$is_args$args;
    }
}
`;

function selfTest() {
  const cases = [
    {
      desc: '三份完全一致 → 绿',
      srcs: [BASE, BASE, BASE],
      expect: true
    },
    {
      desc: '某份缺 server_tokens off → 红',
      srcs: [BASE, BASE.replace('    server_tokens off;\n', ''), BASE],
      expect: false
    },
    {
      desc: '某份缺 gzip on → 红',
      srcs: [BASE, BASE.replace('    gzip on;\n', ''), BASE],
      expect: false
    },
    {
      desc: '安全头只在 location 内、server 级缺失 → 红（继承会被吞）',
      srcs: [
        BASE.replace('    add_header X-Frame-Options "SAMEORIGIN" always;\n', '').replace(
          '    location /websocket/ {',
          '    location /websocket/ {\n        add_header X-Frame-Options "SAMEORIGIN" always;'
        ),
        BASE,
        BASE
      ],
      expect: false
    },
    {
      desc: '有 /websocket/ 但没覆盖 access_log → 红（token 落盘）',
      srcs: [
        BASE.replace('        access_log /var/log/nginx/access.log ws_noargs;\n', ''),
        BASE,
        BASE
      ],
      expect: false
    },
    {
      desc: 'ws_noargs 格式漂移（$uri 改回 $request）→ 红',
      srcs: [
        BASE.replace('"$request_method $uri" $status', '"$request_method $request" $status'),
        BASE,
        BASE
      ],
      expect: false
    },
    {
      desc: 'client_max_body_size 不一致 → 红',
      srcs: [BASE, BASE.replace('client_max_body_size 20m', 'client_max_body_size 5m'), BASE],
      expect: false
    },
    {
      desc: '某份缺一个 ratelimit zone → 红',
      srcs: [
        BASE.replace('limit_req_zone $binary_remote_addr zone=auth_ratelimit:10m rate=5r/s;\n', ''),
        BASE,
        BASE
      ],
      expect: false
    },
    {
      desc: '文件没有 /websocket/（开发直连版）→ 不要求 ws_noargs 覆盖，仍绿',
      srcs: [BASE.replace(/    location \/websocket\/ \{[\s\S]*?\n    \}\n/, ''), BASE, BASE],
      expect: true
    }
  ];

  let failed = 0;
  for (const [i, c] of cases.entries()) {
    const names = FILES.slice(0, c.srcs.length);
    const r = validate(c.srcs.map((src, k) => ({ name: names[k], src })));
    const pass = r.ok === c.expect;
    console.log(`${pass ? 'ok  ' : 'FAIL'} 用例 ${i + 1}：${c.desc}`);
    if (!pass) {
      failed += 1;
      console.log(`      期望 ok=${c.expect}，实得 ok=${r.ok}`);
      r.problems.forEach((p) => console.log(`      · ${p}`));
    }
  }
  if (failed) {
    console.error(`\n自测失败 ${failed}/${cases.length}`);
    process.exit(1);
  }
  console.log(`\n自测通过 ${cases.length}/${cases.length}`);
}

// ── 主流程 ──────────────────────────────────────────────────────────────
const invokedDirectly =
  process.argv[1] &&
  import.meta.url === new URL(`file://${process.argv[1].replace(/\\/g, '/')}`).href;

if (invokedDirectly) {
  if (process.argv.includes('--self-test')) {
    selfTest();
  } else {
    const sources = FILES.map((name) => ({
      name,
      src: readFileSync(join(GATEWAY_DIR, name), 'utf8')
    }));
    const { problems, ok } = validate(sources);
    const zones = sources
      .map((s) => `${s.name}=${limitZoneSet(stripComments(s.src)).length}`)
      .join(' ');
    console.log(`检查 infra/gateway/{${FILES.join(',')}}（zone 数 ${zones}）`);
    if (ok) {
      console.log(
        '网关配置同源检查通过：server_tokens / gzip / 安全头 / body_size / ws_noargs / zone 全部一致'
      );
    } else {
      console.error(`\n发现 ${problems.length} 处不一致：`);
      problems.forEach((p) => console.error(`  · ${p}`));
      process.exit(1);
    }
  }
}
