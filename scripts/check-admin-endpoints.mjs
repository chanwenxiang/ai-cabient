/**
 * A-P2-005 门禁：业务层禁止散落 `/api/` 端点字面量，一律走 AdminEndpoints。
 *
 * 审计 P2-14 硬化：原实现只对「允许清单做 includes 反查」——清单外的新裸路径静默放行
 * （已实际漏网：SkuVisionEnrollView 的 recognition-preview、brand.ts 的 ops-branding），
 * 且扫描根不含 components/layouts。现改为**拒绝式**：扫描根内任何 `/api/` 字面量即报错；
 * 豁免仅限 api/ 收口层自身与 *.test.ts 对 AdminEndpoints 产物值的断言。
 *
 * 用法：node scripts/check-admin-endpoints.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const srcRoot = path.resolve('clients/admin-vue/src');
const endpointsFile = path.join(srcRoot, 'api', 'endpoints.ts');
const scanRoots = [
  path.join(srcRoot, 'views'),
  path.join(srcRoot, 'composables'),
  // P2：stores 曾漏扫（dict-runtime 裸路径漏网即此处盲区）
  path.join(srcRoot, 'stores'),
  // 审计 P2-14：components（GlobalSearch/CrudTable/AddressPicker 等实际发请求）与 layouts 曾漏扫
  path.join(srcRoot, 'components'),
  path.join(srcRoot, 'layouts')
];

// 试点字面量清单仍保留：用于把命中归类提示（清单内=直接违规；清单外=新路径同样违规）
const endpointsText = fs.readFileSync(endpointsFile, 'utf8');
const listMatch = endpointsText.match(
  /ADMIN_ENDPOINT_PILOT_LITERALS\s*=\s*\[([\s\S]*?)\]\s*as\s*const/
);
const pilotLiterals = listMatch ? [...listMatch[1].matchAll(/'([^']+)'/g)].map((m) => m[1]) : [];

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, out);
      continue;
    }
    if (!/\.(vue|ts|tsx|js)$/.test(entry.name)) continue;
    out.push(full);
  }
  return out;
}

const offenders = [];
for (const file of scanRoots.flatMap((r) => walk(r))) {
  const rel = path.relative(process.cwd(), file).replace(/\\/g, '/');
  // 豁免：对 AdminEndpoints 产物值做断言的测试文件（合理用法：钉住端点形状）
  if (/\.test\.tsx?$/.test(rel)) continue;
  const body = fs.readFileSync(file, 'utf8');
  const lines = body.split(/\n/);
  // 匹配字符串字面量里的 /api/ 路径（单/双/反引号）；模板串里 ${} 拼接同样算散落
  const litRe = /(['"`])((?:\/api\/)[^'"`\s]*)\1/g;
  lines.forEach((line, idx) => {
    let m;
    while ((m = litRe.exec(line)) !== null) {
      const literal = m[2];
      // trend 查询串前缀也拦（历史规则保留）
      const inPilot = pilotLiterals.some((p) => literal.includes(p) || p.includes(literal));
      const tag = inPilot ? 'pilot literal' : 'non-pilot path（新路径也必须收口 AdminEndpoints）';
      offenders.push(`${rel}:${idx + 1} bare ${literal} — ${tag}; use AdminEndpoints`);
    }
  });
  // trend 查询串裸前缀（不带引号形态）
  if (/\/api\/v2\/ops\/admin\/trend(\/|\?)/.test(body)) {
    const line = lines.findIndex((l) => /\/api\/v2\/ops\/admin\/trend/.test(l)) + 1;
    offenders.push(`${rel}:${line} bare trend path — use AdminEndpoints.trend*`);
  }
}

if (offenders.length) {
  console.error(
    'A-P2-005 admin endpoints check failed（拒绝式扫描，审计 P2-14）:\n' + offenders.join('\n')
  );
  console.error(
    '修复：路径收口到 clients/admin-vue/src/api/endpoints.ts（AdminEndpoints），业务层仅引用其导出。'
  );
  process.exit(1);
}
console.log(
  `check-admin-endpoints: ok（拒绝式：5 个扫描根 views/composables/stores/components/layouts，${pilotLiterals.length} 个试点字面量已收口）`
);
