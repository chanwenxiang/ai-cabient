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
 *   ④ 解析不可达（provider/深委托）⇒ 基线 attributed=false 显式在册，修复后棘轮收紧；
 *   ⑤ **总量双向对平**：totalEndpoints / totalAttributed 任一变化都要重种基线——
 *      归因**上升**同样要人工复核（解析链放宽、失败路径审计误算都会造假归因）。
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
// 🔴 失败路径审计（操作被拒的留痕，action=ACCESS_DENIED，只在鉴权失败时触发）不是业务归因。
//    负向注入实证：删掉 OpsOta 真实审计后，解析器穿 requirePermission ⇒ AccessDeniedAudit
//    .persistDenied 假绿（publishOta 的成功路径永远走不到那个 appendLog）。
//    🔴 2026-11-14 第五轮：不再用人工类名白名单。旧式 `new Set(['AccessDeniedAudit'])`
//    只要把类名改成 TmpDeniedAudit（业务行为零变化），18 处假归因全数复活且门禁仍绿
//    （240→258 仍 OK）。改为从源码自动派生 + 逐调用点按 action 判定，见下方
//    deriveFailurePathClasses / hasBusinessAuditCall。
const KEYWORDS = new Set([
  'if',
  'for',
  'while',
  'switch',
  'catch',
  'return',
  'synchronized',
  'do',
  'else',
  'try',
  'new',
  'super',
  'this',
  'throw',
  'assert'
]);

const maskJava = (s) =>
  s.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' ')).replace(/\/\/[^\n]*/g, '');

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
const allJava = [
  'trade-service',
  'device-service',
  'common/common-core',
  'jiangyi-gateway'
].flatMap((s) => walk(join(ROOT, 'services', s, 'src', 'main', 'java'), '.java'));
const byClass = new Map();
const rawOf = new Map();
for (const f of allJava) {
  const raw = readFileSync(f, 'utf8');
  const cls = (raw.match(/public\s+(?:final\s+|abstract\s+)?(?:class|record)\s+(\w+)/) || [])[1];
  if (cls && !byClass.has(cls)) byClass.set(cls, f);
  rawOf.set(f, raw);
}

