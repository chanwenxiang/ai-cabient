#!/usr/bin/env node
/**
 * check-preauth-zero-semantics 的负向夹具（2026-11-15）。
 * 每次变异只改一个变量，证明判据「绿得有依据、红得出来」：
 * 显式 0 分支 / return 0 / >0 正常分支 / 下游 amount<=0 落点 / 注释与字符串掩码。
 */
import {
  clientZeroSemanticsProblems,
  evaluateZeroSemantics,
  maskForScan,
  methodBody
} from './check-preauth-zero-semantics.mjs';

let pass = 0;
let fail = 0;
function check(name, cond, detail = '') {
  if (cond) {
    pass += 1;
    console.log(`  ✓ ${name}`);
  } else {
    fail += 1;
    console.error(`  ✗ ${name}${detail ? ' —— ' + detail : ''}`);
  }
}

const ZERO_BRANCH = `        if (fromDb == 0) {
            return 0;
        }
`;
const BASE = (
  zero = ZERO_BRANCH,
  freezeGuard = `        if (amount <= 0) {
            clearSessionPreauth(session);
            return;
        }
`
) => `
public class ConsumerPreauthService {
    public int resolvePreauthCents(String deviceId) {
        int fromDb = systemConfigService.getInt(KEY, -1);
${zero}        if (fromDb > 0 && fromDb <= CEILING) {
            return fromDb;
        }
        return checkoutProperties.resolvePreauthCents();
    }
    private void doFreezeForOpen(String sessionId) {
        int amount = resolvePreauthCents(session.getDeviceId());
${freezeGuard}        accountRepository.save(account);
    }
}`;

// 1) 正向：修复后的真实形态 = 绿
{
  const r = evaluateZeroSemantics(BASE());
  check('三锚点齐全=绿', r.length === 0, r.join('|'));
}

// 2) 复现原缺陷：整段 0 分支删掉（9d5c724f 的 `fromDb > 0` 单分支）= 红
{
  const r = evaluateZeroSemantics(BASE(''));
  check(
    '删显式 0 分支=红（复现静默回落）',
    r.length === 1 && r[0].includes('静默回落'),
    r.join('|')
  );
}

// 3) 0 分支在但返回非 0 = 红
{
  const r = evaluateZeroSemantics(BASE(ZERO_BRANCH.replace('return 0;', 'return 500;')));
  check('0 分支不 return 0=红', r.length === 1 && r[0].includes('fromDb == 0'), r.join('|'));
}

// 4) 注释里复述写法不得造出假绿（等长掩码）
{
  const src = BASE('').replace(
    '        if (fromDb > 0',
    '        // 曾经有 fromDb == 0 → return 0; 分支\n        if (fromDb > 0'
  );
  const r = evaluateZeroSemantics(src);
  check('注释里的 fromDb == 0 不得假绿', r.length === 1 && r[0].includes('静默回落'), r.join('|'));
}

// 5) 日志字符串里的写法同样不得假绿
{
  const src = BASE('').replace(
    '        if (fromDb > 0',
    '        log.info("fromDb == 0 return 0;");\n        if (fromDb > 0'
  );
  const r = evaluateZeroSemantics(src);
  check(
    '字符串里的 fromDb == 0 不得假绿',
    r.length === 1 && r[0].includes('静默回落'),
    r.join('|')
  );
}

// 6) 正常 >0 分支被删 = 红（防「全部返回 0」代替关闭语义）
{
  const src = BASE().replace('fromDb > 0 && fromDb <= CEILING', 'true');
  const r = evaluateZeroSemantics(src);
  check('丢 >0 正常分支=红', r.length === 1 && r[0].includes('正常分支'), r.join('|'));
}

// 7) 下游 amount<=0 落点被删 = 红
{
  const r = evaluateZeroSemantics(BASE(ZERO_BRANCH, ''));
  check(
    '下游清预授权分支被删=红',
    r.length === 1 && r[0].includes('clearSessionPreauth'),
    r.join('|')
  );
}

// 8) 方法体提取失败 = fail-closed 红，且掩码等长
{
  const r = evaluateZeroSemantics('public class Empty {}');
  check('方法体缺失=红（fail-closed）', r.length === 2, r.join('|'));
  const masked = maskForScan(`a\n// xxxxxx\nb`);
  check(
    '掩码等长且保留换行',
    masked.length === 'a\n// xxxxxx\nb'.length && masked.split('\n').length === 3
  );
  check('methodBody 找不到返回 null', methodBody('class X {}', 'nope(') === null);
}

