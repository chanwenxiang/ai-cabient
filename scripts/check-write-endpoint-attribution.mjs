#!/usr/bin/env node
/**
 * 门禁（S9 第二层）：写端点**逐端点归因快照棘轮**——类级门禁
 * （check-write-action-attribution，控制器/1-hop 有 .appendLog( 即可达）之上的
 * 方法级加固。两道各自的盲区由对方补：
 *   - 类级：控制器 5 个写端点只 1 个有 appendLog ⇒ 类级恒可达，端点级盲区
 *   - 端点级：静态委托链解析有不可解析尾巴（provider 模式/深委托/lambda 传参）⇒
 *     未解析端点显式入基线，不硬判红
 *
 * 机制：对每个写控制器抽取全部写端点（@Post/Put/Patch/DeleteMapping 方法），逐端点
 * 递归解析归因（自身 .appendLog( / receiver 调用链 / 同类私有调用链，深度 ≤ 4，visited 防环），
 * 得 per-控制器 {endpoints, attributed} 快照，与
 * [write-endpoint-attribution-baseline.json](scripts/write-endpoint-attribution-baseline.json)
 * 对账（双向棘轮）：
 *   ① **归因退化**：attributed < 基线 ⇒ 红（有端点的审计留痕被拆）；
 *   ② **新端点未归因**：endpoints > 基线 且 attributed 增量 < 端点增量 ⇒ 红（新写端点必须带审计或显式降基线）；
 *   ③ **控制器消失** ⇒ 基线条目陈旧红；
 *   ④ 解析不可达（provider/深委托）⇒ 基线 attributed=false 显式在册，修复后棘轮收紧。
 *
 *   node scripts/check-write-endpoint-attribution.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-write-endpoint-attribution]';
const BASELINE_PATH = join('scripts', 'write-endpoint-attribution-baseline.json');
const WRITE_RE = /@(?:Post|Put|Patch|Delete)Mapping/g;
const MAX_DEPTH = 4;
// 🔴 拒绝路径审计黑名单：这些类的 appendLog 是「操作被拒」的留痕（action=ACCESS_DENIED），
//    只在鉴权失败等异常路径触发，不是业务动作归因——递归可达判定不得把它当端点归因证据。
//    负向注入实证：删掉 OpsOta 真实审计后，解析器穿 requirePermission ⇒ AccessDeniedAudit
//    .persistDenied 假绿（publishOta 的成功路径永远走不到那个 appendLog）。
const FAILURE_PATH_AUDIT_CLASSES = new Set(['AccessDeniedAudit']);
const KEYWORDS = new Set([
  'if', 'for', 'while', 'switch', 'catch', 'return', 'synchronized', 'do',
  'else', 'try', 'new', 'super', 'this', 'throw', 'assert'
]);

const maskJava = (s) =>
  s
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, '');

function walk(dir, ext, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, ext, out);
    else if (name.endsWith(ext)) out.push(p);
  }
  return out;
}

// ── 全量 Java 索引（类名→文件、文件→mask 后源码） ──
const allJava = ['trade-service', 'device-service', 'common', 'jiangyi-gateway'].flatMap((s) =>
  walk(join(ROOT, 'services', s, 'src', 'main', 'java'), '.java')
);
const byClass = new Map();
const rawOf = new Map();
for (const f of allJava) {
  const raw = readFileSync(f, 'utf8');
  const cls = (raw.match(/public\s+(?:final\s+|abstract\s+)?(?:class|record)\s+(\w+)/) || [])[1];
  if (cls && !byClass.has(cls)) byClass.set(cls, f);
  rawOf.set(f, raw);
}

const bodyCache = new Map();
const fieldsCache = new Map();

/** 花括号计数取方法体（输入是已 mask 的源码的行数组与签名行号） */
function extractMethod(lines, sigIdx) {
  let depth = 0, started = false, end = sigIdx;
  for (let j = sigIdx; j < lines.length; j++) {
    for (const ch of lines[j]) {
      if (ch === '{') { depth++; started = true; }
      if (ch === '}') depth--;
    }
    if (started && depth <= 0) { end = j; break; }
  }
  return { body: lines.slice(sigIdx, end + 1).join('\n'), endLine: end + 1 };
}

