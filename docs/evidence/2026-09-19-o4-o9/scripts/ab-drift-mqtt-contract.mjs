#!/usr/bin/env node
/**
 * A/B 漂移验证：`check-edge-cloud-mqtt-contract` 是**真判据**还是摆设？
 *
 *   node docs/evidence/2026-09-19-o4-o9/scripts/ab-drift-mqtt-contract.mjs
 *
 * 纪律（本项目踩过的坑）：
 *   · **基线不绿即中止** —— 基线本身红的话，后面「注入后变红」毫无信息量；
 *   · 每步**改回原状后 sha256 必须逐字节一致**，且门禁必须回绿（否则是「改坏了一直红」）；
 *   · 每个用例都要能说出**它是怎么红的**（错误信息必须命中预期要点），
 *     只要「status!=0」就判过的话，任何语法错误都能冒充漂移检出。
 */
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..', '..', '..');

const F_KT = 'edge/android-app/app/src/main/java/com/aicabinet/edge/mqtt/MqttDeviceClient.kt';
const F_CONSTS =
  'services/common/common-core/src/main/java/com/aicabinet/common/constants/CabinetConstants.java';
const F_TOPICS =
  'services/common/common-core/src/main/java/com/aicabinet/common/mqtt/MqttTopics.java';
const F_PROTO = 'proto/cabinet.proto';

const abs = (rel) => join(root, rel);
const sha = (rel) => createHash('sha256').update(readFileSync(abs(rel))).digest('hex');

function runGate() {
  return spawnSync('node', [join(root, 'scripts', 'check-edge-cloud-mqtt-contract.mjs')], {
    cwd: root,
    encoding: 'utf8'
  });
}
const outOf = (r) => `${r.stdout || ''}${r.stderr || ''}`;

/** 每个用例：怎么改、期望红还是绿、以及「必须出现的错误要点」 */
const cases = [
  {
    label: 'D1 edge 上行 payload.type 改名（DOOR → DOORX）',
    file: F_KT,
    patch: (s) => s.replace('"type" to "DOOR",', '"type" to "DOORX",'),
    expectRed: true,
    must: /payload\.type="DOORX" 不在 MQTT_EVENT_TYPE_/
  },
  {
    label: 'D2 edge 硬编码 topic 改段（/evt → /events）',
    file: F_KT,
    patch: (s) => s.split('"cabinet/$deviceId/evt"').join('"cabinet/$deviceId/events"'),
    expectRed: true,
    must: /在 MqttTopics 里没有对应声明/
  },
  {
    label: 'D3 proto oneof 字段改名（set_target_temp → set_target）',
    file: F_PROTO,
    patch: (s) => s.replace('set_target_temp = 11;', 'set_target = 11;'),
    expectRed: true,
    must: /下行命令集合不一致/
  },
  {
    label: 'D4 Java 常量值改动（OPEN_DOOR → OPEN_DOOR_V2）',
    file: F_CONSTS,
    patch: (s) => s.replace('MQTT_CMD_OPEN_DOOR = "OPEN_DOOR"', 'MQTT_CMD_OPEN_DOOR = "OPEN_DOOR_V2"'),
    expectRed: true,
    must: /下行命令集合不一致/
  },
  {
    label: 'D5 proto 把 alert 声明**注释掉**（形态③：注释不该被当成声明）',
    file: F_PROTO,
    patch: (s) => s.replace('    AlertEvent alert = 13;', '    // AlertEvent alert = 13;'),
    expectRed: true,
    must: /上行事件集合不一致/
  },
  {
    label: 'D6 MqttTopics 新增一个**零调用**的死 topic 声明',
    file: F_TOPICS,
    patch: (s) =>
      s.replace(
        'public static final String ALL_EVENTS_SHARED = "$share/aicabinet/cabinet/+/evt";',
        'public static final String ALL_EVENTS_SHARED = "$share/aicabinet/cabinet/+/evt";\n' +
          '    public static final String ALL_TEMP = "cabinet/+/evt/temp";'
      ),
    expectRed: true,
    must: /是\*\*死声明\*\*|死声明/
  },
  {
    label: 'D7 topic 解析器失效模拟（edge 全改用字符串拼接 ⇒ 解析到 0 个 topic）',
    file: F_KT,
    // 必须把 `/cmd` 也一起换掉：只换 `/evt` 会剩 1 个 topic，解析器仍然「有输出」，
    // 就测不到「解析到 0」这条防空转断言（第一版就是这么写错的）。
    patch: (s) => s.split('"cabinet/$deviceId').join('"cabinet/" + deviceId + "'),
    expectRed: true,
    must: /得到 0 个硬编码 topic/
  },
  {
    label: 'D9 内部端点漂移（客户端 /sessions/door-event → /sessions/door-events）',
    file: 'services/device-service/src/main/java/com/aicabinet/device/client/TradeServiceClient.java',
    patch: (s) =>
      s.replace('.uri("/internal/v1/sessions/door-event")', '.uri("/internal/v1/sessions/door-events")'),
    expectRed: true,
    must: /没有\*\*对应映射|没有对应映射/
  },
  {
    label: 'D8 type 解析器失效模拟（`"type" to "` 键名换成常量 ⇒ 解析到 0 个 type）',
    file: F_KT,
    // 注意别拿「多加几个空格」当变异：门禁用 `\s+`，多空格照样匹配（第一版这么写，恒绿）。
    patch: (s) => s.split('"type" to "').join('TYPE_KEY to "'),
    expectRed: true,
    must: /得到 0 个上行 payload\.type/
  }
];

