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
 *     的 diff 里出现增删的字段声明。
 * 命中形状变更且变更集里没有 openapi.ts => 红，并列出触发文件。
 *
 * 变更集 = base...HEAD（已提交）∪ base 工作树/暂存区（未提交）∪ 未跟踪文件。
 * base 默认 origin/dev，可用 OPENAPI_BASE_REF 覆盖；找不到 base 一律 fail-closed。
 *
 * 负向验证：给任一 Controller 加一个 `@RequestParam` 且不提交 openapi.ts => 必须红；
 * 同时带上 openapi.ts 改动（哪怕只加注释）=> 绿。只改 Controller 方法体 => 绿。
 *
 *   node scripts/check-openapi-regen-required.mjs
 */
import { gitAsync } from './lib/async-spawn.mjs';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-openapi-regen-required]';
const GENERATED = 'packages/shared-types/src/generated/openapi.ts';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

const candidates = [process.env.OPENAPI_BASE_REF, 'origin/dev', 'origin/main', 'dev'].filter(
  Boolean
);
let base = null;
for (const ref of candidates) {
  const r = await gitAsync(['rev-parse', '--verify', '--quiet', `${ref}^{commit}`], { cwd: ROOT });
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

const suspects = [];
for (const path of set) {
  if (!path.endsWith('.java')) continue;
  if (!isController(path) && !isDto(path)) continue;
  const diff = await diffText(path);
  if (!diff) continue;
  const changedLines = diff
    .split(/\r?\n/)
    .filter((l) => /^[+-]/.test(l) && !/^(\+\+\+|---)/.test(l))
    .map((l) => l.slice(1));
  const shapeHit = changedLines.find((line) =>
    isController(path) ? SHAPE_ANNOTATION.test(line) : FIELD_DECL.test(line)
  );
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