// ── 失败路径审计类：从源码自动派生，不用人工名单（铁律 84②） ──
// 规则 A：类体内有 appendLog( 调用，且同文件出现「操作被拒」action 字面量；
// 规则 B：类名本身带 denied / forbidden / unauthorized 语义。
// 换类名、新增同类失败审计类都会自动纳入——无需回来维护名单。
// 边界（显式在册）：若新类既不叫 *Denied*、也不写被拒 action 常量，静态判据无法
// 区分它与正常业务审计；此时靠 ⑤ 总量对平棘轮强制人工复核，不静默放行。
const FAILURE_ACTION_LITERAL_RE =
  /["'][A-Z0-9_]*(?:ACCESS_DENIED|PERMISSION_DENIED|FORBIDDEN|UNAUTHORIZED)[A-Z0-9_]*["']/;
const FAILURE_CLASS_NAME_RE = /(?:denied|forbidden|unauthorized)/i;

/**
 * 纯函数（夹具可注入假源码验证换类名场景）：派生失败路径审计类集合。
 * @param {Map<string,string>} byClassLike 类名 → 文件路径
 * @param {Map<string,string>} rawOfLike   文件路径 → 源码
 */
export function deriveFailurePathClasses(byClassLike, rawOfLike) {
  const out = new Set();
  for (const [cls, file] of byClassLike) {
    if (FAILURE_CLASS_NAME_RE.test(cls)) {
      out.add(cls);
      continue;
    }
    const src = maskJava(rawOfLike.get(file) ?? '');
    if (/\bappendLog\s*\(/.test(src) && FAILURE_ACTION_LITERAL_RE.test(src)) out.add(cls);
  }
  return out;
}

const FAILURE_PATH_AUDIT_CLASSES = deriveFailurePathClasses(byClass, rawOf);

const bodyCache = new Map();
const fieldsCache = new Map();

/** 花括号计数取方法体（输入是已 mask 的源码的行数组与签名行号） */
function extractMethod(lines, sigIdx) {
  let depth = 0,
    started = false,
    end = sigIdx;
  for (let j = sigIdx; j < lines.length; j++) {
    for (const ch of lines[j]) {
      if (ch === '{') {
        depth++;
        started = true;
      }
      if (ch === '}') depth--;
    }
    if (started && depth <= 0) {
      end = j;
      break;
    }
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

/** 括号计数取 `(` 起的参数文本（跳过字符串字面量；body 已 mask 注释）。 */
function argTextOf(src, openIdx) {
  let depth = 0;
  let inStr = false;
  for (let i = openIdx; i < src.length; i++) {
    const ch = src[i];
    if (inStr) {
      if (ch === '\\') {
        i++;
        continue;
      }
      if (ch === '"') inStr = false;
      continue;
    }
    if (ch === '"') {
      inStr = true;
      continue;
    }
    if (ch === '(') depth++;
    if (ch === ')') {
      depth--;
      if (depth === 0) return src.slice(openIdx + 1, i);
    }
  }
  return src.slice(openIdx + 1);
}

/**
 * 逐调用点判定：body 内是否存在「非失败路径」的 `.appendLog(` 调用。
 * 🔴 不能只看方法体有没有 appendLog——同一方法里可能只在 if (denied) 分支留痕；
 *    也不能只靠类名黑名单——换类名即可绕过（240→258 假绿实证）。
 *    按 action 字面量逐调用点排除，失败路径类的整个方法体直接不算。
 * @param {string} body 已 mask 的方法体
 * @param {string} clsName 所属类（用于自动派生的失败路径类判定）
 */
export function hasBusinessAuditCall(body, clsName = '') {
  if (FAILURE_PATH_AUDIT_CLASSES.has(clsName)) return false;
  for (const m of body.matchAll(/\.\s*appendLog\s*\(/g)) {
    if (!FAILURE_ACTION_LITERAL_RE.test(argTextOf(body, m.index + m[0].length - 1))) return true;
  }
  return false;
}

/** 递归归因：cls.method 起深度 ≤ MAX_DEPTH 内是否可达业务 `.appendLog(` 调用点或
 *  AdminAuditService.appendLog 方法定义本身（走到定义=委托链终点是审计写入）。
 *  🔴 失败路径审计类（自动派生）不进入其方法体——从它出发的任何可达都是
 *     鉴权失败路径，不是业务归因（负向注入实证：不挡在入口则 publishOta ⇒
 *     requirePermission ⇒ denied ⇒ persistDenied ⇒ appendLog 定义假绿）。 */
export function reachable(clsName, methodName, depth, visited) {
  const key = clsName + '.' + methodName;
  if (visited.has(key) || depth > MAX_DEPTH) return false;
  visited.add(key);
  if (FAILURE_PATH_AUDIT_CLASSES.has(clsName)) return false;
  if (
    clsName === 'AdminAuditService' &&
    (methodName === 'appendLog' || methodName === 'appendLogIndependent')
  )
    return true;
  const body = bodyOf(clsName, methodName);
  if (!body) return false;
  if (hasBusinessAuditCall(body, clsName)) return true;
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
      if (/public\s+[\w<>,\s]+?\s+\w+\s*\(/.test(lines[j])) {
        sig = j;
        break;
      }
    }
    if (sig < 0) continue;
    const name = lines[sig].match(/(\w+)\s*\(/)?.[1] ?? '?';
    const { body } = extractMethod(lines, sig);
    let attributed = hasBusinessAuditCall(body, cls);
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
 * @param {{totalEndpoints?:number,totalAttributed?:number}|null} totals 基线总量（可选，夹具可不传）
 */
export function evaluate(snapshot, baseline, totals = null) {
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
  // ⑤ 总量双向对平：逐控制器判据只防「归因下降」「新端点未归因」，对
  //    「解析链放宽 / 失败路径审计误算」造成的**归因虚增**零防护（负向实证：
  //    仅改失败审计类的类名，归因 240→258 仍全绿，18 处假归因复活）。
  //    总量字段因此不是装饰：任何变化都要求重种快照，上升也必须人工复核。
  if (totals) {
    const ep = snapshot.reduce((n, s) => n + s.endpoints, 0);
    const attr = snapshot.reduce((n, s) => n + s.attributed, 0);
    if (typeof totals.totalEndpoints === 'number' && ep !== totals.totalEndpoints) {
      problems.push(`端点总量 ${totals.totalEndpoints} → ${ep}：快照已变，必须重种基线`);
    }
    if (typeof totals.totalAttributed === 'number' && attr !== totals.totalAttributed) {
      problems.push(
        `归因总量 ${totals.totalAttributed} → ${attr}：必须重种基线（上升也要复核——` +
          `解析链放宽或失败路径审计被误算会造成假归因）`
      );
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
    const { cls, endpoints } = controllerEndpoints(src);
    if (endpoints.length === 0) continue;
    snapshot.push({
      file: rel,
      cls: cls ?? '',
      endpoints: endpoints.length,
      attributed: endpoints.filter((e) => e.attributed).length
    });
  }

  const problems = evaluate(snapshot, baseline, baselineJson);
  // 未归因总量棘轮（只许减）：存量 215 在册，新端点未归因/归因退化都会顶破
  const maxUnattr =
    typeof baselineJson.maxUnattributed === 'number' ? baselineJson.maxUnattributed : Infinity;
  const totalEp = snapshot.reduce((n, s) => n + s.endpoints, 0);
  const totalAttr = snapshot.reduce((n, s) => n + s.attributed, 0);
  const unattr = totalEp - totalAttr;
  if (unattr > maxUnattr) {
    problems.push(
      `未归因总量棘轮击穿：${unattr} > maxUnattributed ${maxUnattr}——` +
        `新写端点必须带审计留痕；存量销账路径=补审计后重种快照或显式降基线并说明`
    );
  }
  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S9 端点级归因棘轮）`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${snapshot.length} 个写控制器 ${totalEp} 个写端点，归因 ${totalAttr} ` +
      `（未归因 ${unattr}/${maxUnattr === Infinity ? '∞' : maxUnattr}，快照与基线对平，未解析端点显式在册）`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  await main();
}