// ── 基线 ────────────────────────────────────────────────────────────────────
const touched = [...new Set(cases.map((c) => c.file))];
const baselineSha = new Map(touched.map((f) => [f, sha(f)]));
const baseline = runGate();
if (baseline.status !== 0) {
  console.error('[ab-drift] 基线不绿，A/B 无意义，中止：\n' + outOf(baseline));
  process.exit(1);
}
console.log('[ab-drift] 基线绿 ✓\n');

let pass = 0;
const failures = [];

for (const c of cases) {
  const original = readFileSync(abs(c.file), 'utf8');
  const mutated = c.patch(original);
  if (mutated === original) {
    failures.push(`${c.label}: patch 未产生任何改动 —— 变异没生效，A/B 是假的`);
    console.error(`  ✗ ${c.label}（变异未生效）`);
    continue;
  }
  let r;
  try {
    writeFileSync(abs(c.file), mutated, 'utf8');
    r = runGate();
  } finally {
    writeFileSync(abs(c.file), original, 'utf8');
  }
  const red = r.status !== 0;
  const txt = outOf(r);
  const okDirection = red === c.expectRed;
  const okReason = !c.expectRed || c.must.test(txt);

  // 还原校验：sha256 必须逐字节一致，且门禁必须回绿
  const restored = sha(c.file) === baselineSha.get(c.file);
  const after = runGate();
  const backGreen = after.status === 0;

  if (okDirection && okReason && restored && backGreen) {
    pass++;
    console.log(`  ✓ ${c.label} ⇒ ${red ? '红' : '绿'}，还原字节一致且回绿`);
  } else {
    const why = [
      !okDirection && `方向错（期望${c.expectRed ? '红' : '绿'}，实为${red ? '红' : '绿'}）`,
      !okReason && '红了但错误要点不匹配（可能只是别的原因红的）',
      !restored && `${c.file} 还原后 sha256 不一致`,
      !backGreen && '还原后门禁没回绿'
    ]
      .filter(Boolean)
      .join('；');
    failures.push(`${c.label}: ${why}\n${txt}`);
    console.error(`  ✗ ${c.label} ⇒ ${why}\n${txt}`);
  }
}

console.log(`\n[ab-drift] ${pass}/${cases.length} 用例符合预期`);
if (failures.length) {
  console.error(`[ab-drift] FAIL：${failures.length} 个用例不成立`);
  process.exit(1);
}
console.log('[ab-drift] OK：门禁对三类源文件的漂移都能红，且还原后逐字节一致');
