#!/usr/bin/env node
/**
 * 门禁：改了「API 形状」就必须在**同一变更集**里重新生成并提交
 * `packages/shared-types/src/generated/openapi.ts`。
 *
 * 背景（lessons #12/#242）：新增端点/参数/DTO 字段后漏跑 `pnpm gen:api-types`，
 * 本地 `check-openapi-types` 只做结构校验（别名齐全）照样绿，直到 CI 抓回：
 *   generated OpenAPI types are stale
 * 一轮推送连吃两红（收货误验 SKU / OpenAPI 类型过期）就是预检盲区 X1。
 *
 * 判据只看**形状**，不看业务逻辑改动（避免「改一行审计文案也要求 regen」）：
 *   - Controller 文件（`*Controller.java` 或 `.../(api|web|controller)/**.java`）的
 *     diff 里出现增删的映射/参数注解：@XxxMapping、@RequestMapping、
 *     @RequestParam/@PathVariable/@RequestBody/@RequestHeader/@ModelAttribute；
 *   - DTO 文件（`*Request.java` / `*Response.java` / `*Dto.java`，或 `.../dto/**.java`）
 *     的 diff 里出现增删的字段声明（`private`/`protected` 开头）；
 *   - DTO 文件若是 **record**：出现增删的**record 分量**或 record 声明行本身。
 * 命中形状变更且变更集里没有 openapi.ts => 红，并列出触发文件。
 *
 * 🔴 为什么 record 必须单列一条（2026-10-24 实测假阴性）：本仓 DTO 大量用 Java
 * record，分量声明写成 `String clientRequestNo`——**既无修饰符也无结尾符**，
 * `FIELD_DECL` 完全看不见。`MerchantSelfReplenishRequest` 加幂等键 `clientRequestNo`
 * 那次，本门禁打印「变更集内无 API 形状改动」，而 CI 的 openapi job 起 jar 抽真
 * /v3/api-docs 判红；靠手动 live regen 才没吃一轮红。为不引入反向的假红，record
 * 判据受两层约束：① 文件自身确实是 record（普通类里 `Type name` 形状太常见）；
 * ② 命中行的标识符**确实是 record 头里的某个分量**（方法体里的局部声明不算）。
 *
 * 变更集 = base...HEAD（已提交）∪ base 工作树/暂存区（未提交）∪ 未跟踪文件。
 * base 默认 origin/dev，可用 OPENAPI_BASE_REF 覆盖；找不到 base 一律 fail-closed。
 *
 * 🔴 2026-10-29 补的第二条放行判据（**内容覆盖**，不是「关掉门禁」）：生成物可以**先于**代码
 * 进基线——peer 在 dev 服务上 live regen 时顺手把当天在飞的形状一起生成了，于是
 * `openapi.ts` 早已随基线提交、本次变更集里只剩 Java。此时「同变更集带 openapi.ts」这条
 * 记账判据必然红，而契约其实是同步的（本机实测：按 18080 现抓 spec 重生成，`git diff`
 * 零字节）。放行的前提要逐条挣得：
 *   ① 生成物在基线里**确实存在**（`git cat-file -e base:openapi.ts`）；
 *   ② 每处形状改动都能在生成物里**找到对应物**：新增的 record 分量/字段 ⇒ 该 record 的
 *      schema 块里出现同名属性；新增的映射路径 ⇒ 生成物里出现该 path 字面量；**删掉的**
 *      形状则要求生成物里**不再出现**（极性相反，防止「删了字段但类型没重生成」蒙混）；
 *   ③ 抽不出可核对标识符的命中（如无 path 字面量的 `@RequestParam`、普通类字段）⇒
 *      **不放行**，仍按原判据红（fail-closed）。
 * 真正的权威校验仍是 CI 那一步：起 HEAD 的 jar 抽 `/v3/api-docs` 再跑 `check:openapi-types`。
 *
 * 负向验证：`node scripts/check-openapi-regen-required.test.mjs`
 * （注解红 / ack 绿 / record 分量红 / 方法体绿 / fail-closed 五向，全程在 `.tmp`
 * 临时 git 仓库里跑，不碰真实树）。
 *
 *   node scripts/check-openapi-regen-required.mjs
 *
 * 受控豁免（MA-074）：`scripts/openapi-regen-exemptions.json` 只放行「给 controller 追加
 * HttpServletRequest 这类服务端形参」——它必然碰到形状行而 spec 又确实不含这类参数。
 * 三条硬约束（只对 controller 生效、登记的 endpoint 必须仍在源码中、映射签名与基线逐条相等）
 * 由 `check-openapi-regen-required.test.mjs` 的 7 条用例钉住，含「DTO 不得豁免」那条。
 */
