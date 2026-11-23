#!/usr/bin/env node
/**
 * `check-jiangyi-command-capability.mjs` 的夹具（S8④ 自证）。
 *
 * 真实树必须绿；六种退化各注入一次必须红；全程只操作内存字符串，不写盘（零污染）。
 *
 *   node scripts/check-jiangyi-command-capability.test.mjs
 */
import { readFileSync } from 'node:fs';
import {
  CAPABILITY_FILE,
  CLIENT_FILE,
  CONSTANTS_FILE,
  evaluate
} from './check-jiangyi-command-capability.mjs';

const TAG = '[check-jiangyi-command-capability.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const constantsSrc = readFileSync(CONSTANTS_FILE, 'utf8');
const capabilitySrc = readFileSync(CAPABILITY_FILE, 'utf8');
const clientSrc = readFileSync(CLIENT_FILE, 'utf8');

const ev = (over = {}) =>
  evaluate({
    constantsSrc,
    capabilitySrc,
    clientSrc,
    ...over
  });

// 1) 真实树：三条判据全绿
{
  const p = ev();
  assert(p.length === 0, `真实树应绿：${p.join('|')}`);
}

// 2) 注入①：新增一条 MQTT 指令常量但不声明归属 ⇒ 红
{
  const injected = constantsSrc.replace(
    /(\bstatic\s+final\s+String\s+MQTT_CMD_SELF_TEST\b[^;]*;)/,
    '$1\n    public static final String MQTT_CMD_FORMAT_DISK = "FORMAT_DISK";'
  );
  assert(injected !== constantsSrc, '注入①未生效（找不到 SELF_TEST 常量）');
  const p = ev({ constantsSrc: injected });
  assert(
    p.some(
      (x) => x.includes('MQTT_CMD_FORMAT_DISK') && x.includes('未在 JiangyiCommandCapability')
    ),
    `新增指令未声明将邑归属必须红：${p.join('|')}`
  );
}

// 3) 注入②：能力表丢掉 REBOOT 条目 ⇒ 红（这正是「能路由却发 MQTT」的形态）
{
  const injected = capabilitySrc.replace(
    /\s*m\.put\(CabinetConstants\.MQTT_CMD_REBOOT, Route\.GATEWAY\);/,
    ''
  );
  assert(injected !== capabilitySrc, '注入②未生效（找不到 REBOOT 条目）');
  const p = ev({ capabilitySrc: injected });
  assert(
    p.some((x) => x.includes('MQTT_CMD_REBOOT') && x.includes('未在 JiangyiCommandCapability')),
    `REBOOT 归属被摘掉必须红：${p.join('|')}`
  );
}

// 4) 注入③：声明一个常量表里没有的名字（改名后忘同步）⇒ 红
{
  const injected = capabilitySrc.replace(
    'm.put(CabinetConstants.MQTT_CMD_SELF_TEST, Route.UNSUPPORTED);',
    'm.put(CabinetConstants.MQTT_CMD_SELF_TEST_V2, Route.UNSUPPORTED);'
  );
  assert(injected !== capabilitySrc, '注入③未生效');
  const p = ev({ capabilitySrc: injected });
  assert(
    p.some((x) => x.includes('不存在的常量 MQTT_CMD_SELF_TEST_V2')),
    `陈旧常量名必须红：${p.join('|')}`
  );
  assert(
    p.some((x) => x.includes('MQTT_CMD_SELF_TEST') && x.includes('未在 JiangyiCommandCapability')),
    `改名的常量本身也要报「未声明」：${p.join('|')}`
  );
}

// 5) 注入④：设备指令出口丢掉 isJiangyi 分支 ⇒ 红
{
  const injected = clientSrc
    .split('if (jiangyiDeviceDirectory.isJiangyi(deviceId)) {')
    .join('if (false) {');
  assert(injected !== clientSrc, '注入④未生效');
  const p = ev({ clientSrc: injected });
  assert(
    p.some((x) => x.includes('requestOpsCommand 没有 isJiangyi 分支')),
    `requestOpsCommand 丢护栏必须红：${p.join('|')}`
  );
  assert(
    p.some((x) => x.includes('requestSetTargetTemp 没有 isJiangyi 分支')),
    `requestSetTargetTemp 丢护栏必须红：${p.join('|')}`
  );
}

// 6) 注入⑤：护栏里不 throw，改成静默跳过 ⇒ 红（比丢分支更隐蔽）
{
  const injected = clientSrc.replace('throw jiangyiRefusal(command);', 'String skipped = command;');
  assert(injected !== clientSrc, '注入⑤未生效');
  const p = ev({ clientSrc: injected });
  assert(
    p.some((x) => x.includes('requestOpsCommand 的将邑分支没有 throw')),
    `静默跳过必须红：${p.join('|')}`
  );
}

// 7) 注入⑥：把条目挪进注释里（看着还在，实际不执行）⇒ 仍须红
{
  const injected = capabilitySrc.replace(
    'm.put(CabinetConstants.MQTT_CMD_REBOOT, Route.GATEWAY);',
    '// m.put(CabinetConstants.MQTT_CMD_REBOOT, Route.GATEWAY);'
  );
  assert(injected !== capabilitySrc, '注入⑥未生效');
  const p = ev({ capabilitySrc: injected });
  assert(
    p.some((x) => x.includes('MQTT_CMD_REBOOT') && x.includes('未在 JiangyiCommandCapability')),
    `注释掉的条目不得算作已声明：${p.join('|')}`
  );
}

console.log(`${TAG} OK：${checks} 项断言全过（真树绿 + 6 注入红）`);
