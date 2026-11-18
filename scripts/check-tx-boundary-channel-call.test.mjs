#!/usr/bin/env node
/**
 * S8③ 配套夹具：check-tx-boundary-channel-call 的判据必须与负向用例同批。
 *
 * 这里用 sandbox 里的合成 Java 源码验证：
 *  ① 基线齐 ⇒ 绿；② 新增「事务内渠道调用」⇒ R1 红；
 *  ③ 修完方法但没摘基线 ⇒ R2 红；④ 扫描面塌陷 ⇒ R3 红。
 * 同时验证注释/字符串遮蔽、字段类型识别、能力探测豁免这些解析细节。
 */
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(join(dirname(fileURLToPath(import.meta.url)), '..'));
const SANDBOX = join(ROOT, '.tmp', 'tx-gate-fixture');
const SRC = join(SANDBOX, 'services', 'trade-service', 'src', 'main', 'java');
const BASELINE = join(SANDBOX, 'scripts', 'tx-boundary-channel-call-baseline.json');
mkdirSync(SRC, { recursive: true });
mkdirSync(join(SANDBOX, 'scripts'), { recursive: true });

function writeJava(name, methods, withChannel = true) {
  const body = methods
    .map(
      (m) => `
    @Transactional
    public void ${m}() {
        ${withChannel ? 'weChatPayClient.createServiceOrder("x");' : 'int unused = 1;'}
    }`
    )
    .join('\n');
  writeFileSync(
    join(SRC, name),
    `package com.aicabinet.trade.service;\npublic class ${name.replace('.java', '')} {\n` +
      `  private final com.x.WeChatPayClient weChatPayClient;\n${body}\n}\n`
  );
}

const KEYS = Array.from(
  { length: 6 },
  (_, i) => `services/trade-service/src/main/java/Fixture${i}.java#run`
);
KEYS.forEach((_, i) => writeJava(`Fixture${i}.java`, ['run']));
// 上一轮 R1 注入可能留下带违规的 Intruder.java；先覆盖成干净版本，避免夹具依赖运行顺序。
writeJava('Intruder.java', ['quiet'], false);

// 100+ 个 @Transactional 方法：每个类补 20 个无渠道调用的空方法，满足扫描面下限。
writeJava(
  'FixtureLarge.java',
  Array.from({ length: 100 }, (_, i) => `quiet${i}`),
  false
);

function writeBaseline(keys) {
  writeFileSync(
    BASELINE,
    JSON.stringify({
      entries: keys.map((key) => ({ key, reason: 'fixture' }))
    })
  );
}

process.env.GATE_ROOT = SANDBOX;
const gate = await import(
  pathToFileURL(join(ROOT, 'scripts', 'check-tx-boundary-channel-call.mjs')).href
);

// ── 解析层 ──
const masked = gate.maskComments('// a()\nString s = "b()";\nvoid c() { real(); }');
assert.ok(masked.split('\n')[0].trim() === '', '行注释未遮蔽');
assert.ok(!masked.includes('b()'), '字符串未遮蔽');
assert.ok(masked.includes('real()'), '真实代码被遮蔽');
assert.ok(
  gate.clientFieldNames('private final com.x.WeChatPayClient wx;').includes('wx'),
  '全限定字段名未识别'
);
assert.equal(
  gate.channelCalls('wx.isConfigured(); wx.fetch();', ['wx']).length,
  1,
  '能力探测未豁免'
);
assert.deepEqual(gate.selfCalls('self.a(); this.b();'), ['a', 'b'], 'self 调用解析错误');
assert.ok(gate.methodBody('void a(){ if(x){} }', 7).includes('if(x)'), '大括号配对错误');

// ── R1：基线齐 ⇒ 绿 ──
writeBaseline(KEYS);
let r = gate.evaluate();
assert.equal(r.txMethods > 100, true, `事务方法数不足：${r.txMethods}`);
assert.equal(r.channelMethods, 6, `命中数异常：${r.channelMethods}`);
assert.deepEqual(gate.checkBaseline(r.offenders), [], '基线齐时不应报错');

// ── R1：新增事务内渠道调用（无基线） ⇒ 红 ──
writeJava('Intruder.java', ['run']);
r = gate.evaluate();
const r1 = gate.checkBaseline(r.offenders);
assert.ok(
  r1.some((e) => e.includes('Intruder.java#run') && e.includes('R1')),
  `新增违规未判红：${r1.join(' | ')}`
);

// ── R2：已修方法但基线没摘 ⇒ 红 ──
writeBaseline([...KEYS, 'services/trade-service/src/main/java/Gone.java#run']);
r = gate.evaluate();
const r2 = gate.checkBaseline(r.offenders);
assert.ok(
  r2.some((e) => e.includes('Gone.java#run') && e.includes('不再命中')),
  `陈旧基线未判红：${r2.join(' | ')}`
);

// ── R3：扫描面塌陷 ⇒ 红 ──
const r3 = gate.scanSanity(10, 1);
assert.equal(r3.length, 2, '扫描面塌陷未判红');

console.log('[check-tx-boundary-channel-call.test] OK：13 项断言全过（含 R1/R2/R3 负向）');
