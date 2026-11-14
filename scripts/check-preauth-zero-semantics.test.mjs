#!/usr/bin/env node
/**
 * check-preauth-zero-semantics 的负向夹具（2026-11-15）。
 * 每次变异只改一个变量，证明判据「绿得有依据、红得出来」：
 * 显式 0 分支 / return 0 / >0 正常分支 / 下游 amount<=0 落点 / 注释与字符串掩码。
 */
import { evaluateZeroSemantics, maskForScan, methodBody } from './check-preauth-zero-semantics.mjs';

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

console.log(`\n[check-preauth-zero-semantics.test] ${pass} 通过 / ${fail} 失败`);
if (fail > 0) process.exit(1);
