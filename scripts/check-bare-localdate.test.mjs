#!/usr/bin/env node
/**
 * `check-bare-localdate.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-bare-localdate.test.mjs
 */
import { evaluate, stripComments, countInFile } from './check-bare-localdate.mjs';

const TAG = '[check-bare-localdate.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

// 1) 双向棘轮：命中数与基线严格相等 ⇒ 绿
{
  const baseline = [{ file: 'a.java', count: 2 }];
  const actual = [{ file: 'a.java', count: 2 }];
  assert(evaluate(actual, baseline, 'bare').length === 0, '与基线相等应绿');
}

// 2) 新文件出现未登记命中 ⇒ 红
{
  const baseline = [{ file: 'a.java', count: 2 }];
  const actual = [
    { file: 'a.java', count: 2 },
    { file: 'b.java', count: 1 }
  ];
  const p = evaluate(actual, baseline, 'bare');
  assert(p.length === 1 && p[0].includes('不在基线'), `新增文件须红：${p.join('|')}`);
}

// 3) 已登记文件命中变多（有人新加裸调用）⇒ 红
{
  const baseline = [{ file: 'a.java', count: 2 }];
  const actual = [{ file: 'a.java', count: 3 }];
  const p = evaluate(actual, baseline, 'bare');
  assert(p.length === 1 && p[0].includes('不符'), `新增命中须红：${p.join('|')}`);
  assert(p[0].includes('新增未登记'), `红因要点明方向：${p[0]}`);
}

// 4) 已登记文件命中变少（删了旧调用没销基线）⇒ 红
{
  const baseline = [{ file: 'a.java', count: 3 }];
  const actual = [{ file: 'a.java', count: 2 }];
  const p = evaluate(actual, baseline, 'bare');
  assert(p[0].includes('基线条目应同步销账'), `删旧未销须红：${p.join('|')}`);
}

// 5) 基线条目对应文件已零命中 ⇒ 陈旧红
{
  const baseline = [{ file: 'gone.java', count: 1 }];
  const actual = [];
  const p = evaluate(actual, baseline, 'bare');
  assert(p.length === 1 && p[0].includes('基线陈旧'), `陈旧基线须红：${p.join('|')}`);
}

// 6) 注释与字符串不计数；带参 LocalDate.now(ZoneId.of(...)) 不算裸
{
  const src = `
    // 历史：LocalDate.now() 裸用（R19-17 前）
    String hint = "LocalDate.now()";
    // 参考：LocalDate.now(ZoneId.of("Asia/Shanghai")) 是合规形态
    LocalDate d = LocalDate.now();
    LocalDate ok = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    ZoneId z = ZoneId.systemDefault();
  `;
  const masked = stripComments(src);
  assert(
    countInFile(masked, 'bare') === 1,
    `注释/字符串不计数、带参不裸：实际 ${countInFile(masked, 'bare')}`
  );
  assert(countInFile(masked, 'systemDefault') === 1, `systemDefault 应命中 1`);
}

// 7) 真实仓面：基线 × 真实扫描 ⇒ 零问题（复刻 main 口径，不用同步子进程）
{
  const { readFileSync, readdirSync, statSync } = await import('node:fs');
  const { dirname, join, relative, resolve } = await import('node:path');
  const { fileURLToPath } = await import('node:url');
  const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const baseline = JSON.parse(readFileSync(join(ROOT, 'scripts/localdate-baseline.json'), 'utf8'));
  const walk = (dir, out = []) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      const st = statSync(full);
      if (st.isDirectory()) {
        if (['node_modules', 'target', 'static'].includes(name)) continue;
        walk(full, out);
      } else if (name.endsWith('.java')) out.push(full);
    }
    return out;
  };
  const files = walk(join(ROOT, 'services')).filter((p) => /src[\\/]main[\\/]/.test(p));
  assert(files.length > 0, '应扫到主源码');
  const actual = { bare: [], systemDefault: [] };
  for (const abs of files) {
    const rel = relative(ROOT, abs).replace(/\\/g, '/');
    const masked = stripComments(readFileSync(abs, 'utf8'));
    const b = countInFile(masked, 'bare');
    const s = countInFile(masked, 'systemDefault');
    if (b) actual.bare.push({ file: rel, count: b });
    if (s) actual.systemDefault.push({ file: rel, count: s });
  }
  const problems = [
    ...evaluate(actual.bare, baseline.bareLocalDate, 'bare'),
    ...evaluate(actual.systemDefault, baseline.zoneIdSystemDefault, 'systemDefault')
  ];
  assert(problems.length === 0, `真实树应零问题：${problems.join('|')}`);
}

console.log(`${TAG} OK：${checks} 项断言全过`);
