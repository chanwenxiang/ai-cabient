#!/usr/bin/env node
/**
 * R20 P3-5 / P3-10 门禁（2026-11-12）。
 *
 * 钉三件事，每一条都贴真实责任边界，且**扫源码前抹注释**（等长空格，保行号）——
 * 本轮修复在每个落点都写了成因注释，注释里复述了旧写法/关键词，不抹必假红或假绿。
 *
 * 1. 活动窗三处判据同源：findActiveInWindow 的 wrapper（IS NULL ×2 / le / gt）
 *    与 @Select 的 (start_time <= now()) / (end_time > now()) 比较符**自动对齐**；
 *    ConsumerMarketingService.doClaimCampaign 的结束端点必须是「到点即止」。
 * 2. 跨端字典自动对齐：SysDictBootstrap 的 map.put(...,m(...)) 与 shared-dict 的同名族
 *    键值集合逐项相等（常量引用走 CabinetConstants/本地常量解析，不用人工白名单）。
 * 3. 三个账单/应付下拉不得再手写中文 label，必须由 useDictOptions 渲染。
 *
 * 用法：
 *   node scripts/check-p3-window-and-dict-parity.mjs             # 正向门禁
 *   node scripts/check-p3-window-and-dict-parity.mjs --self-test # 负向自测（沙箱化变异，不碰磁盘）
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();
const P = {
  bootstrap:
    'services/trade-service/src/main/java/com/aicabinet/trade/service/SysDictBootstrap.java',
  constants:
    'services/common/common-core/src/main/java/com/aicabinet/common/constants/CabinetConstants.java',
  dictTs: 'packages/shared-dict/src/index.ts',
  mapper:
    'services/trade-service/src/main/java/com/aicabinet/trade/mapper/PromotionActivityMapper.java',
  marketing:
    'services/trade-service/src/main/java/com/aicabinet/trade/service/ConsumerMarketingService.java',
  rental: 'clients/admin-vue/src/views/finance/DeviceRentalView.vue',
  sites: 'clients/admin-vue/src/views/system/OrgSitesView.vue',
  warehouse: 'clients/admin-vue/src/views/warehouse/WarehouseView.vue'
};

// 跨端必须同词表的族（P3-10 涉及；账单三族 + 应付族 + 盘点方式 + 补偿任务）。
const PARITY_TYPES = [
  'device_rental_bill_status',
  'site_rent_bill_status',
  'device_data_fee_bill_status',
  'supplier_payable_status',
  'stocktake_mode',
  'compensation_task_status'
];

/** 注释替换为等长空格：保行号/列偏移，避免注释里的示例写法被当成实现。 */
function maskComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*|<!--[\s\S]*?-->/g, (m) =>
    m.replace(/[^\n]/g, ' ')
  );
}

/** 从 openIdx（指向开括号的下一个字符）起截取配平内容。 */
function balanced(src, openIdx, open, close) {
  let depth = 1;
  let inStr = null;
  for (let i = openIdx; i < src.length; i++) {
    const c = src[i];
    if (inStr) {
      if (c === '\\') {
        i++;
        continue;
      }
      if (c === inStr) inStr = null;
      continue;
    }
    if (c === '"' || c === "'") {
      inStr = c;
      continue;
    }
    if (c === open) depth++;
    else if (c === close && --depth === 0) return src.slice(openIdx, i);
  }
  return '';
}

/** 按顶层逗号切参数；字符串里的逗号不切。 */
function splitArgs(body) {
  const out = [];
  let buf = '';
  let depth = 0;
  let inStr = null;
  for (const c of body) {
    if (inStr) {
      buf += c;
      if (c === inStr) inStr = null;
      continue;
    }
    if (c === '"' || c === "'") {
      inStr = c;
      buf += c;
      continue;
    }
    if (c === '(' || c === '{' || c === '[') depth++;
    if (c === ')' || c === '}' || c === ']') depth--;
    if (c === ',' && depth === 0) {
      out.push(buf.trim());
      buf = '';
      continue;
    }
    buf += c;
  }
  if (buf.trim()) out.push(buf.trim());
  return out;
}