import { gitAsync } from './lib/async-spawn.mjs';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-openapi-regen-required]';
const GENERATED = 'packages/shared-types/src/generated/openapi.ts';

const EXEMPTION_FILE = process.env.OPENAPI_EXEMPTIONS_FILE
  ? resolve(ROOT, process.env.OPENAPI_EXEMPTIONS_FILE)
  : join(ROOT, 'scripts', 'openapi-regen-exemptions.json');
const EXEMPTIONS = existsSync(EXEMPTION_FILE)
  ? JSON.parse(readFileSync(EXEMPTION_FILE, 'utf8'))
  : {};

/**
 * 映射签名：HTTP 方法注解（含其路径字面量）+ 方法返回类型。
 * 豁免必须「签名逐条相等」，所以删路由、改路径、改响应类型都会让豁免失效——
 * 这三类恰恰是真正会改 spec 的动作。参数列表里的服务端形参（HttpServletRequest 等）
 * 不进签名，正是要放行的那一类。
 */
function mappingSignature(src) {
  const out = [];
  for (const l of src.split(/\r?\n/)) {
    const t = l.trim();
    const m = /^@(Get|Post|Put|Delete|Patch)Mapping(?:\(([^)]*)\))?/.exec(t);
    if (m) out.push(`${m[1]}:${(m[2] || '').replace(/["\s]/g, '')}`);
    const r =
      /^\s*public\s+(ApiResponse<[^>]*>|ResponseEntity<[^>]*>|[A-Za-z][\w<>,. ]*)\s+\w+\s*\(/.exec(
        t
      );
    if (r) out.push(`ret:${r[1].replace(/\s+/g, '')}`);
  }
  return out.sort();
}

const isController = (p) =>
  /\.java$/.test(p) &&
  (/\/[^/]*Controller\.java$/.test(p) || /\/src\/main\/java\/.*\/(api|web|controller)\//.test(p));
const isDto = (p) =>
  /\.java$/.test(p) &&
  (/\/[^/]*(Request|Response|Dto)\.java$/.test(p) ||
    /\/src\/main\/java\/.*\/dto\//.test(p) ||
    /^services\/common\/.*\.java$/.test(p));

const SHAPE_ANNOTATION =
  /^\s*@(?:Get|Post|Put|Delete|Patch)Mapping\b|^\s*@RequestMapping\b|^\s*@(?:RequestParam|PathVariable|RequestBody|RequestHeader|ModelAttribute)\b/;
const FIELD_DECL = /^\s*(?:private|protected)\s+[\w<>[\],.?\s]+\s+\w+\s*(?:=|;)/;

const RECORD_DECL = /\brecord\s+[A-Za-z_$][\w$]*\s*(?:<[^<>]*>)?\s*\(/;
const RECORD_DECL_LINE =
  /^\s*(?:public\s+|final\s+|abstract\s+|strictfp\s+)*record\s+[A-Za-z_$][\w$]*\s*(?:<[^<>]*>)?\s*\(/;
const RECORD_COMPONENT =
  /^\s*(?:@\w+(?:\([^()]*\))?\s+)*(?:final\s+)?(?:[A-Z][\w.]*|(?:int|long|boolean|double|float|short|byte|char))(?:<[^<>()]*>)?(?:\[\])?(?:\s*\?\s*)?\s+[a-z_$][\w$]*\s*[,)]?\s*$/;
const TRAILERS = /[,;)\s]+$/;

/** 一行末尾的标识符（Java 合法 record 头里，每个分量最后那个标识符就是分量名）。 */
function tailIdentifier(line) {
  const t = line.trim().replace(TRAILERS, '');
  const m = /([A-Za-z_$][\w$]*)\s*(?:\[[^\]]*\])?\s*$/.exec(t);
  return m ? m[1] : null;
}

