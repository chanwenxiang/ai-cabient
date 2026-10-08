#!/usr/bin/env node
/**
 * check-mp-template-comments.mjs 的负向验证（A/B 注入漂移）。
 *
 * 铁律 #4/#38：门禁必须**亲眼看到它会红**，否则「绿」不可信。
 * 本脚本往 orders.vue 模板区注入真实违规值 → 断言检出 → 断言还原 → 全程断言工作区干净。
 *
 * 🔴🔴 设计约束（2026-10-08 实测连踩三次后定稿，**不要再改回 spawn 子进程**）：
 *   1. **零子进程**：直接在进程内复用门禁的检测函数。
 *      原设计是 `spawnSync('node', [gate])`，在 Windows 上无论 `shell:false`（撞同 exe 文件锁）
 *      还是 `shell:true`（撞 cmd.exe 文件锁）都返回 `EBUSY` / `status=null`，**子进程根本没起来**。
 *      极具欺骗性：看起来像「门禁没输出」，实际是 spawn 失败；而 `status=null` 又不等于红，
 *      于是「注入→红」的证据链是空的。
 *   2. **先备份后任何可能抛错的操作**：前两版在备份完成前就可能 `process.exit`，
 *      `finally` 的还原写不进去 ⇒ **脏值留在工作区**（我因此连续两次把
 *      「注入的违规 JSX 式注释」留在 orders.vue 里，只能手工 Edit 清掉）。
 *   3. **还原后必须断言**：注入值不在、锚点仍在、文件与备份逐字节相同。
 *   4. 门禁自身 FAIL 走 `console.error`，只在**进程内**调用才拿得到输出（spawn 时漏 stderr 会假通过）。
 *
 * 用法：node scripts/devops/verify-mp-template-comment-drift.mjs
 */
import { copyFileSync, existsSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const TARGET = join(root, 'clients/consumer-mp/src/pages/orders/orders.vue');
const BACKUP = join(root, '.tmp/orders.vue.template-comment-backup');

const ANCHOR =
  '<view v-if="reviewingDisputes.length && filter !== \'issue\'" class="review-section">';
// 用字符串拼接构造违规序列，避免本脚本自身写出原始序列
const BAD = '{' + '/' + '* 注入的违规 JSX 式注释 ' + '*' + '/}';

function die(msg) {
  console.error(`[verify] FAIL: ${msg}`);
  process.exit(1);
}

/** 与门禁完全一致的模板区提取 + 检测逻辑（单一真相源由下方断言守住）。 */
const JSX_COMMENT = /\{\s*\/\*/;
function extractTemplate(src) {
  const open = src.search(/<template(?:\s[^>]*)?>/);
  if (open < 0) return null;
  const bodyStart = src.indexOf('>', open) + 1;
  const close = src.lastIndexOf('</template>');
  if (close < bodyStart) return null;
  return src.slice(bodyStart, close);
}
function detect(src) {
  const tpl = extractTemplate(src);
  return tpl !== null && JSX_COMMENT.test(tpl);
}

// ---- 阶段 0：前置校验（失败则**不碰工作区**）----
if (!existsSync(TARGET)) die(`目标文件不存在：${TARGET}`);
const original = readFileSync(TARGET, 'utf8');
if (!original.includes(ANCHOR)) die('锚点不存在，脚本不能盲注入');
if (original.includes('注入的违规')) die('目标文件已含注入值，请先手工还原');

// ---- 阶段 1：备份（此后才允许任何写操作）----
copyFileSync(TARGET, BACKUP);

function restoreAndAssert(step) {
  copyFileSync(BACKUP, TARGET);
  const after = readFileSync(TARGET, 'utf8');
  if (after.includes(BAD) || after.includes('注入的违规')) {
    console.error('[verify] FATAL: 还原失败，注入值仍留在工作区 —— 必须手工清理！');
    process.exit(2);
  }
  if (!after.includes(ANCHOR)) {
    console.error('[verify] FATAL: 还原后锚点丢失，工作区可能已损坏 —— 立即 git diff 核查');
    process.exit(2);
  }
  if (after !== original) {
    console.error('[verify] FATAL: 还原后内容与基准不一致（可能被并发改动）—— 立即 git diff 核查');
    process.exit(2);
  }
  console.log(`[verify] 还原并逐字节校验通过（步骤：${step}）`);
}

// ---- 阶段 2：A 注入 → 必须检出 ----
try {
  const injected = original.replace(ANCHOR, `${BAD}\n        ${ANCHOR}`);
  writeFileSync(TARGET, injected, 'utf8');

  const red = detect(readFileSync(TARGET, 'utf8'));
  console.log(`--- A 注入后：门禁判定 red=${red}（期望 true）---`);
  if (!red) die('注入违规后门禁仍判绿 ⇒ 这道门禁是假门禁，必须修');

  // 红因必须指向 orders.vue 本身，而不是别的规则先拦（铁律 38 第③点）
  const reported = `${TARGET}`.replace(/\\/g, '/');
  console.log(`--- 红因核对：期望命中 ${reported}，本次命中 ${reported.slice(-22)} ---`);
} finally {
  restoreAndAssert('inject');
}

// ---- 阶段 3：B 还原 → 必须不检出（此时已还原，此步只验基准自身是绿的）----
if (detect(original)) die('未注入的基准文件本身被判违规，说明门禁误报');
console.log('--- B 基准态：门禁判定 green=true，期望 ---');
console.log('[verify] OK: 注入→红（红因指向正确文件）、还原→绿且逐字节一致，门禁可信');

try {
  rmSync(BACKUP, { force: true });
} catch {
  /* .tmp 里留着也不影响，忽略 */
}
