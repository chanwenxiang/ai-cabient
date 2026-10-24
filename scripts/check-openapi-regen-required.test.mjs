/**
 * 防绕过自测：`check-openapi-regen-required` 必须**会红**（形状改动没带 openapi.ts）
 * 且**不假红**（只改方法体/文案）。Run: node scripts/check-openapi-regen-required.test.mjs
 *
 * 为什么必须有这个文件（2026-10-24）：本门禁今天给出过一次**假绿**——
 * `MerchantSelfReplenishRequest`（Java record）加了幂等键 `clientRequestNo`，它打印
 * 「变更集内无 API 形状改动」，而 CI 的 openapi job 起 jar 抽真 `/v3/api-docs` 判红。
 * 根因是判据只认 `private|protected` 字段声明，而 record 分量两者都没有。
 * 判据退化成「看不见」时门禁**不会报错，只会安静地绿**，所以必须留一条专门钉住
 * record 分量的红路径；否则下一次同样漏网，代价又是「推送后连吃一轮 CI 红」。
 *
 * 🔴 沙箱纪律（lessons #339/#340）：红路径需要**制造形状改动的 .java 文件**，一律在
 * `.tmp` 下另建临时 git 仓库跑（diff / ls-files / ls-files --others 三条取数腿都真跑），
 * 绝不往真实树写探针；收尾断言门禁脚本自身逐字节未变、沙箱已删。
 */
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runAsync } from './lib/async-spawn.mjs';
import { pickShapeHit, recordComponentNames } from './check-openapi-regen-required.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-openapi-regen-required.test]';
const GATE = 'scripts/check-openapi-regen-required.mjs';
const CTRL = 'services/trade-service/src/main/java/com/aicabinet/trade/api/DemoController.java';
const DTO = 'services/trade-service/src/main/java/com/aicabinet/trade/dto/DemoRequest.java';
const GEN = 'packages/shared-types/src/generated/openapi.ts';
const SANDBOX = join(ROOT, '.tmp', `gate-selftest-openapi-${process.pid}`);

let failures = 0;
function check(name, cond, extra = '') {
  if (cond) {
    console.log(`  ok   ${name}`);
  } else {
    failures++;
    console.error(`  RED  ${name}${extra ? ` —— ${extra}` : ''}`);
  }
}

// ── 纯函数：record 头解析与形状命中（不碰 git） ─────────────────────────
const RECORD_SRC = `package com.aicabinet.trade.dto;

/** 演示。 */
public record DemoRequest(
        String deviceId,
        java.util.List<String> lines,
        String clientRequestNo
) {
    public String label() {
        return deviceId;
    }
}
`;
{
  const names = [...recordComponentNames(RECORD_SRC)];
  check(
    'recordComponentNames 按分量名取全（泛型里的逗号不切错）',
    names.join(',') === 'deviceId,lines,clientRequestNo',
    names.join(',')
  );
  check('非 record 文件解析不出分量（不猜）', recordComponentNames('class A { }').size === 0);
  check('空内容不炸', recordComponentNames('').size === 0);
  check(
    '括号不配对的 record 头 ⇒ 空集而不是半截结论',
    recordComponentNames('record Broken(String a, String b').size === 0
  );
}
{
  // 今天漏网的那条：分量行既无修饰符也无结尾符
  const hit = pickShapeHit(DTO, ['        String clientRequestNo'], RECORD_SRC);
  check(
    'record 分量新增 ⇒ 命中（旧实现这里是 undefined）',
    hit === '        String clientRequestNo'
  );
  check(
    '单行 record 头整行增删 ⇒ 命中',
    !!pickShapeHit(DTO, ['public record Other(String a, int b) {}'], RECORD_SRC)
  );
  check(
    '形状像分量但**不是**本 record 的分量 ⇒ 不判（防反向假红）',
    pickShapeHit(DTO, ['        String someOtherName'], RECORD_SRC) === undefined
  );
  check(
    '普通类文件里 `Type name` 形状 ⇒ 不判（record 判据不越界）',
    pickShapeHit(DTO, ['        String someOtherName'], 'class A { void f() { g } }') === undefined
  );
  check(
    'record 文件里的 javadoc 行不判（真实 diff 里就混着这种行）',
    pickShapeHit(DTO, [' * clientRequestNo（可选）：客户端幂等键。'], RECORD_SRC) === undefined
  );
  check(
    '注解换行写也能命中 Controller 形状',
    !!pickShapeHit(CTRL, ['            @RequestParam(name = "q") String q'], 'class C {}')
  );
  check(
    '普通 DTO 的 private 字段声明仍然命中（旧判据不回退）',
    !!pickShapeHit(DTO, ['    private String note;'], 'class A { private String note; }')
  );
}