const CLIENT_ACCOUNT = `
export function parsePreauthCents(raw: unknown): number | null {
  if (raw == null || String(raw).trim() === '') return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value >= 0 ? value : null;
}
export function resolveClientPreauthCents(opts?: {
  configPreauthCents?: number | string | null;
  devicePreauthCents?: number | null;
}): number {
  const cfg = parsePreauthCents(opts?.configPreauthCents);
  if (cfg === 0) return 0;
  const device = parsePreauthCents(opts?.devicePreauthCents);
  if (device !== null) return device;
  return DEFAULT;
}
export function preauthYuanLabel(preauthCents: number): string {
  if (preauthCents <= 0) return '0';
  return String(preauthCents);
}
export function isPayReady(acc: unknown, preauthCents: number): boolean {
  if (!acc) return false;
  return preauthCents <= 0 || availableCents(acc) >= preauthCents;
}
export function payReadyHint(acc: unknown, preauthCents: number): string {
  if (!acc) return 'login';
  if (preauthCents <= 0) return 'closed';
  return 'recharge';
}
`;
const CLIENT_PAGE = `configPreauthCents.value = parsePreauthCents(cfg?.preauthCents);`;
const CLIENT_INDEX = `devicePreauthCents.value = parsePreauthCents(status.preauthCents);`;
const CLIENT_SOURCES = {
  account: CLIENT_ACCOUNT,
  drawer: CLIENT_PAGE,
  verify: CLIENT_PAGE,
  mine: CLIENT_PAGE,
  index: CLIENT_INDEX
};

check('消费者端解析链与四入口齐全=绿', () => {
  const r = clientZeroSemanticsProblems(CLIENT_SOURCES);
  check('消费者端正向夹具无违规', r.length === 0, r.join('|'));
});

check('解析器把 >=0 改成 >0 ⇒ 红', () => {
  const r = clientZeroSemanticsProblems({
    ...CLIENT_SOURCES,
    account: CLIENT_ACCOUNT.replace('value >= 0', 'value > 0')
  });
  check(
    '显式 0 被解析器拒绝=红',
    r.some((p) => p.includes('没保留显式 0')),
    r.join('|')
  );
});

check('全局 0 分支删掉 ⇒ 红', () => {
  const r = clientZeroSemanticsProblems({
    ...CLIENT_SOURCES,
    account: CLIENT_ACCOUNT.replace('  if (cfg === 0) return 0;\n', '')
  });
  check(
    '配置 0 静默回落=红',
    r.some((p) => p.includes('缺全局配置 0')),
    r.join('|')
  );
});

check('全局 0 分支移到设备分支之后 ⇒ 红', () => {
  const moved = CLIENT_ACCOUNT.replace(
    '  if (cfg === 0) return 0;\n  const device = parsePreauthCents(opts?.devicePreauthCents);',
    '  const device = parsePreauthCents(opts?.devicePreauthCents);\n  if (cfg === 0) return 0;'
  );
  const r = clientZeroSemanticsProblems({ ...CLIENT_SOURCES, account: moved });
  check(
    '0 无法压倒设备押金=红',
    r.some((p) => p.includes('无法压倒设备押金')),
    r.join('|')
  );
});

check('DeviceStatus 的 0 被当缺失 ⇒ 红', () => {
  const r = clientZeroSemanticsProblems({
    ...CLIENT_SOURCES,
    account: CLIENT_ACCOUNT.replace(
      'if (device !== null) return device;',
      'if (device > 0) return device;'
    )
  });
  check(
    '已解析 0 回落=红',
    r.some((p) => p.includes('没把 DeviceStatus')),
    r.join('|')
  );
});

check('任一入口改回 Number(...) 且 >0 回落 ⇒ 红', () => {
  const r = clientZeroSemanticsProblems({
    ...CLIENT_SOURCES,
    mine: `const p = Number(cfg?.preauthCents);\nconfigPreauthCents.value = Number.isFinite(p) && p > 0 ? p : null;`
  });
  check(
    '入口绕过统一解析器=红',
    r.some((p) => p.includes('mine 没走 parsePreauthCents')) &&
      r.some((p) => p.includes('绕过 0 语义守卫')),
    r.join('|')
  );
});

check('关闭态展示/放行分支被删 ⇒ 红', () => {
  const r = clientZeroSemanticsProblems({
    ...CLIENT_SOURCES,
    account: CLIENT_ACCOUNT.replace("if (preauthCents <= 0) return '0';", '')
      .replace(
        'return preauthCents <= 0 || availableCents(acc) >= preauthCents;',
        'return availableCents(acc) >= preauthCents;'
      )
      .replace("if (preauthCents <= 0) return 'closed';", '')
  });
  check(
    '0 被显示成 0.01 或继续挡门=红',
    r.some((p) => p.includes('preauthYuanLabel')) &&
      r.some((p) => p.includes('isPayReady')) &&
      r.some((p) => p.includes('payReadyHint')),
    r.join('|')
  );
});

console.log(`\n[check-preauth-zero-semantics.test] ${pass} 通过 / ${fail} 失败`);
if (fail > 0) process.exit(1);
