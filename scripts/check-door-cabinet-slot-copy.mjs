/**
 * 门禁：开门柜界面上「货道」字样的棘轮（只减不增）+ 已退役货道告警标识不得复活。
 *
 * 背景（2026-11-04 用户第三次重申「开门柜去掉货道概念」并已执行三批）：
 * 货道机件（`DeviceSlotService` 的货道分支、slots CRUD、FEFO per-slot、盘点…）本体退场要动 40 个测试类
 * 与一次全量 Docker IT，不可能一轮做完；但**用户可见文案**可以先钉住，否则一边删一边会被新代码加回来。
 * 本门禁把「三端源码里非注释的『货道』出现次数」钉成只降不升的基线：
 *   - 变多 ⇒ 直接红（新增可见货道措辞）。
 *   - 变少 ⇒ 也红，要求把 BASELINE 下调到实测值（防基线虚高掩盖倒退）。
 * 历史订单行里的 `·货道A1` 属**数据解析**（legacy 展示），不在本门禁范围内——它只出现在 *.test.ts 夹具，
 * 而测试文件本就排除；真要清 legacy 文案请连同 `order-line-summary` 的解析一起在批 3b 里判。
 *
 * 第二判据（第十一刀加）：`SLOT_DISCREPANCY` / `SlotDiscrepancyAlertDto` / `slot-discrepancies`
 *   在「能重新长出界面」的作用域（后端 main、三端 src、生成的契约）的**非注释**里出现即红。
 *
 * 自检：`node scripts/check-door-cabinet-slot-copy.mjs --selftest`（夹具在 .tmp 沙箱，绝不读真实树）。
 * 打印实测：`--print`。
 */
import { mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

const ROOT = process.cwd();
const SCOPES = [
  'clients/admin-vue/src',
  'clients/merchant-mp/src',
  'clients/consumer-mp/src',
  'packages/shared-uni/src'
];
/** 🔴 显式常量，不是文件清单长度；批 3b 每清一档就下调这里。
 *  2026-11-04 首建实测 89 处 / 26 文件（大头：merchant device-detail 12、useReplenishmentDisplay 9、
 *  admin ReplenishmentView 8、MerchantSplitsView 7、ReplenishLinesSection 7、device-stocktake 7、
 *  useDeviceSlotActions 6；消费者端 orders.vue 也有 3 处——那是最该先切的）。
 *  同日第一档已切完**消费者端**（orders 的货道标签+解析、order-detail 行内「· 货道 X」、
 *  result 的 lineMeta 货道段，共 5 处 / 3 文件）⇒ 84 处 / 23 文件。
 *  同日第二档（商户端可达性复核）后为 83 处 / 23 文件；批 3b 首刀删掉 admin 货道 UI 后
 *  （useDeviceSlotActions 6 处 + 设备详情 4 处）为 **73 处 / 21 文件**。
 *  🔴 **命中数 ≠ 可见数**：逐处核过可达性，剩 83 处里绝大多数位于 `isDoorCabinet()` 守卫之内、
 *  或 `!isDoorCabinet` 的模板分支里（slotHint / stockDeltaText / 货道编辑格 / 「缺货 N 个货道」的
 *  非门柜分支…），开门柜用户看不到；它们属批 3b 要整段删掉的机件，不值为「降计数」去改写。
 *  真正可见的只有已修掉的：device-detail 那句「无货道概念」解释文案。
 *  🔴 S1a 教训（2026-11-04）：曾打算下线 mp「柜机盘点」页，**删前回查发现它同时承载开门柜盘点**
 *  （`isDoorCabinet` 分支 + `deviceDoorStocktake`）⇒ 整页删除已回退。凡「看起来是货道专属」的
 *  页面/组件，删前必须按 `isDoorCabinet` / `deviceDoorStocktake` 一类关键字反查一遍。 */
const BASELINE = Number(process.env.SLOT_COPY_BASELINE || 18);
const TERM = '货道';

/**
 * 已退役的货道告警标识：**非注释**里再出现即红（第十一刀，2026-11-05）。
 *
 * 为什么放这里而不是新建门禁：本文件已经是「开门柜不许有货道面」的守门人，同一口径两条判据
 * （文案棘轮 + 退役标识不得复活）共用一套扫描/剥注释逻辑，也免去第 98 号门禁的接线成本。
 *
 * 覆盖范围＝能重新长出界面的地方：后端生产代码、三端源码、生成的契约（`openapi.ts`）。
 * 🔴 刻意**不**扫 `packages/shared-dict/src`：那里的 `SLOT_DISCREPANCY: '货道账实差异'` 是
 *   **字典键**（非注释，扫了必红），保留它是历史行的兜底译名——删掉只会让旧数据显示「未知」。
 *   同理不扫 SQL 迁移（V445 按值置 INACTIVE，必须含该字面量）。
 */
const RETIRED_TERMS = ['SLOT_DISCREPANCY', 'SlotDiscrepancyAlertDto', 'slot-discrepancies'];
const RETIRED_SCOPES = [
  'services/trade-service/src/main/java',
  'services/common',
  'clients/admin-vue/src',
  'clients/merchant-mp/src',
  'clients/consumer-mp/src',
  'packages/shared-uni/src',
  'packages/shared-types/src'
];

function isDir(p) {
  try {
    return statSync(p).isDirectory();
  } catch {
    return false;
  }
}

function walk(dir, out = []) {
  if (!isDir(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === 'dist' || name === '.git') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    // 测试夹具里的「货道」是在断言 legacy 数据解析，不是界面文案 ⇒ 排除
    else if (/\.(vue|ts)$/.test(p) && !/\.(test|spec)\./.test(p) && !p.endsWith('.d.ts'))
      out.push(p);
  }
  return out;
}

/** 剥掉块注释、行注释与 HTML 注释；字符串里的中文一律算「界面文案」 */
function stripComments(text) {
  return text
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:'"`\\])\/\/[^\n]*/g, '$1');
}

function countTerm(text) {
  let n = 0;
  for (let i = text.indexOf(TERM); i >= 0; i = text.indexOf(TERM, i + TERM.length)) n++;
  return n;
}

/** 返回 [{file, count}]；scopeRoot 可注入，自检用沙箱 */
export function scan(scopeRoot = ROOT) {
  const rows = [];
  for (const s of SCOPES) {
    for (const f of walk(resolve(scopeRoot, s))) {
      const n = countTerm(stripComments(readFileSync(f, 'utf8')));
      if (n > 0) rows.push({ file: relative(scopeRoot, f).replace(/\\/g, '/'), count: n });
    }
  }
  return rows.sort((a, b) => b.count - a.count);
}

/** 退役标识的扫描要连 Java 一起看（文案棘轮只看三端 .vue/.ts） */
function walkCode(dir, out = []) {
  if (!isDir(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === 'dist' || name === '.git') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walkCode(p, out);
    else if (/\.(vue|ts|java)$/.test(p) && !/\.(test|spec)\./.test(p) && !p.endsWith('.d.ts'))
      out.push(p);
  }
  return out;
}

/** 非注释里复活退役标识的文件清单；scopeRoot 可注入，自检用沙箱 */
export function scanRetired(scopeRoot = ROOT) {
  const hits = [];
  for (const s of RETIRED_SCOPES) {
    for (const f of walkCode(resolve(scopeRoot, s))) {
      const body = stripComments(readFileSync(f, 'utf8'));
      const term = RETIRED_TERMS.find((t) => body.includes(t));
      if (term) hits.push({ file: relative(scopeRoot, f).replace(/\\/g, '/'), term });
    }
  }
  return hits.sort((a, b) => a.file.localeCompare(b.file));
}

function selfTest() {
  const sandbox = join(ROOT, '.tmp', `slot-copy-selftest-${process.pid}`);
  let failures = 0;
  const check = (name, cond, extra = '') => {
    console.log(`  [${cond ? 'ok' : 'RED'}] ${name}${extra ? ' —— ' + extra : ''}`);
    if (!cond) failures++;
  };
  try {
    rmSync(sandbox, { recursive: true, force: true });
    for (const s of SCOPES) mkdirSync(join(sandbox, s), { recursive: true });
    for (const s of RETIRED_SCOPES) mkdirSync(join(sandbox, s), { recursive: true });
    // 1) 注释里的不计
    writeFileSync(
      join(sandbox, 'clients/admin-vue/src/A.vue'),
      '<template><div>ok</div></template>\n<script setup>\n// 这里提到' +
        TERM +
        '是注释\n/* 也是' +
        TERM +
        '注释 */\n</script>\n'
    );
    // 2) 字符串里的要计
    writeFileSync(
      join(sandbox, 'clients/admin-vue/src/B.vue'),
      '<template><div>' + TERM + '热区</div><span label="' + TERM + '容量" /></template>\n'
    );
    // 3) 测试夹具不计
    writeFileSync(
      join(sandbox, 'clients/admin-vue/src/C.test.ts'),
      `const s = '${TERM}A1';\nexport default s;`
    );
    const rows = scan(sandbox);
    const total = rows.reduce((a, r) => a + r.count, 0);
    check('注释里的「货道」不计', !rows.some((r) => r.file.endsWith('A.vue')));
    check(
      '模板文本与属性里的「货道」计 2 处',
      total === 2,
      `实测 ${total}：${JSON.stringify(rows)}`
    );
    check('*.test.ts 夹具不计', !rows.some((r) => r.file.endsWith('C.test.ts')));
    // 4) 棘轮两条判据都要能红
    const verdict = (baseline) =>
      total > baseline ? 'red-over' : total < baseline ? 'red-under' : 'pass';
    check('高于基线判红', verdict(1) === 'red-over');
    check('低于基线判红（要求下调）', verdict(3) === 'red-under');
    check('相等才通过', verdict(2) === 'pass');
    // 5) 缺 scope 目录不崩（返回空而非异常）
    const empty = scan(join(sandbox, 'nope'));
    check('scope 不存在时返回空而不抛', Array.isArray(empty) && empty.length === 0);
    // 6) 退役标识：写进代码（非注释）必须被抓到——证这条判据会红
    mkdirSync(join(sandbox, 'services/trade-service/src/main/java/demo'), { recursive: true });
    writeFileSync(
      join(sandbox, 'services/trade-service/src/main/java/demo/R.java'),
      'class R { static final String T = "SLOT_DISCREPANCY"; }\n'
    );
    // 7) 只是注释里提一句退役留痕，不算复活
    writeFileSync(
      join(sandbox, 'services/trade-service/src/main/java/demo/N.java'),
      '// SLOT_DISCREPANCY 告警面已退场\n/* GET /slot-discrepancies 已删 */\nclass N {}\n'
    );
    const retired = scanRetired(sandbox);
    check(
      '退役标识出现在代码里即判红',
      retired.some((r) => r.file.endsWith('R.java')),
      JSON.stringify(retired)
    );
    check('退役标识只出现在注释里不算复活', !retired.some((r) => r.file.endsWith('N.java')));
    check(
      '退役扫描不误伤文案棘轮的夹具',
      retired.every((r) => !/(A|B|C)\.vue|A\.vue/.test(r.file))
    );
  } catch (e) {
    check('自检未抛异常', false, String(e && e.message));
  } finally {
    rmSync(sandbox, { recursive: true, force: true });
  }
  if (failures) {
    console.error(`[check-door-cabinet-slot-copy --selftest] FAIL ${failures} 项`);
    process.exit(1);
  }
  console.log('[check-door-cabinet-slot-copy --selftest] 判据通过（沙箱已删，未读真实树）');
  process.exit(0);
}

if (process.argv.includes('--selftest')) selfTest();

const rows = scan();
const total = rows.reduce((a, r) => a + r.count, 0);

if (process.argv.includes('--print')) {
  console.log(`实测「货道」可见文案出现次数 = ${total}（基线 ${BASELINE}），文件数 ${rows.length}`);
  for (const r of rows.slice(0, 12)) console.log(`  ${r.count}  ${r.file}`);
  process.exit(0);
}

const problems = [];
if (total > BASELINE) {
  problems.push(
    `开门柜界面里的「货道」文案从基线 ${BASELINE} 涨到 ${total} 处。` +
      '「开门柜不设货道概念」是已拍板口径（PK §12 2026-11-04 二/三/四批）：新界面请说「商品/库存」，' +
      '不要新增货道措辞；批 3b 正在把机件本身删掉。'
  );
} else if (total < BASELINE) {
  problems.push(
    `实测 ${total} 处 < 基线 ${BASELINE} 处：棘轮只减不增，请把 BASELINE 下调到 ${total}` +
      '（否则基线虚高会掩盖后续倒退）。'
  );
}

const retiredHits = scanRetired();
if (retiredHits.length) {
  problems.push(
    `已退役的货道告警标识在代码（非注释）里复活：${RETIRED_TERMS.join(' / ')}。` +
      '第十一刀（2026-11-05）已把 merchant `/slot-discrepancies` 端点、workbench `slotDiscrepancies`、' +
      'AdminStatsDto.slotDiscrepancyCount、mp 订阅项/待办卡归类整套删掉，字典行置 INACTIVE（V445）；' +
      '「账实差异」的入参是货道账面 vs 货道实盘，开门柜没这两样 ⇒ 不要再加回来。' +
      '（例外：packages/shared-dict 的历史译名与 SQL 迁移按值改状态，均不在本扫描范围内。）'
  );
}

if (problems.length) {
  console.error(`[check-door-cabinet-slot-copy] FAIL：`);
  for (const p of problems) console.error(`  - ${p}`);
  for (const h of retiredHits) console.error(`    复活 ${h.term} → ${h.file}`);
  console.error('  明细（前 12，按次数降序）：');
  for (const r of rows.slice(0, 12)) console.error(`    ${r.count}  ${r.file}`);
  process.exit(1);
}
console.log(
  `[check-door-cabinet-slot-copy] OK：三端非注释「货道」文案 ${total} 处，与基线相等（批 3b 每清一档下调）；` +
    `退役告警标识 ${RETIRED_TERMS.length} 个在 ${RETIRED_SCOPES.length} 个作用域内 0 复活`
);