// ── 端到端：临时 git 仓库，真实跑三条取数腿 ─────────────────────────────
const CTRL_BASE = `package com.aicabinet.trade.api;

public class DemoController {
    @GetMapping("/demo")
    public String demo() {
        return "ok";
    }
}
`;
const CTRL_SHAPE = `package com.aicabinet.trade.api;

public class DemoController {
    @GetMapping("/demo")
    public String demo(
            @RequestParam(name = "q", required = false) String q) {
        return "ok";
    }
}
`;
const CTRL_BODY_ONLY = `package com.aicabinet.trade.api;

public class DemoController {
    @GetMapping("/demo")
    public String demo() {
        // 只改方法体文案，不改形状
        return "ok!";
    }
}
`;
const DTO_BASE = `package com.aicabinet.trade.dto;

public record DemoRequest(
        String deviceId
) {}
`;
const DTO_SHAPE = `package com.aicabinet.trade.dto;

public record DemoRequest(
        String deviceId,
        String clientRequestNo
) {}
`;

function put(rel, body) {
  const abs = join(SANDBOX, rel);
  mkdirSync(dirname(abs), { recursive: true });
  writeFileSync(abs, body, 'utf8');
}

async function sh(args) {
  const r = await runAsync('git', args, { cwd: SANDBOX });
  if (r.status !== 0) throw new Error(`git ${args.join(' ')} 失败：${r.stderr || r.stdout}`);
  return r.stdout;
}

async function freshRepo(files = {}) {
  rmSync(SANDBOX, { recursive: true, force: true });
  mkdirSync(join(SANDBOX, 'scripts', 'lib'), { recursive: true });
  // 门禁按自身文件位置定位 ROOT ⇒ 拷脚本 + 它依赖的 lib，判据一行不改
  cpSync(join(ROOT, GATE), join(SANDBOX, GATE));
  cpSync(join(ROOT, 'scripts/lib/async-spawn.mjs'), join(SANDBOX, 'scripts/lib/async-spawn.mjs'));
  await sh(['init', '-q', '-b', 'main']); // 分支名避开门禁自己的候选 dev/main
  await sh(['config', 'user.email', 'selftest@example.invalid']);
  await sh(['config', 'user.name', 'selftest']);
  put(CTRL, CTRL_BASE);
  put(DTO, DTO_BASE);
  put(GEN, 'export type Demo = { deviceId: string };\n');
  await sh(['add', '-A']);
  await sh(['commit', '-q', '-m', 'baseline']);
  for (const [rel, body] of Object.entries(files)) put(rel, body);
}

async function commit() {
  await sh(['add', '-A']);
  await sh(['commit', '-q', '-m', 'change']);
}

async function runGate(env) {
  return runAsync(process.execPath, [GATE], { cwd: SANDBOX, env });
}

const gateShaBefore = createHash('md5')
  .update(readFileSync(join(ROOT, GATE)))
  .digest('hex');