/**
 * 解析 record 头里的分量名集合；文件不是 record / 括号不配对 ⇒ 空集（不猜）。
 * `<`、`(`、`[` 都计深度，所以泛型/注解参数里的逗号不会把分量切错。
 */
export function recordComponentNames(content) {
  const names = new Set();
  if (!content) return names;
  const m = RECORD_DECL.exec(content);
  if (!m) return names;
  const open = m.index + m[0].length - 1;
  let depth = 0;
  let close = -1;
  for (let i = open; i < content.length; i++) {
    const c = content[i];
    if (c === '(') depth++;
    else if (c === ')') {
      depth--;
      if (depth === 0) {
        close = i;
        break;
      }
    }
  }
  if (close < 0) return names;
  const parts = [];
  let buf = '';
  let nest = 0;
  for (const c of content.slice(open + 1, close)) {
    if (c === '(' || c === '<' || c === '[') nest++;
    else if (c === ')' || c === '>' || c === ']') nest--;
    if (c === ',' && nest === 0) {
      parts.push(buf);
      buf = '';
    } else {
      buf += c;
    }
  }
  parts.push(buf);
  for (const part of parts) {
    const line = part
      .replace(/\/\/[^\n]*/g, '')
      .replace(/\s+/g, ' ')
      .trim();
    const name = tailIdentifier(line);
    if (name) names.add(name);
  }
  return names;
}

/** 取变更行里第一条「形状」命中；控制器看注解，DTO 看字段声明或 record 分量。 */
export function pickShapeHit(path, changedLines, content) {
  const hit = pickShapeHitSigned(
    path,
    changedLines.map((line) => ({ sign: '+', line })),
    content
  );
  return hit ? hit.line : undefined;
}

/** 同上，但保留极性与原文（内容覆盖判据要知道是「新增」还是「删除」）。 */
export function pickShapeHitSigned(path, signedLines, content) {
  if (isController(path)) return signedLines.find((l) => SHAPE_ANNOTATION.test(l.line));
  const components = recordComponentNames(content);
  return signedLines.find(({ line }) => {
    if (FIELD_DECL.test(line)) return true;
    if (RECORD_DECL_LINE.test(line)) return true;
    if (!RECORD_COMPONENT.test(line)) return false;
    const name = tailIdentifier(line);
    return !!name && components.has(name);
  });
}

/** record 声明里的类型名（生成物里的 schema 键）。 */
export function recordName(content) {
  const m = RECORD_NAME.exec(content || '');
  return m ? m[1] : null;
}

const RECORD_NAME = /\brecord\s+([A-Za-z_$][\w$]*)/;
const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** 生成物里 `Name: { … }` 的块内文（按花括号配平；取不到 ⇒ null，不猜）。 */
export function schemaBlock(generated, name) {
  if (!generated || !name) return null;
  const re = new RegExp(`(?:^|[\\s{])${escapeRe(name)}\\s*:\\s*\\{`, 'm');
  const m = re.exec(generated);
  if (!m) return null;
  const start = m.index + m[0].length;
  let depth = 1;
  let i = start;
  for (; i < generated.length; i++) {
    const c = generated[i];
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) break;
    }
  }
  return depth === 0 ? generated.slice(start, i) : null;
}

/**
 * 把一个 Java 文件里的「可核对形状」抽成集合（与 diff 无关，纯文件级）：
 *   - 控制器 ⇒ 映射注解里的 path 字面量（只认以 `/` 开头的）；
 *   - record ⇒ schema 名 + 各分量名（挂在 schema 名下核对）；
 *   - 普通类 ⇒ 字段名（无 schema 归属，内容判据不认，走 fail-closed）。
 */
