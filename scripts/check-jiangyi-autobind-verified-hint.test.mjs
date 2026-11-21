#!/usr/bin/env node
/**
 * check-jiangyi-autobind-verified-hint.mjs 的负向用例（真树绿 + 逐分支注入红 + 还原 md5 一致）。
 *   node scripts/check-jiangyi-autobind-verified-hint.test.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import {
  serviceProblems,
  noteProblems,
  viewProblems
} from './check-jiangyi-autobind-verified-hint.mjs';

const TAG = '[check-autobind-hint.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const md5 = (s) => createHash('md5').update(s).digest('hex');

const SERVICE_REAL = readFileSync(
  'services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiMappingAutoBindService.java',
  'utf8'
);
const VIEW_REAL = readFileSync('clients/admin-vue/src/views/skus/SkuJiangyiLinkDialog.vue', 'utf8');

// 0) 真树全绿 + 注释不误判
assert(serviceProblems(SERVICE_REAL).length === 0, '真树 service 应绿');
assert(noteProblems(SERVICE_REAL).length === 0, '真树 note 应绿');
assert(viewProblems(VIEW_REAL).length === 0, '真树 view 应绿');
assert(
  serviceProblems('// mappingReviewService.isVerified(deviceId)\nclass X {}').length > 0,
  '注释里的 isVerified 不算'
);

// 1) 删注入 → 红
{
  const broken = SERVICE_REAL.replace(
    /private final JiangyiMappingReviewService mappingReviewService;/,
    '// removed'
  );
  assert(
    serviceProblems(broken).some((p) => p.includes('未注入')),
    '删注入须红'
  );
}

// 2) 删 isVerified 调用 → 红
{
  const broken = SERVICE_REAL.replace(
    /mappingVerified = mappingReviewService\.isVerified\(deviceId\);/,
    'mappingVerified = true;'
  );
  assert(
    serviceProblems(broken).some((p) => p.includes('未调用 isVerified')),
    '删 isVerified 调用须红'
  );
}

// 3) 删 record 字段 → 红
{
  const broken = SERVICE_REAL.replace(/boolean mappingVerified\) \{\}/, 'String unused) {}');
  assert(noteProblems(broken).length > 0, '删 record 字段须红');
}

// 4) 前端逐柜标记退化 → 红（三种注入）
{
  const cases = [
    [VIEW_REAL.replace(/mappingVerified\?\: boolean;/, 'unused?: string;'), '字段'],
    [VIEW_REAL.replace(/note\.mappingVerified === false/, 'note.status === "SKIPPED"'), '判据'],
    [VIEW_REAL.replace(/待复核/g, 'REVIEW_ME'), '文案']
  ];
  for (const [broken, name] of cases) {
    assert(viewProblems(broken).length > 0, `前端${name}注入须红`);
  }
}

// 5) 上架提示删除 → 红
{
  const broken = VIEW_REAL.replace(/先上架[，,]\s*再挂接/, '妥善处理后挂接');
  assert(
    viewProblems(broken).some((p) => p.includes('上架')),
    '删上架提示须红'
  );
}

// 6) 还原字节一致（真树未被本测试改动）
assert(
  md5(
    readFileSync(
      'services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiMappingAutoBindService.java',
      'utf8'
    )
  ) === md5(SERVICE_REAL),
  'service 真树字节被污染'
);
assert(
  md5(readFileSync('clients/admin-vue/src/views/skus/SkuJiangyiLinkDialog.vue', 'utf8')) ===
    md5(VIEW_REAL),
  'view 真树字节被污染'
);

console.log(`${TAG} OK：${checks} 项断言全过（真树绿 / 八种注入红 / 字节零污染）`);
