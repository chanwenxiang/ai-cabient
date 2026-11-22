/**
 * openapi-surface.mjs 负向自测（MA-392）。
 * 断言三档判定与关键反例；preflight 每次推送前先跑本文件再消费判定结果。
 */
import assert from 'node:assert/strict';
import { analyzeOpenApiSurfaceRisk } from './openapi-surface.mjs';

const TAG = 'openapi-surface.test';
function ok(cond, msg) {
  if (!cond) throw new Error(`${TAG} 断言失败：${msg}`);
}

const ROOT = '/repo';
function fakeRootRead(pathToFile) {
  const store = {
    [ROOT + '/services/trade-service/src/main/java/com/aicabinet/trade/service/AutoBindSvc.java']:
      'public record DeviceNote(String deviceId, Integer classId) {}\n',
    [ROOT + '/services/trade-service/src/main/java/com/aicabinet/trade/dto/ThingDto.java']:
      'public class ThingDto { Long id; }\n',
    [ROOT + '/services/trade-service/src/main/java/com/aicabinet/trade/service/PlainSvc.java']:
      'public class PlainSvc { void run() {} }\n',
    [ROOT + '/services/trade-service/src/main/java/com/aicabinet/trade/api/ThingController.java']:
      'public class ThingController {}\n'
  };
  return (p) => {
    // Windows 的 resolve('/repo', …) 会补当前盘符（D:\repo\…）⇒ 归一时剥掉盘符再统一斜杠
    const canon = (x) =>
      x
        .replace(/^[A-Za-z]:/, '')
        .split('\\')
        .join('/');
    const hit = store[canon(p)];
    if (hit === undefined) throw new Error('ENOENT: ' + p);
    return hit;
  };
}

// 1) service 内嵌 record 改动 + generated 未动 ⇒ fire（MA-384/CI #1479 事故形态）
const r1 = analyzeOpenApiSurfaceRisk(
  ['services/trade-service/src/main/java/com/aicabinet/trade/service/AutoBindSvc.java'],
  { root: ROOT, readFile: fakeRootRead() }
);
ok(r1.level === 'fire' && r1.files.length === 1, `case1 应 fire，实得 ${JSON.stringify(r1)}`);

// 2) /dto/ 路径改动 + generated 未动 ⇒ fire
const r2 = analyzeOpenApiSurfaceRisk(
  ['services/trade-service/src/main/java/com/aicabinet/trade/dto/ThingDto.java'],
  { root: ROOT, readFile: fakeRootRead() }
);
ok(r2.level === 'fire', `case2 应 fire，实得 ${r2.level}`);

// 3) 同批动了 generated ⇒ 不拦（重生成即免检）
const r3 = analyzeOpenApiSurfaceRisk(
  [
    'services/trade-service/src/main/java/com/aicabinet/trade/service/AutoBindSvc.java',
    'packages/shared-types/src/generated/openapi.ts'
  ],
  { root: ROOT, readFile: fakeRootRead() }
);
ok(r3.level === 'none', `case3 应 none，实得 ${r3.level}`);

// 4) 只改控制器（结构档原判据已覆盖）⇒ 本判定不 fire
const r4 = analyzeOpenApiSurfaceRisk(
  ['services/trade-service/src/main/java/com/aicabinet/trade/api/ThingController.java'],
  { root: ROOT, readFile: fakeRootRead() }
);
ok(r4.level === 'none', `case4 应 none，实得 ${r4.level}`);

// 5) 普通 service（无 record、非 dto）⇒ warn 档提醒
const r5 = analyzeOpenApiSurfaceRisk(
  ['services/trade-service/src/main/java/com/aicabinet/trade/service/PlainSvc.java'],
  { root: ROOT, readFile: fakeRootRead() }
);
ok(r5.level === 'warn' && r5.files.length === 1, `case5 应 warn，实得 ${r5.level}`);

// 6) src/test 与非 trade 路径 ⇒ none
const r6 = analyzeOpenApiSurfaceRisk(
  [
    'services/trade-service/src/test/java/com/aicabinet/trade/service/AutoBindSvcTest.java',
    'clients/admin-vue/src/views/Foo.vue'
  ],
  { root: ROOT, readFile: fakeRootRead() }
);
ok(r6.level === 'none', `case6 应 none，实得 ${r6.level}`);

// 7) 文件已删除（readFile 抛错）⇒ 降 warn，不误拦删码批
const r7 = analyzeOpenApiSurfaceRisk(
  ['services/trade-service/src/main/java/com/aicabinet/trade/service/Gone.java'],
  { root: ROOT, readFile: fakeRootRead() }
);
ok(r7.level === 'warn', `case7 应 warn（删除文件不误拦），实得 ${r7.level}`);

console.log(`✓ ${TAG}: 7/7 断言通过（fire×2 / none×3 / warn×2，含 MA-384 事故形态复现）`);
