#!/usr/bin/env node
/**
 * `check-mapper-tz-timezone.mjs` 的负向用例（S8④ 自证）。
 *
 *   node scripts/check-mapper-tz-timezone.test.mjs
 */
import { evaluateXml } from './check-mapper-tz-timezone.mjs';

const TAG = '[check-mapper-tz-timezone.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

// 1) 带 AT TIME ZONE ⇒ 零违规
{
  const src = `
    <select>
      SELECT to_char(o.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS c0
      GROUP BY to_char(o.created_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD')
    </select>`;
  assert(evaluateXml(src, 'a.xml', []).length === 0, '合规应零违规');
}

// 2) 裸 to_char ⇒ 红（R22-4 缺陷本体）
{
  const src = `<select>SELECT to_char(o.created_at, 'YYYY-MM-DD') AS c0</select>`;
  const v = evaluateXml(src, 'a.xml', []);
  assert(v.length === 1 && v[0].func === 'to_char', `裸 to_char 须红：${JSON.stringify(v)}`);
  assert(v[0].reason.includes('AT TIME ZONE'), `红因要点明缺时区转换：${v[0].reason}`);
}

// 3) 裸 date_trunc ⇒ 红
{
  const src = `<select>SELECT date_trunc('day', o.created_at) AS d0</select>`;
  const v = evaluateXml(src, 'a.xml', []);
  assert(v.length === 1 && v[0].func === 'date_trunc', `裸 date_trunc 须红：${JSON.stringify(v)}`);
}

// 4) AT TIME ZONE 但时区不对 ⇒ 红（值棘轮）
{
  const src = `<select>SELECT to_char(o.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')</select>`;
  const v = evaluateXml(src, 'a.xml', []);
  assert(v.length === 1 && v[0].reason.includes('Asia/Shanghai'), `改区须红：${JSON.stringify(v)}`);
}

// 5) 例外必须显式记账：裸用 + allowlist 带理由 ⇒ 放行；不带理由条目无来源区分（条目即理由）
{
  const src = `<select>SELECT to_char(o.created_at, 'YYYY-MM-DD')</select>`;
  assert(
    evaluateXml(src, 'a.xml', [{ file: 'a.xml', why: '参数为 date 列，无时区语义' }]).length === 0,
    '登记例外应放行'
  );
}

// 6) 行号定位准确
{
  const src = `<select>
    SELECT 1
    SELECT to_char(x, 'Y')</select>`;
  const v = evaluateXml(src, 'a.xml', []);
  assert(v[0].line === 3, `行号应定位到违规行：实际 ${v[0].line}`);
}

console.log(`${TAG} OK：${checks} 项断言全过`);
