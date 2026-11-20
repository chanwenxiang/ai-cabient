#!/usr/bin/env node
/**
 * check-jiangyi-txt-url-default 的负向夹具：真树绿 + 五种注入红 + 真树字节零残留。
 */
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import {
  CONTROLLER_FILE,
  DOMAIN_FILE,
  DTO_FILE,
  SYNC_FILE,
  VIEW_FILE,
  controllerProblems,
  domainProblems,
  dtoProblems,
  migrationProblems,
  syncProblems,
  viewProblems
} from './check-jiangyi-txt-url-default.mjs';

const TAG = '[check-jiangyi-txt-url-default.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}
const md5 = (p) => createHash('md5').update(readFileSync(p)).digest('hex');

const files = [SYNC_FILE, DOMAIN_FILE, DTO_FILE, CONTROLLER_FILE, VIEW_FILE];
const before = files.map(md5);
const [sync, domain, dto, controller, view] = files.map((p) => readFileSync(p, 'utf8'));

for (const [name, src] of Object.entries({ sync, domain, dto, controller, view })) {
  assert(src.length > 500, `${name} 源文件读取失败（锚点漂移）`);
}
assert(syncProblems(sync).length === 0, '真实 sync 必须绿');
assert(domainProblems(domain).length === 0, '真实 domain 必须绿');
assert(dtoProblems(dto).length === 0, '真实 dto 必须绿');
assert(controllerProblems(controller).length === 0, '真实 controller 必须绿');
assert(viewProblems(view).length === 0, '真实 view 必须绿');
assert(
  migrationProblems(
    readdirSync(
      new URL('../services/trade-service/src/main/resources/db/migration', import.meta.url)
    )
  ).length === 0,
  '真实迁移必须绿'
);

// ① 不落库（回到「只打日志」的旧状态）
const noPersist = sync.replace(
  'jiangyiDeviceMapper.markCurrentTxtUrl(',
  'jiangyiDeviceMapper.markNothing('
);
assert(noPersist !== sync, '注入锚点 markCurrentTxtUrl 找不到');
assert(
  syncProblems(noPersist).some((p) => p.includes('未把设备自报的 txtUrl 落库')),
  '不落库必须红'
);

// ② 落库相对路径（拼绝对地址被去掉 ⇒ 后台拿到的是 model/xxx.txt）
const relative = sync.replace('absoluteTxtUrl(modelTxtBaseUrl, reported)', 'reported');
assert(relative !== sync, '注入锚点 absoluteTxtUrl 找不到');
assert(
  syncProblems(relative).some((p) => p.includes('未经 absoluteTxtUrl')),
  '落库相对路径必须红'
);

// ③ 实体字段丢失
const noField = domain.replace(
  'private String currentTxtUrl;',
  'private String currentTxtUrlGone;'
);
assert(noField !== domain, '注入锚点实体字段找不到');
assert(domainProblems(noField).length === 1, '实体缺字段必须红');

// ④ DTO / 映射丢值
assert(
  dtoProblems(dto.replace('String currentTxtUrl,', 'String currentTxtUrlGone,')).length === 1,
  'DTO 缺字段必须红'
);
assert(
  controllerProblems(controller.replace('getCurrentTxtUrl()', 'getCurrentTxtUrlGone()')).length ===
    1,
  'toDto 未传值必须红'
);

// ⑤ 前端不兜底 / 无条件覆盖
assert(
  viewProblems(
    view.replace(
      'reviewForm.value.textUrl = bound.currentTxtUrl;',
      "reviewForm.value.textUrl = '';"
    )
  ).some((p) => p.includes('未用 bound.currentTxtUrl 兜底')),
  '前台不兜底必须红'
);
assert(
  viewProblems(
    view.replace(
      'if (bound?.currentTxtUrl && !reviewForm.value.textUrl.trim())',
      'if (bound?.currentTxtUrl)'
    )
  ).some((p) => p.includes('抹掉运营手填')),
  '无条件覆盖必须红'
);

// ⑥ 迁移缺失
assert(migrationProblems([]).length === 1, '迁移缺失必须红');

assert(
  files.every((p, i) => md5(p) === before[i]),
  '真树被写入污染'
);
console.log(`${TAG} OK：${checks} 项断言全过（真树绿 / 六种注入红 / md5 零污染）`);