function findMethod(lines, methodName) {
  const re = new RegExp(
    `(?:public|protected|private)\\s+[\\w<>,\\[\\]\\s]+\\s+${methodName}\\s*\\(`
  );
  for (let i = 0; i < lines.length; i++) if (re.test(lines[i])) return i;
  return -1;
}

function srcLinesOf(clsName) {
  const f = byClass.get(clsName);
  if (!f) return null;
  return maskJava(rawOf.get(f)).split('\n');
}

function bodyOf(clsName, methodName) {
  const key = clsName + '.' + methodName;
  if (bodyCache.has(key)) return bodyCache.get(key);
  let body = null;
  const lines = srcLinesOf(clsName);
  if (lines) {
    const mi = findMethod(lines, methodName);
    if (mi >= 0) body = extractMethod(lines, mi).body;
  }
  bodyCache.set(key, body);
  return body;
}

function fieldsOf(clsName) {
  if (fieldsCache.has(clsName)) return fieldsCache.get(clsName);
  const map = new Map();
  const lines = srcLinesOf(clsName);
  if (lines) {
    const src = lines.join('\n');
    for (const m of src.matchAll(/private\s+(?:final\s+)?(\w+)\s+(\w+)\s*;/g)) {
      if (byClass.has(m[1])) map.set(m[2], m[1]);
    }
  }
  fieldsCache.set(clsName, map);
  return map;
}

/** 递归归因：cls.method 起深度 ≤ MAX_DEPTH 内是否可达 `.appendLog(` 调用点
 *  （拒绝路径审计类【AccessDeniedAudit】不计——它只在鉴权失败时触发） */
