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
 * 负向验证：`node scripts/check-openapi-regen-required.test.mjs`
 * （注解红 / ack 绿 / record 分量红 / 方法体绿 / fail-closed 五向，全程在 `.tmp`
 * 临时 git 仓库里跑，不碰真实树）。
 *
 *   node scripts/check-openapi-regen-required.mjs
 */
import { gitAsync } from './lib/async-spawn.mjs';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-openapi-regen-required]';
const GENERATED = 'packages/shared-types/src/generated/openapi.ts';

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
  if (isController(path)) return changedLines.find((line) => SHAPE_ANNOTATION.test(line));
  const components = recordComponentNames(content);
  return changedLines.find((line) => {
    if (FIELD_DECL.test(line)) return true;
    if (RECORD_DECL_LINE.test(line)) return true;
    if (!RECORD_COMPONENT.test(line)) return false;
    const name = tailIdentifier(line);
    return !!name && components.has(name);
  });
}

/** diff 文本里的增删内容行（去掉 +/- 前缀，排除文件头）。 */
export function changedContentLines(diff) {
  return diff
    .split(/\r?\n/)
    .filter((l) => /^[+-]/.test(l) && !/^(\+\+\+|---)/.test(l))
    .map((l) => l.slice(1));
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
    const diff = await diffText(path);
    if (!diff) continue;
    const changedLines = changedContentLines(diff);
    const shapeHit = pickShapeHit(path, changedLines, await javaSource(path));
    if (shapeHit) suspects.push({ path, line: shapeHit.trim() });
  }

  if (suspects.length && !set.has(GENERATED)) {
    fail(
      `检测到 API 形状改动，但变更集里没有 ${GENERATED}：\n  - ` +
        suspects.map((s) => `${s.path}  (${s.line.slice(0, 100)})`).join('\n  - ') +
        '\n正解：起 trade 后 OPENAPI_IGNORE_CACHE=1 跑 gen-openapi-types，把 openapi.ts 与端点改动放同一提交。'
    );
  }

  console.log(
    `${TAG} OK：${suspects.length ? `检测到 ${suspects.length} 处 API 形状改动，变更集已包含 ${GENERATED}` : '变更集内无 API 形状改动'}`
  );
}