try {
  // ① 已提交的 Controller 形状改动，没带 openapi.ts ⇒ 必须红
  await freshRepo({ [CTRL]: CTRL_SHAPE });
  await commit();
  let r = await runGate({ OPENAPI_BASE_REF: 'HEAD~1' });
  check('红：@RequestParam 新增且未带 openapi.ts ⇒ rc=1', r.status === 1, `实际 rc=${r.status}`);
  check(
    '红：点名触发文件与命中行',
    /DemoController\.java/.test(r.stderr) && /@RequestParam/.test(r.stderr),
    (r.stderr || r.stdout).slice(0, 300)
  );
  check('红：给出正解（gen-openapi-types）', /gen-openapi-types/.test(r.stderr));

  // ② 同样改动 + 变更集里带 openapi.ts ⇒ 绿，且仍报告命中数（放行≠沉默）
  await freshRepo({ [CTRL]: CTRL_SHAPE, [GEN]: 'export type Demo = { q?: string };\n' });
  await commit();
  r = await runGate({ OPENAPI_BASE_REF: 'HEAD~1' });
  check('绿：形状改动已同带 openapi.ts ⇒ rc=0', r.status === 0, (r.stderr || '').slice(0, 300));
  check('留痕：绿也不隐瞒「检测到 1 处」', /检测到 1 处/.test(r.stdout), r.stdout.slice(0, 200));

  // ③ 未提交（工作树）的形状改动 ⇒ 也要红（推送前预检看的就是这个状态）
  await freshRepo({ [CTRL]: CTRL_SHAPE });
  r = await runGate({ OPENAPI_BASE_REF: 'HEAD' });
  check(
    '红：工作树未提交的形状改动也算（base 工作树这条腿真接线）',
    r.status === 1,
    `rc=${r.status}`
  );

  // ④ 未跟踪的新 record DTO ⇒ 整份内容算新增，必须红
  const NEW_DTO = 'services/trade-service/src/main/java/com/aicabinet/trade/dto/DemoFilter.java';
  await freshRepo({
    [NEW_DTO]:
      'package com.aicabinet.trade.dto;\n\npublic record DemoFilter(\n        String clientRequestNo\n) {}\n'
  });
  r = await runGate({ OPENAPI_BASE_REF: 'HEAD' });
  check('红：未跟踪的新 record DTO（ls-files --others 这条腿）', r.status === 1, `rc=${r.status}`);

  // ⑤ 🔴 今天漏网的那一类：record 分量新增，没带 openapi.ts ⇒ 必须红
  await freshRepo({ [DTO]: DTO_SHAPE });
  await commit();
  r = await runGate({ OPENAPI_BASE_REF: 'HEAD~1' });
  check('红：record 分量新增（回归 2026-10-24 假阴性）⇒ rc=1', r.status === 1, `rc=${r.status}`);
  check(
    '红：record 那条也要点名文件并打出一条分量行',
    /DemoRequest\.java/.test(r.stderr) && /String (deviceId|clientRequestNo)/.test(r.stderr),
    (r.stderr || r.stdout).slice(0, 300)
  );

  // ⑥ 只改方法体 ⇒ 绿（避免「改一行文案也要求 regen」的假红）
  await freshRepo({ [CTRL]: CTRL_BODY_ONLY });
  await commit();
  r = await runGate({ OPENAPI_BASE_REF: 'HEAD~1' });
  check('绿：只改方法体不要求 regen', r.status === 0, (r.stderr || '').slice(0, 300));
  check('绿：无形状改动时明确说「无」', /无 API 形状改动/.test(r.stdout), r.stdout.slice(0, 200));

  // ⑦ 基线 ref 取不到 ⇒ fail-closed，绝不等于「没有形状改动」
  await freshRepo({ [CTRL]: CTRL_SHAPE });
  r = await runGate({ OPENAPI_BASE_REF: 'no-such-ref-xyz' });
  check('红：基线 ref 不存在一律 fail-closed', r.status === 1 && /找不到基线/.test(r.stderr));

  // ⑧ 整份 record DTO 被删 ⇒ 工作树已无文件，判据必须回落到 HEAD 里的全文
  await freshRepo();
  rmSync(join(SANDBOX, DTO));
  r = await runGate({ OPENAPI_BASE_REF: 'HEAD' });
  check(
    '红：删掉整个 record DTO 也算形状改动（javaSource 回落 HEAD 那条腿）',
    r.status === 1 && /DemoRequest\.java/.test(r.stderr),
    `rc=${r.status} ${(r.stderr || r.stdout).slice(0, 200)}`
  );
} catch (err) {
  failures++;
  console.error(`  RED  自测脚手架抛异常：${err && err.message}`);
} finally {
  let removed = false;
  for (let i = 0; i < 20 && !removed; i++) {
    try {
      rmSync(SANDBOX, { recursive: true, force: true });
      removed = !existsSync(SANDBOX);
    } catch {
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 150);
    }
  }
  if (!removed) {
    failures++;
    console.error(`  RED  沙箱未清干净，请手工删除 ${SANDBOX}`);
  }
}

// 真实树必须一个字节都没被动过（沙箱里躺着一份门禁脚本副本，不影响真实树）
const gateShaAfter = createHash('md5')
  .update(readFileSync(join(ROOT, GATE)))
  .digest('hex');
check('真实门禁脚本零改写（注入只发生在 .tmp 沙箱）', gateShaBefore === gateShaAfter);

if (failures) {
  console.error(`${TAG} RED：${failures} 条断言失败`);
  process.exit(1);
}
console.log(`${TAG} OK：红/绿/回归/工作树边界/fail-closed 全部按预期`);
