/**
 * 审计 P1-7 门禁：服务端 CSV 导出公式注入中和必须统一走 CsvCells。
 * 规则：`static String csv(` 助手只允许出现在 CsvCells 自身，或函数体内委托
 * `CsvCells.escape(...)`；任何手写转义副本（含局部公式中和）即失败。
 * 用法：node scripts/check-csv-escape.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve('services/trade-service/src/main/java');
const METHOD_RE =
  /(public|private|protected|static)[\w\s<>]*\bcsv\s*\(\s*(?:final\s+)?String\s+\w+\s*\)\s*\{/;

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, out);
      continue;
    }
    if (!/\.(java|kt)$/.test(entry.name)) continue;
    out.push(full);
  }
  return out;
}

const offenders = [];
for (const file of walk(root)) {
  const rel = path.relative(process.cwd(), file).replace(/\\/g, '/');
  const isAuthoritative = rel.endsWith('support/CsvCells.java');
  const body = fs.readFileSync(file, 'utf8');
  const lines = body.split(/\n/);
  lines.forEach((line, idx) => {
    const m = line.match(METHOD_RE);
    if (!m) return;
    if (isAuthoritative) return;
    // 取函数体（浅层大括号配平，助手都很短）
    let depth = 0;
    let end = idx;
    for (let i = idx; i < lines.length; i++) {
      depth += (lines[i].match(/\{/g) || []).length - (lines[i].match(/\}/g) || []).length;
      if (depth <= 0 && i > idx) {
        end = i;
        break;
      }
    }
    const fnBody = lines.slice(idx, end + 1).join('\n');
    if (!fnBody.includes('CsvCells.escape')) {
      offenders.push(`${rel}:${idx + 1} csv() 未委托 CsvCells.escape（禁止手写 CSV 转义副本）`);
    }
  });
}

if (offenders.length) {
  console.error('check-csv-escape: 发现手写 CSV 转义副本：');
  for (const o of offenders) console.error('  ' + o);
  console.error(
    '统一改为 com.aicabinet.trade.support.CsvCells.escape(...)（见审计 P1-7 / docs/CODE_AUDIT_REPORT_2026-10-05.md）'
  );
  process.exit(1);
}
console.log('✓ check:csv-escape — 全部 csv() 助手均委托 CsvCells');