function collectConstants(src) {
  const map = new Map();
  for (const m of src.matchAll(/static\s+final\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]+)"/g)) {
    map.set(m[1], m[2]);
  }
  return map;
}

function resolveToken(tok, consts) {
  const t = tok.trim();
  if (/^"[^"]*"$/.test(t)) return t.slice(1, -1);
  const id = t.includes('.') ? t.slice(t.lastIndexOf('.') + 1) : t;
  return consts.get(id) ?? null;
}

function parseJavaDicts(src, consts) {
  const out = new Map();
  const re = /map\.put\(\s*"([a-z0-9_]+)"\s*,\s*t\(\s*"[^"]*"\s*,\s*m\(/g;
  for (const m of src.matchAll(re)) {
    const body = balanced(src, m.index + m[0].length, '(', ')');
    const args = splitArgs(body);
    const pairs = new Map();
    for (let i = 0; i + 1 < args.length; i += 2) {
      const k = resolveToken(args[i], consts);
      const v = resolveToken(args[i + 1], consts);
      if (k && v) pairs.set(k, v);
    }
    out.set(m[1], pairs);
  }
  return out;
}

function parseTsDicts(src) {
  const out = new Map();
  for (const m of src.matchAll(/([a-z][a-z0-9_]*)\s*:\s*\{/g)) {
    const body = balanced(src, m.index + m[0].length, '{', '}');
    const pairs = new Map();
    for (const kv of body.matchAll(/([A-Z][A-Z0-9_]*)\s*:\s*'([^']+)'/g)) pairs.set(kv[1], kv[2]);
    out.set(m[1], pairs);
  }
  return out;
}

/** 取方法定义体（按「签名 + 首个 {」配平）。 */
function methodBody(src, signature) {
  const idx = src.indexOf(signature);
  if (idx < 0) return null;
  const brace = src.indexOf('{', idx);
  if (brace < 0) return null;
  return balanced(src, brace + 1, '{', '}');
}

/** 判据纯函数：喂文件内容的读取器，返回失败清单（空 = 绿）。self-test 直接喂变异文本。 */
function evaluate(readFile) {
  const failures = [];
  const fail = (msg) => failures.push(msg);
  const read = (rel) => {
    try {
      return maskComments(readFile(rel));
    } catch (e) {
      fail(`读不到 ${rel}: ${e.message}`);
      return '';
    }
  };

  // ── 规则 1：活动窗口径 ──────────────────────────────────────────────
  const mapperSrc = read(P.mapper);
  const marketingSrc = read(P.marketing);
  const windowBody = methodBody(mapperSrc, 'findActiveInWindow(String status, Instant now)');
  if (windowBody === null) {
    fail('P3-5：找不到 PromotionActivityMapper.findActiveInWindow 的方法体');
  } else {
    const nullCount = (windowBody.match(/\.isNull\(/g) || []).length;
    if (nullCount !== 2)
      fail(
        `P3-5：起止两侧都要有 IS NULL 分支（当前 ${nullCount} 个），否则不设期限的活动在列表里消失`
      );
    if (!windowBody.includes('.le(')) fail('P3-5：起点必须 le（含端点）');
    if (!windowBody.includes('.gt(')) fail('P3-5：终点必须 gt（不含端点，到点即止）');
    if (windowBody.includes('.lt('))
      fail('P3-5：起点退回 lt 会与定价 SQL 在起始那一瞬给出相反结论');
  }

  const claimBody = methodBody(marketingSrc, 'private CouponDto doClaimCampaign(');
  if (claimBody === null) {
    fail('P3-5：找不到 ConsumerMarketingService.doClaimCampaign 的方法体');
  } else {
    if (!claimBody.includes('!now.isBefore('))
      fail('P3-5：领券的结束端点必须 now >= end 即拒（到点即止）');
    if (claimBody.includes('now.isAfter('))
      fail('P3-5：now.isAfter(end) 会让 end 那一瞬仍可领，与 SQL 相反');
  }

  // SQL 真值 ↔ wrapper 比较符自动对齐（不写死期望值，读两侧互相校验）。
  const sqlBlock = (mapperSrc.match(/@Select\("""([\s\S]*?)"""/) || ['', ''])[1];
  const sqlStart = /start_time\s*(<=|<)\s*now\(\)/.exec(sqlBlock);
  const sqlEnd = /end_time\s*(>=|>)\s*now\(\)/.exec(sqlBlock);
  if (!sqlStart || !sqlEnd) {
    fail('P3-5：定价 SQL 的活动窗比较符读不到（@Select 真值变了？）');
  } else if (windowBody) {
    const javaStart = windowBody.includes('.le(') ? '<=' : windowBody.includes('.lt(') ? '<' : '?';
    const javaEnd = windowBody.includes('.gt(') ? '>' : windowBody.includes('.ge(') ? '>=' : '?';
    if (javaStart !== sqlStart[1])
      fail(`P3-5：起点口径分裂（wrapper=${javaStart} / SQL=${sqlStart[1]}）`);
    if (javaEnd !== sqlEnd[1]) fail(`P3-5：终点口径分裂（wrapper=${javaEnd} / SQL=${sqlEnd[1]}）`);
  }

  // ── 规则 2：跨端字典自动对齐 ─────────────────────────────────────────
  const consts = new Map([
    ...collectConstants(read(P.constants)),
    ...collectConstants(read(P.bootstrap))
  ]);
  const javaDicts = parseJavaDicts(read(P.bootstrap), consts);
  const tsDicts = parseTsDicts(read(P.dictTs));

  for (const type of PARITY_TYPES) {
    const backend = javaDicts.get(type);
    const frontend = tsDicts.get(type);
    if (!backend || !frontend) {
      fail(`P3-10：字典族 ${type} 缺一侧（后端=${!!backend} / 前端=${!!frontend}）`);
      continue;
    }
    const fmt = (m) =>
      [...m.entries()]
        .map(([k, v]) => `${k}=${v}`)
        .sort()
        .join(',');
    if (fmt(backend) !== fmt(frontend)) {
      fail(`P3-10：${type} 跨端口径分裂\n    后端: ${fmt(backend)}\n    前端: ${fmt(frontend)}`);
    }
  }

  // 「待付 vs 未付」是刻意的两套业务视角，钉住不得被「统一文案」的好心改掉。
  const billLabel = javaDicts.get('device_rental_bill_status')?.get('UNPAID');
  const payableLabel = javaDicts.get('supplier_payable_status')?.get('UNPAID');
  if (billLabel !== '待付' || payableLabel !== '未付') {
    fail(
      `P3-10：UNPAID 的两个业务视角被改掉（账单=${billLabel} / 应付=${payableLabel}）；` +
        '账单是「等对方付款」、应付是「我方尚未付款」，不是文案漂移'
    );
  }

  // ── 规则 3：下拉不得手写字典 label ───────────────────────────────────
  for (const key of ['rental', 'sites', 'warehouse']) {
    const src = read(P[key]);
    const hardcoded =
      src.match(/label="(?:待付|未付|已付|已作废|部分付款|已关闭|明盘|盲盘)"/g) || [];
    if (hardcoded.length)
      fail(`P3-10：${P[key]} 仍有手写字典 label（${hardcoded.join(',')}），会再次漂移`);
    if (!/v-for="item in \w+Options"/.test(src))
      fail(`P3-10：${P[key]} 的状态下拉没有走 useDictOptions 渲染`);
  }

  return failures;
}

/** 负向自测：只在内存里变异，绝不写磁盘；每个用例必须「注入旧行为→转红」。 */
function selfTest() {
  const baseline = new Map();
  for (const rel of Object.values(P)) baseline.set(rel, readFileSync(join(ROOT, rel), 'utf8'));
  const withFile = (rel, transform) => {
    const copy = new Map(baseline);
    copy.set(rel, transform(baseline.get(rel)));
    return (r) => copy.get(r);
  };

  const cases = [
    { desc: '基线全绿', reader: (r) => baseline.get(r), expectGreen: true },
    {
      desc: '删掉一个 IS NULL 分支（不设期限的活动重新隐身）',
      reader: withFile(P.mapper, (s) =>
        s.replace('.isNull(PromotionActivity::getEndTime).or().', '')
      ),
      expectGreen: false
    },
    {
      desc: '起点 le 退回 lt（与定价 SQL 端点分裂）',
      reader: withFile(P.mapper, (s) =>
        s.replace(
          '.le(PromotionActivity::getStartTime, now)',
          '.lt(PromotionActivity::getStartTime, now)'
        )
      ),
      expectGreen: false
    },
    {
      desc: 'SQL 终点改成 >=（wrapper 未同步）',
      reader: withFile(P.mapper, (s) => s.replace('end_time > now()', 'end_time >= now()')),
      expectGreen: false
    },
    {
      desc: '领券端点退回 now.isAfter(end)',
      reader: withFile(P.marketing, (s) =>
        s.replace('!now.isBefore(activity.getEndTime())', 'now.isAfter(activity.getEndTime())')
      ),
      expectGreen: false
    },
    {
      desc: '前端盘点方式回到遗留码 VISIBLE（V453 已清，跨端分裂）',
      reader: withFile(P.dictTs, (s) =>
        s.replace(
          "stocktake_mode: { BLIND: '盲盘', OPEN: '明盘' }",
          "stocktake_mode: { BLIND: '盲盘', VISIBLE: '明盘' }"
        )
      ),
      expectGreen: false
    },
    {
      desc: '后端删掉 compensation_task_status 字典族',
      reader: withFile(P.bootstrap, (s) =>
        s.replace(/map\.put\("compensation_task_status"[\s\S]*?\)\)\);/, '')
      ),
      expectGreen: false
    },
    {
      desc: '设备租赁页加回手写「未付」',
      reader: withFile(P.rental, (s) =>
        s.replace(
          '<el-option\n                v-for="item in billStatusOptions"',
          '<el-option label="未付" value="UNPAID" />\n              <el-option\n                v-for="item in billStatusOptions"'
        )
      ),
      expectGreen: false
    },
    // 注释干扰：maskComments 若失效，这一条会假红（注释里复述了旧写法与手写 label）。
    {
      desc: '注释里写旧写法/手写 label 不得假红',
      reader: withFile(P.marketing, (s) =>
        s.replace(
          'private CouponDto doClaimCampaign(',
          '// 历史写法 now.isAfter(end) 已废弃；label="未付" 也只是注释示例\n    private CouponDto doClaimCampaign('
        )
      ),
      expectGreen: true
    }
  ];

  let bad = 0;
  for (const c of cases) {
    const errs = evaluate(c.reader);
    const green = errs.length === 0;
    if (green !== c.expectGreen) {
      bad++;
      console.error(
        `  [负向自测 FAIL] ${c.desc}：期望${c.expectGreen ? '绿' : '红'}，实得${green ? '绿' : '红'}`
      );
      for (const e of errs) console.error(`    - ${e.split('\n')[0]}`);
    } else {
      console.log(`  [OK] ${c.desc} → ${green ? '绿' : '红'}（${errs.length} 条判据命中）`);
    }
  }
  if (bad) {
    console.error(
      `[check-p3-window-and-dict-parity --self-test] FAIL（${bad}/${cases.length} 个用例不符）`
    );
    process.exit(1);
  }
  console.log(`[check-p3-window-and-dict-parity --self-test] OK（${cases.length} 个用例全符合）`);
}

if (process.argv.includes('--self-test')) {
  selfTest();
} else {
  const failures = evaluate((rel) => readFileSync(join(ROOT, rel), 'utf8'));
  if (failures.length) {
    console.error(`[check-p3-window-and-dict-parity] FAIL（${failures.length} 条）`);
    for (const f of failures) console.error(`  - ${f}`);
    process.exit(1);
  }
  console.log(
    '[check-p3-window-and-dict-parity] OK（活动窗三处同源 / 6 个字典族跨端一致 / 3 个下拉字典驱动）'
  );
}