export function shapesOf(path, content) {
  const shapes = [];
  if (!content) return shapes;
  if (isController(path)) {
    for (const m of content.matchAll(MAPPING_PATH)) {
      if (m[1].startsWith('/')) shapes.push({ kind: 'path', value: m[1] });
    }
    return shapes;
  }
  const owner = recordName(content);
  if (!owner) {
    for (const m of content.matchAll(FIELD_DECL_ALL)) {
      shapes.push({ kind: 'property', owner: null, value: m[1] });
    }
    return shapes;
  }
  shapes.push({ kind: 'schema', value: owner });
  for (const c of recordComponentNames(content)) {
    shapes.push({ kind: 'property', owner, value: c });
  }
  return shapes;
}

const MAPPING_PATH =
  /@(?:Get|Post|Put|Delete|Patch|Request)Mapping\s*\(\s*(?:value\s*=\s*)?\{?\s*"([^"]*)"/g;
const FIELD_DECL_ALL = /^\s*(?:private|protected)\s+[\w<>[\],.?\s]+\s+(\w+)\s*(?:=|;)/gm;
const shapeKey = (s) => `${s.kind}:${s.owner ?? ''}:${s.value}`;

/** 生成物是否已覆盖该形状：新增要求出现，删除要求消失（抽不出归属 ⇒ 一律不覆盖）。 */
export function shapeCovered({ sign, shape }, generated) {
  let present = false;
  if (shape.kind === 'path') {
    present = !!generated && generated.includes(shape.value);
  } else if (shape.kind === 'schema') {
    present = schemaBlock(generated, shape.value) !== null;
  } else if (shape.owner) {
    const block = schemaBlock(generated, shape.owner);
    present =
      block !== null && new RegExp(`(?:^|[^\\w$])${escapeRe(shape.value)}\\??\\s*:`).test(block);
  }
  return sign === '-' ? !present : present;
}

/** diff 文本里的增删内容行（去掉 +/- 前缀，排除文件头）。 */
export function changedContentLines(diff) {
  return changedContentLinesWithSign(diff).map((s) => s.line);
}

/** 同上，保留极性：`+` 新增、`-` 删除。 */
export function changedContentLinesWithSign(diff) {
  return diff
    .split(/\r?\n/)
    .filter((l) => /^[+-]/.test(l) && !/^(\+\+\+|---)/.test(l))
    .map((l) => ({ sign: l[0], line: l.slice(1) }));
}

const isCli =
  process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isCli) await main();

