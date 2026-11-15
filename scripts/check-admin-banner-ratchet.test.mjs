#!/usr/bin/env node
/**
 * `check-admin-banner-ratchet.mjs` 的负向夹具（MA-283 / S8③）。
 *
 *   node scripts/check-admin-banner-ratchet.test.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASELINE, bannerRatchetProblem, countAlerts } from './check-admin-banner-ratchet.mjs';

const TAG = '[check-admin-banner-ratchet.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

// 1) 计数判据：只数 <el-alert，普通标签不算。
//    ⚠️ 已知窄边界（不在 MA-283 范围）：`<el-alert-custom` 也会被 `\b` 命中，
//    因为 `t` 与 `-` 之间是词边界；门禁 javadoc 明说自定义标签暂未纳入，
//    这里不顺便改计数口径（改了会让 49 这个实测值失真）。
assert(countAlerts('<el-alert title="a" />\n<el-alert>') === 2, '两个常驻横幅应计 2');
assert(
  countAlerts('<el-button>x</el-button>\n<p>el-alert 字样</p>') === 0,
  '普通标签/文本不该计入'
);

// 2) 三态判据：超基线红 / 持平绿 / 低于基线也红（棘轮只降不升）
assert(/新增常驻横幅被禁止/.test(bannerRatchetProblem(52, 49) || ''), 'total > baseline 必须红');
assert(bannerRatchetProblem(49, 49) === null, 'total == baseline 必须绿');
assert(
  /同步下调/.test(bannerRatchetProblem(46, 49) || ''),
  'total < baseline 必须红（旧实现只判单向，基线虚高会白送名额）'
);

// 3) 真实仓面：BASELINE 必须**恰好等于**当前实测横幅数。
//    这条是 MA-283 的核心——旧实现 BASELINE=52 而实测 49，等于送 3 个免费名额；
//    且只判单向，横幅删到 45 时基线仍挂 52，又能加回来。
function walk(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (name.endsWith('.vue')) out.push(full);
  }
  return out;
}
const views = walk(join(ROOT, 'clients/admin-vue/src/views'));
assert(views.length > 10, 'views 扫描锚点失效（扫到 0 个 .vue）');
const realTotal = views.reduce((sum, f) => sum + countAlerts(readFileSync(f, 'utf8')), 0);
assert(
  bannerRatchetProblem(realTotal, BASELINE) === null,
  `BASELINE=${BASELINE} 与实测 ${realTotal} 不一致：${bannerRatchetProblem(realTotal, BASELINE)}`
);

// 4) 负向：把「低于基线」判据删掉（退回只判单向）⇒ 上面第 2 条会红，
//    这里再用一个显式场景钉住退化方向
assert(
  bannerRatchetProblem(BASELINE - 3, BASELINE) !== null,
  '少 3 个横幅时基线不同步下调必须红（否则又出现 3 个免费名额）'
);

console.log(`${TAG} OK：${checks} 项断言全过（实测 ${realTotal} / 基线 ${BASELINE}）`);