export function reachable(clsName, methodName, depth, visited) {
  const key = clsName + '.' + methodName;
  if (visited.has(key) || depth > MAX_DEPTH) return false;
  visited.add(key);
  const body = bodyOf(clsName, methodName);
  if (!body) return false;
  if (/\.appendLog\s*\(/.test(body) && !FAILURE_PATH_AUDIT_CLASSES.has(clsName)) return true;
  const fields = fieldsOf(clsName);
  for (const m of body.matchAll(/(\w+)\.(\w+)\s*\(/g)) {
    const cls2 = fields.get(m[1]);
    if (!cls2) continue;
    if (reachable(cls2, m[2], depth + 1, visited)) return true;
  }
  for (const m of body.matchAll(/(?<![.\w])(\w+)\s*\(/g)) {
    const mn = m[1];
    if (KEYWORDS.has(mn)) continue;
    if (reachable(clsName, mn, depth + 1, visited)) return true;
  }
  return false;
}

/** 控制器写端点枚举 + 逐端点归因。 */
export function controllerEndpoints(src) {
  const lines = maskJava(src).split('\n');
  const cls = (src.match(/public\s+(?:final\s+)?class\s+(\w+)/) || [])[1];
  const fields = cls ? fieldsOf(cls) : new Map();
  const endpoints = [];
  for (let i = 0; i < lines.length; i++) {
    if (!/@(?:Post|Put|Patch|Delete)Mapping/.test(lines[i])) continue;
    let sig = -1;
    for (let j = i + 1; j < Math.min(i + 8, lines.length); j++) {
      if (/public\s+[\w<>,\s]+?\s+\w+\s*\(/.test(lines[j])) { sig = j; break; }
    }
    if (sig < 0) continue;
    const name = lines[sig].match(/(\w+)\s*\(/)?.[1] ?? '?';
    const { body } = extractMethod(lines, sig);
    let attributed = /\.appendLog\s*\(/.test(body);
    if (!attributed && cls) {
      for (const m of body.matchAll(/(\w+)\.(\w+)\s*\(/g)) {
        const cls2 = fields.get(m[1]);
        if (!cls2) continue;
        if (reachable(cls2, m[2], 1, new Set([cls + '.' + name]))) {
          attributed = true;
          break;
        }
      }
    }
    if (!attributed && cls) {
      for (const m of body.matchAll(/(?<![.\w])(\w+)\s*\(/g)) {
        const mn = m[1];
        if (KEYWORDS.has(mn)) continue;
        if (reachable(cls, mn, 1, new Set([cls + '.' + name]))) {
          attributed = true;
          break;
        }
      }
    }
    endpoints.push({ name, attributed });
  }
  return { cls, endpoints };
}

/**
 * 纯判定：当前快照 × 基线（双向棘轮）。
 * @param {{file:string,cls:string,endpoints:number,attributed:number}[]} snapshot
 * @param {Record<string,{endpoints:number,attributed:number}>} baseline
 */
export function evaluate(snapshot, baseline) {
  const problems = [];
  const baseMap = new Map(Object.entries(baseline));
  for (const s of snapshot) {
    const b = baseMap.get(s.file);
    if (!b) {
      problems.push(
        `${s.file.split('/').pop()}：新写控制器 ${s.endpoints} 个端点未入基线——` +
          `归因 ${s.attributed}/${s.endpoints}，全部归因则登记快照，否则显式降基线并说明`
      );
      continue;
    }
    if (s.attributed < b.attributed) {
      problems.push(
        `${s.file.split('/').pop()}：端点归因退化 ${s.attributed}/${s.endpoints} < 基线 ${b.attributed}/${b.endpoints}——` +
          `有写端点的审计留痕被拆（S9 盲区层面的退化，类级门禁看不见）`
      );
    }
    if (s.endpoints > b.endpoints) {
      const added = s.endpoints - b.endpoints;
      const attrAdded = s.attributed - b.attributed;
      if (attrAdded < added) {
        problems.push(
          `${s.file.split('/').pop()}：新增 ${added} 个写端点但归因只增 ${attrAdded}——` +
            `新写端点必须带审计留痕（或明确降基线登记为未归因并说明）`
        );
      }
    }
  }
  for (const [file] of baseMap) {
    if (!snapshot.some((s) => s.file === file)) {
      problems.push(`基线陈旧：${file.split('/').pop()} 已无写端点，条目必须删除（棘轮只许收缩）`);
    }
  }
  return problems;
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function main() {
  const baselinePath = join(ROOT, BASELINE_PATH);
  if (!existsSync(baselinePath)) fail(`基线缺失 ${BASELINE_PATH}`);
  const baselineJson = JSON.parse(readFileSync(baselinePath, 'utf8'));
  const baseline = baselineJson.controllers || {};
  if (Object.keys(baseline).length === 0) fail('基线 controllers 空（种子丢失或格式错）');

  const controllers = allJava.filter((f) => {
    const src = rawOf.get(f);
    WRITE_RE.lastIndex = 0;
    return WRITE_RE.test(src);
  });
  if (controllers.length === 0) fail('写控制器提取数=0（提取器坏了，零命中=假绿）');

  const snapshot = [];
  for (const f of controllers) {
    const rel = relative(ROOT, f).replace(/\\/g, '/');
    const src = rawOf.get(f);
    const { endpoints } = controllerEndpoints(src);
    if (endpoints.length === 0) continue;
    snapshot.push({
      file: rel,
      cls: endpoints.cls ?? '',
      endpoints: endpoints.length,
      attributed: endpoints.filter((e) => e.attributed).length,
    });
  }

  const problems = evaluate(snapshot, baseline);
  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S9 端点级归因棘轮）`);
    process.exit(1);
  }
  const totalEp = snapshot.reduce((n, s) => n + s.endpoints, 0);
  const totalAttr = snapshot.reduce((n, s) => n + s.attributed, 0);
  console.log(
    `${TAG} OK：${snapshot.length} 个写控制器 ${totalEp} 个写端点，归因 ${totalAttr} ` +
      `（快照与基线对平，未解析端点显式在册）`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  await main();
}