async function main() {
  function fail(msg) {
    console.error(`${TAG} FAIL: ${msg}`);
    process.exit(1);
  }

  const candidates = [process.env.OPENAPI_BASE_REF, 'origin/dev', 'origin/main', 'dev'].filter(
    Boolean
  );
  let base = null;
  for (const ref of candidates) {
    const r = await gitAsync(['rev-parse', '--verify', '--quiet', `${ref}^{commit}`], {
      cwd: ROOT
    });
    if (r.ok) {
      base = ref;
      break;
    }
  }
  if (!base) {
    fail(
      '找不到基线 ref（试过 ' +
        candidates.join(', ') +
        '）——无法确定变更集。不要当成「没有形状变更」；请先 fetch 或设置 OPENAPI_BASE_REF。'
    );
  }

  async function names(args) {
    const r = await gitAsync(args, { cwd: ROOT });
    if (!r.ok) fail(`git ${args.join(' ')} 失败（${r.spawnError || 'non-zero exit'}）：${r.out}`);
    return (r.out || '').split(/\r?\n/).filter(Boolean);
  }

  const set = new Set([
    ...(await names(['diff', '--name-only', `${base}...HEAD`])),
    ...(await names(['diff', '--name-only', base])),
    ...(await names(['ls-files', '--others', '--exclude-standard']))
  ]);

  async function diffText(path) {
    const tracked = await gitAsync(['ls-files', '--error-unmatch', '--', path], { cwd: ROOT });
    if (!tracked.ok) {
      // 未跟踪文件：整份内容都算新增
      try {
        return readFileSync(resolve(ROOT, path), 'utf8')
          .split(/\r?\n/)
          .map((l) => `+${l}`)
          .join('\n');
      } catch {
        return '';
      }
    }
    const r = await gitAsync(['diff', base, '--', path], { cwd: ROOT });
    if (!r.ok) fail(`git diff ${base} -- ${path} 失败：${r.out}`);
    return r.out;
  }

  /** record 判据要文件全文，不能只看 diff：工作树 → HEAD → base → 空（删掉的也要认得出）。 */
  async function javaSource(path) {
    const abs = join(ROOT, path);
    if (existsSync(abs)) {
      try {
        return readFileSync(abs, 'utf8');
      } catch {
        /* 落到 git 取数 */
      }
    }
    for (const ref of ['HEAD', base]) {
      const r = await gitAsync(['show', `${ref}:${path}`], { cwd: ROOT });
      if (r.ok) return r.out || '';
    }
    return '';
  }

  const suspects = [];
  for (const path of set) {
    if (!path.endsWith('.java')) continue;
    if (!isController(path) && !isDto(path)) continue;
    // 🔴 2026-10-30（G-18 首触发现）：豁免 jiangyi-gateway 设备协议模块——spec（18080→trade
    // /v3/api-docs）从不含网关路径（openapi.ts 0 处 /jiangyi/api、0 处网关控制器，实测），
    // 覆盖判据对该模块**结构上不可能满足**，留着就是必然误报。其契约面在
    // docs/JIANGYI_INTERFACE_CHECKLIST.md（对照 smali/真机报文），不在 openapi.ts；
    // CI 权威校验起的也是 trade jar。
    if (path.startsWith('services/jiangyi-gateway/')) continue;
    const diff = await diffText(path);
    if (!diff) continue;
    const src = await javaSource(path);
    const hit = pickShapeHitSigned(path, changedContentLinesWithSign(diff), src);
    if (hit)
      suspects.push({
        path,
        sign: hit.sign,
        line: hit.line.trim(),
        controller: isController(path)
      });
  }

  /**
   * 受控豁免（MA-074 引入）：只放「给 controller 方法追加服务端形参」这一类——
   * 典型是加 `HttpServletRequest request` 取操作者做审计留痕（审计族一半的修法都要它）。
   * 这类改动**必然**碰到 `@PathVariable/@RequestBody` 所在行（git 把被替换的旧行也算变更行），
   * 于是被形状启发式命中；而 spec 里根本没有这类参数，净差恒为空 ⇒ 门禁结构性不可满足。
   *
   * 三条硬约束，缺一条就不豁免（防 G-18 那类「本地说没改、CI 说改了」的反向假绿）：
   *   ① 只对 controller 生效，**DTO 永不豁免**（`@NotNull` 改 required 不产生新 property key，
   *      正是 MA-083 那次真该 regen 的情形）；
   *   ② 登记里的 endpoint 字面量必须仍出现在该文件当前源码里（条目漂移即失效）；
   *   ③ 基线与当前的**映射签名**（HTTP 方法注解 + 路径 + 返回类型）必须逐条相等——
   *      删路由、改路径、改响应类型都会让签名变化，从而拒绝豁免。
   */
  const exempted = [];
  const kept = [];
  for (const s of suspects) {
    const entry = s.controller ? EXEMPTIONS[s.path] : undefined;
    if (!entry) {
      kept.push(s);
      continue;
    }
    const current = await javaSource(s.path);
    if (!entry.endpoint || !current.includes(entry.endpoint)) {
      s.exemptFail = '登记里的 endpoint 已不在该文件源码中（条目漂移）';
      kept.push(s);
      continue;
    }
    const atBase = await gitAsync(['show', `${base}:${s.path}`], { cwd: ROOT });
    if (mappingSignature(atBase.out || '').join('|') !== mappingSignature(current).join('|')) {
      s.exemptFail = '映射签名（HTTP 方法/路径/返回类型）与基线不一致';
      kept.push(s);
      continue;
    }
    exempted.push({ path: s.path, reason: entry.reason });
  }
  suspects.length = 0;
  suspects.push(...kept);

  if (suspects.length && !set.has(GENERATED)) {
    const why = await coverageVerdict();
    if (why.ok) {
      console.log(
        `${TAG} OK：检测到 ${suspects.length} 处 API 形状改动；${GENERATED} 已在基线提交且逐条覆盖（${why.note}），变更集无需重复 regen`
      );
      return;
    }
    fail(
      `检测到 API 形状改动，但变更集里没有 ${GENERATED}：\n  - ` +
        suspects
          .filter((s) => !s.covered)
          .map((s) => `${s.path}  (${s.line.slice(0, 100)})`)
          .join('\n  - ') +
        `\n${why.reason}` +
        `\n正解：起 trade 后 OPENAPI_IGNORE_CACHE=1 跑 gen-openapi-types，把 openapi.ts 与端点改动放同一提交。`
    );
  }

  /**
   * 覆盖判据：生成物在基线存在，且「基线源码 → 当前源码」的**净形状差**逐条能在生成物里
   * 找到（新增要求出现、删除要求消失）。净差 = 当前形状集 − 基线形状集。
   * 净差为空（只改了请求体以外的注解参数、或文件没动到形状）⇒ 不覆盖，仍按原判据红。
   */
  async function coverageVerdict() {
    const atBase = await gitAsync(['cat-file', '-e', `${base}:${GENERATED}`], { cwd: ROOT });
    if (!atBase.ok) {
      return { ok: false, reason: `（${GENERATED} 在基线 ${base} 里不存在，无法用内容覆盖判据）` };
    }
    let generated = '';
    const abs = join(ROOT, GENERATED);
    if (existsSync(abs)) generated = readFileSync(abs, 'utf8');
    else {
      const shown = await gitAsync(['show', `HEAD:${GENERATED}`], { cwd: ROOT });
      if (shown.ok) generated = shown.out || '';
    }
    const uncovered = [];
    for (const s of suspects) {
      const before = await gitAsync(['show', `${base}:${s.path}`], { cwd: ROOT });
      const beforeKeys = new Set(shapesOf(s.path, before.ok ? before.out || '' : '').map(shapeKey));
      const current = shapesOf(s.path, await javaSource(s.path));
      const currentKeys = new Set(current.map(shapeKey));
      const added = current.filter((x) => !beforeKeys.has(shapeKey(x)));
      const removed = shapesOf(s.path, before.ok ? before.out || '' : '').filter(
        (x) => !currentKeys.has(shapeKey(x))
      );
      const delta = [
        ...added.map((shape) => ({ shape, sign: '+' })),
        ...removed.map((shape) => ({ shape, sign: '-' }))
      ];
      s.covered = delta.length > 0 && delta.every((x) => shapeCovered(x, generated));
      if (!s.covered) {
        s.delta =
          delta.map((x) => `${x.sign}${x.shape.kind}:${x.shape.value}`).join(',') || '（净差为空）';
        uncovered.push(s);
      }
    }
    return uncovered.length
      ? {
          ok: false,
          reason: `（基线里的 ${GENERATED} 未覆盖下列形状差——${uncovered
            .map((s) => `${s.path} ${s.delta}`)
            .join('；')}）`
        }
      : { ok: true, note: '按 schema 块/property/path 字面量逐条核对' };
  }

  console.log(
    `${TAG} OK：${suspects.length ? `检测到 ${suspects.length} 处 API 形状改动，变更集已包含 ${GENERATED}` : '变更集内无 API 形状改动'}` +
      (exempted.length
        ? `；另有 ${exempted.length} 处按登记豁免（仅 controller 服务端形参，映射签名与基线逐条相等）：${exempted
            .map((e) => e.path.split('/').pop())
            .join(', ')}`
        : '')
  );
}
