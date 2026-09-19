#!/usr/bin/env node
/**
 * 运营告警渠道门禁 —— 「渠道声明 / 报文形状 / 失败判定 / 配置闭环 / 链路接线」对不上就红。
 * 前半（R1–R9）守**应用内**链路，后半（R10–R13）守**监控栈**链路。
 *
 * 背景（09-18）
 * -------------
 * 公司用**飞书**（无钉钉/企微），而 `OpsAlertDispatcher` 只有钉钉/企微/通用三条渠道。
 * 补飞书时踩到两个**静默失效**的坑，它们的共同点是「不报错、只是没送到」：
 *
 *  1. **报文形状**：飞书用下划线 `msg_type`，钉钉/企微用 `msgtype`。
 *     照搬钉钉报文会被飞书以 `code=9499 Bad Request` 拒收。
 *  2. **失败判定**：飞书/钉钉/企微在**业务被拒时仍返回 HTTP 200**，只在响应体里给业务码
 *     （飞书 `code`、钉钉企微 `errcode`）。原来的 `toBodilessEntity()` 丢掉返回体，
 *     于是「日志说已发送、群里没消息」。这正是本项目最忌的**信号在骗读者**。
 *
 * 规则
 * ----
 *   R1 渠道接线：`CHANNELS` 必须声明 FEISHU 并绑定 `OPS_ALERT_FEISHU_WEBHOOK`。
 *   R2 报文形状：`feishuPayload` 必须产出 `msg_type`，且**不得**混入钉钉的 `msgtype`。
 *   R3 分发路由：`payloadFor` 必须把 `"FEISHU"` 显式路由到 `feishuPayload`
 *      （落到 default/generic ⇒ 群里收到的是通用 JSON ⇒ 飞书报 9499）。
 *   R4 失败判定：`deliveryError` 对 FEISHU 必须看 `code`、对 DINGTALK/WECOM 必须看 `errcode`；
 *      且 `postJson` **不得**再丢弃响应体（`toBodilessEntity`）。
 *   R5 配置闭环：每个 `ops.alert.*` 字面量都要在 `SystemConfigService` 里有常量，
 *      **且**在 `upsertIfAbsent` 里 seed（不 seed ⇒ 运营台看不到、也就改不了）。
 *   R6 运营台可见：`AlertRuleView.vue` 的 `BUILTIN_GROUPS` 必须覆盖全部 `ops.alert.*` 键
 *      （结构性规则，未来新增键会自动被要求露出来）。
 *   R7 试发能力：`probeChannels` 必须遍历 `CHANNELS`（新渠道自动被试发覆盖）。
 *   R8 试发端点：`SystemConfigController` 必须暴露 `POST /alert-test` 且调用 `probeChannels`。
 *   R9 前端闭环：`endpoints.ts` 的 `systemConfigAlertTest` 指向 `/system-configs/alert-test`，
 *      且 `AlertRuleView.vue` 真的调了它（否则端点=死代码、按钮=摆设）。
 *
 *   —— 以下四项守住**监控栈那一侧**（Prometheus/Grafana 两条链路末端）——
 *   这一侧的失效形态与 R1–R9 完全不同：**配置语法全对、组件也起得来，只是名字对不上**，
 *   于是告警永远送不到。典型是「alertmanager 的 receiver URL 写死了 relay 的服务名，
 *   而 compose 里那个服务被改名」——两处各自都"合法"，合起来断链。
 *   本仓铁律：「一致性 ≠ 有效性」，所以这里判的是**跨文件的有效值**，不是各文件内部自洽。
 *
 *   R10 桥的失败语义：`feishu-relay.py` 未配置必须 `return 503`、飞书 `code != 0` 必须走 502，
 *      且报文用 `msg_type`（飞书）而非 `msgtype`。若它拿 200 表示"没送出去"，
 *      Alertmanager 会把失败当成功、不再重试 —— 又是一个「信号在骗读者」。
 *      R10b：server 必须用 `ThreadingHTTPServer` **装配**。HTTP/1.1 是 keep-alive，
 *      单线程 server 会被 Alertmanager（Go，复用连接）的一条空闲连接饿死
 *      ⇒ 第一条告警之后就全部静默超时（2026-09-18 真实栈实测复现）。
 *   R11 链路接线（跨文件）：`alertmanager.yml` 的 receiver URL `host:port/path` 里，
 *      host 必须是 `docker-compose.full.yml` 里**真实存在**的服务，port 必须等于该服务
 *      的 `PORT` 环境变量值（有效值），path 必须是桥真的接受的路由。
 *   R12 下游必须存在：凡 prometheus 配置里写了 `rule_files:`（即会评估规则），
 *      就必须有 `alerting:` 段，且 targets 的 host 是 compose 里的真实服务、
 *      port 是它的容器端口。否则就是「规则会评估、无人被通知」。
 *   R13 起得来：两个服务都得挂在 `alerting` profile 下（避免本机资源有限时被默认拉起）、
 *      alertmanager 得 `depends_on` 桥、两者挂载的文件路径必须存在。
 *
 * 防「恒真 / 恒假」：解析出的渠道数、告警键数、分组数、展示键数、compose 服务数任一项低于下限 ⇒ 红
 * （锚点漂了就是失去判别力，不能假绿）。负向对照（分支逐个真注入漂移 → 必红）见
 * `scripts/devops/verify-ops-alert-channels-drift.py`。
 *
 *   node scripts/check-ops-alert-channels.mjs
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-ops-alert-channels]';

const DISPATCHER = join(
  root,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/OpsAlertDispatcher.java'
);
const CONFIG_SERVICE = join(
  root,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/SystemConfigService.java'
);
const ALERT_VIEW = join(root, 'clients/admin-vue/src/views/system/AlertRuleView.vue');
const CONTROLLER = join(
  root,
  'services/trade-service/src/main/java/com/aicabinet/trade/api/SystemConfigController.java'
);
const ENDPOINTS = join(root, 'clients/admin-vue/src/api/endpoints.ts');
const RELAY = join(root, 'infra/monitoring/feishu-relay.py');
const AM_CONFIG = join(root, 'infra/monitoring/alertmanager.yml');
const FULL_COMPOSE = join(root, 'infra/docker-compose.full.yml');
/** 会评估告警规则的 prometheus 配置（`prometheus.yml` 是 dev 栈的极简版，不加载规则）。 */
const PROM_CONFIGS = [
  'infra/monitoring/prometheus.yml',
  'infra/monitoring/prometheus.compose.yml',
  'infra/monitoring/prometheus-full.yml'
].map((p) => join(root, p));

/** Grafana 侧 provisioning（R14）：contact point / policy / 将来的 alert rule。 */
const GF_ALERTING_DIR = join(root, 'infra/monitoring/grafana/provisioning/alerting');
const GF_CONTACT_POINTS = join(GF_ALERTING_DIR, 'contact-points.yml');
const GF_POLICIES = join(GF_ALERTING_DIR, 'policies.yml');
/** Prometheus 侧规则（R14d 的重名对照面）。 */
const PROM_ALERT_RULES = join(root, 'infra/prometheus/alert_rules.yml');

/** 解析下限：低于此值说明锚点/结构已变，门禁失去判别力。 */
const MIN_CHANNELS = 4;
const MIN_ALERT_KEYS = 5;
const MIN_GROUPS = 5;
const MIN_DISPLAYED = 5;
const MIN_SERVICES = 8;
const MIN_RELAY_CHARS = 2000;
/** R14 锚点下限：Grafana 侧至少要解析出这么多 contact point / receiver，否则结构已变。 */
const MIN_GF_CONTACT_POINTS = 1;
const MIN_GF_RECEIVERS = 1;

/** 桥接受的路由（与 feishu-relay.py 的 do_POST 一致）。 */
const RELAY_PATHS = ['/webhook', '/'];

/**
 * 允许不出现在运营台的 `ops.alert.*` 键（内部键）。
 * 目前为空；将来若有纯内部键，加到这里 **并注明原因**，不要放宽 R6 整体。
 */
const INTERNAL_ALERT_KEYS = new Set([]);

const fail = (msg) => {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
};

const rel = (p) => relative(root, p).replace(/\\/g, '/');

for (const f of [
  DISPATCHER,
  CONFIG_SERVICE,
  ALERT_VIEW,
  CONTROLLER,
  ENDPOINTS,
  RELAY,
  AM_CONFIG,
  FULL_COMPOSE,
  GF_CONTACT_POINTS,
  GF_POLICIES,
  PROM_ALERT_RULES,
  ...PROM_CONFIGS
]) {
  if (!existsSync(f)) fail(`缺少被校验文件 ${rel(f)}（路径可能已变）`);
}

const dispatcher = readFileSync(DISPATCHER, 'utf8');
const configService = readFileSync(CONFIG_SERVICE, 'utf8');
const alertView = readFileSync(ALERT_VIEW, 'utf8');
const controller = readFileSync(CONTROLLER, 'utf8');
const endpoints = readFileSync(ENDPOINTS, 'utf8');
const relay = readFileSync(RELAY, 'utf8');
const amConfig = readFileSync(AM_CONFIG, 'utf8');
const compose = readFileSync(FULL_COMPOSE, 'utf8');
const gfContactPoints = readFileSync(GF_CONTACT_POINTS, 'utf8');
const gfPolicies = readFileSync(GF_POLICIES, 'utf8');
const promAlertRules = readFileSync(PROM_ALERT_RULES, 'utf8');

const problems = [];

/** 抽出某个方法/字段的源码片段：从 `from` 匹配处起，到下一个顶层成员结束。 */
function member(name, source) {
  const start = source.indexOf(name);
  if (start < 0) return null;
  const end = source.indexOf('\n    }', start);
  return end < 0 ? source.slice(start) : source.slice(start, end);
}

/** 抽出 Python 里某个顶层函数的整块源码（到下一个顶层 def/class 为止）。 */
function pyFunction(name, source) {
  const start = source.indexOf(`def ${name}(`);
  if (start < 0) return null;
  const rest = source.slice(start);
  const next = rest.slice(1).search(/^(?:def |class )/m);
  return next < 0 ? rest : rest.slice(0, next + 1);
}

/** 抽出 YAML 里某个**顶层**键的整块（到下一个列 0 的键为止）。 */
function topLevelBlock(text, key) {
  const m = text.match(new RegExp(`^${key}:[ \\t]*$`, 'm'));
  if (!m) return null;
  const rest = text.slice(m.index + m[0].length);
  const next = rest.search(/^\S/m);
  return next < 0 ? rest : rest.slice(0, next);
}

/**
 * 解析 compose 的 `services:` 段 → Map<服务名, 服务块源码>。
 * ⚠️ 必须先切出 `services:` 块：顶层还有 `volumes:`/`networks:`，其子项同样缩进两格，
 * 不切的话 `pgdata:` 会被当成服务名，于是"host 是不是真实服务"这条判据就失去意义。
 */
function composeServices(text) {
  const block = topLevelBlock(text, 'services');
  if (block === null) return null;
  const marks = [...block.matchAll(/^ {2}([A-Za-z0-9_.-]+):[ \t]*$/gm)];
  const map = new Map();
  marks.forEach((m, i) => {
    const start = m.index + m[0].length;
    const end = i + 1 < marks.length ? marks[i + 1].index : block.length;
    map.set(m[1], block.slice(start, end));
  });
  return map;
}

// ---------- R1 渠道接线 ----------
const channels = [
  ...dispatcher.matchAll(/new Channel\(\s*"(\w+)"\s*,\s*SystemConfigService\.(\w+)\s*\)/g)
].map((m) => ({ name: m[1], constant: m[2] }));
if (channels.length < MIN_CHANNELS) {
  fail(`只解析出 ${channels.length} 条告警渠道（期望 ≥ ${MIN_CHANNELS}）：CHANNELS 结构可能已变`);
}
const byName = new Map(channels.map((c) => [c.name, c]));
if (!byName.has('FEISHU')) {
  problems.push('CHANNELS 未声明 FEISHU 渠道（公司无钉钉/企微，告警将无人接收）');
} else if (byName.get('FEISHU').constant !== 'OPS_ALERT_FEISHU_WEBHOOK') {
  problems.push(
    `FEISHU 渠道绑定的配置常量是 ${byName.get('FEISHU').constant}，期望 OPS_ALERT_FEISHU_WEBHOOK`
  );
}

// ---------- R2 报文形状 ----------
const feishuBody = member('static Map<String, Object> feishuPayload', dispatcher);
if (!feishuBody) {
  problems.push('OpsAlertDispatcher 里找不到 feishuPayload 方法');
} else {
  if (!/body\.put\(\s*"msg_type"/.test(feishuBody)) {
    problems.push('feishuPayload 未产出 `msg_type`（飞书要求下划线拼写）');
  }
  if (/body\.put\(\s*"msgtype"/.test(feishuBody)) {
    problems.push('feishuPayload 混入了钉钉的 `msgtype` 字段（飞书会以 9499 Bad Request 拒收）');
  }
  if (!/content\.put\(\s*"text"/.test(feishuBody)) {
    problems.push('feishuPayload 未产出 `content.text`');
  }
}

// ---------- R3 分发路由 ----------
const payloadFor = member('private Object payloadFor', dispatcher);
if (!payloadFor) {
  problems.push('OpsAlertDispatcher 里找不到 payloadFor（渠道→报文路由）');
} else if (!/case\s+"FEISHU"\s*->\s*feishuPayload\s*\(/.test(payloadFor)) {
  problems.push(
    'payloadFor 未把 "FEISHU" 路由到 feishuPayload（会退回通用 JSON，被飞书 9499 拒收）'
  );
}

// ---------- R4 失败判定 ----------
const deliveryError = member('static String deliveryError', dispatcher);
if (!deliveryError) {
  problems.push('OpsAlertDispatcher 里找不到 deliveryError（平台业务码判定）');
} else {
  for (const line of deliveryError.split('\n')) {
    if (!/^\s*case\s/.test(line)) continue;
    if (/"FEISHU"/.test(line) && !/"code"/.test(line)) {
      problems.push('deliveryError 的 FEISHU 分支未看 `code` 业务码');
    }
    if (/"(DINGTALK|WECOM)"/.test(line) && !/"errcode"/.test(line)) {
      problems.push('deliveryError 的 DINGTALK/WECOM 分支未看 `errcode` 业务码');
    }
  }
}
if (/\.toBodilessEntity\(\)/.test(dispatcher)) {
  problems.push(
    'postJson 仍用 toBodilessEntity() 丢弃响应体 ⇒ HTTP 200 即算成功的假绿（业务被拒收不会被发现）'
  );
}

// ---------- R5 配置闭环 ----------
const constants = new Map(
  [...configService.matchAll(/public static final String (\w+)\s*=\s*"(ops\.alert\.[\w.]+)"/g)].map(
    (m) => [m[2], m[1]]
  )
);
if (constants.size < MIN_ALERT_KEYS) {
  fail(
    `只解析出 ${constants.size} 个 ops.alert.* 配置键（期望 ≥ ${MIN_ALERT_KEYS}）：常量格式可能已变`
  );
}
const seeded = new Set(
  [...configService.matchAll(/upsertIfAbsent\(\s*(\w+)\s*,/g)].map((m) => m[1])
);
for (const [key, constant] of constants) {
  if (!seeded.has(constant)) {
    problems.push(
      `配置键 ${key}（常量 ${constant}）未在 upsertIfAbsent 里 seed ⇒ 运营台看不到、改不了`
    );
  }
}

// ---------- R6 运营台可见 ----------
const groupsStart = alertView.indexOf('const BUILTIN_GROUPS');
if (groupsStart < 0) {
  fail('AlertRuleView.vue 里找不到 BUILTIN_GROUPS：结构可能已变，门禁失去判别力');
}
const groupsEnd = alertView.indexOf('\n};', groupsStart);
if (groupsEnd < 0) fail('BUILTIN_GROUPS 块未正常结束');
const groupsBlock = alertView.slice(groupsStart, groupsEnd);

const groupCount = [...groupsBlock.matchAll(/^\s{2}[^\s{][^:]*:\s*\[/gm)].length;
if (groupCount < MIN_GROUPS) {
  fail(`只解析出 ${groupCount} 个告警分组（期望 ≥ ${MIN_GROUPS}）：BUILTIN_GROUPS 结构可能已变`);
}
const displayed = new Set([...groupsBlock.matchAll(/'([\w.]+)'/g)].map((m) => m[1]));
if (displayed.size < MIN_DISPLAYED) {
  fail(`BUILTIN_GROUPS 只解析出 ${displayed.size} 个键（期望 ≥ ${MIN_DISPLAYED}）：结构可能已变`);
}
for (const key of constants.keys()) {
  if (INTERNAL_ALERT_KEYS.has(key)) continue;
  if (!displayed.has(key)) {
    problems.push(`配置键 ${key} 未出现在 AlertRuleView.vue 的 BUILTIN_GROUPS ⇒ 运营台无法配置`);
  }
}

// ---------- R7 试发能力 ----------
const probe = member('public List<ChannelProbe> probeChannels', dispatcher);
if (!probe) {
  problems.push(
    'OpsAlertDispatcher 缺少 probeChannels ⇒ 渠道配错时运营无从自查（HTTP 200 + 业务码）'
  );
} else if (!/\bCHANNELS\b/.test(probe)) {
  problems.push('probeChannels 未遍历 CHANNELS ⇒ 新增渠道不会被试发覆盖');
}

// ---------- R8 试发端点 ----------
if (!/@PostMapping\(\s*"\/alert-test"\s*\)/.test(controller)) {
  problems.push('SystemConfigController 未暴露 POST /alert-test ⇒ 运营台没有「测试发送」入口');
} else if (!/\.probeChannels\(/.test(controller)) {
  problems.push('POST /alert-test 未调用 probeChannels');
}

// ---------- R9 前端闭环 ----------
const mapped = endpoints.match(/systemConfigAlertTest:\s*`([^`]+)`/);
if (!mapped) {
  problems.push('endpoints.ts 未定义 systemConfigAlertTest');
} else if (!mapped[1].endsWith('/system-configs/alert-test')) {
  problems.push(
    `systemConfigAlertTest 指向 ${mapped[1]}，与后端 /api/v2/ops/admin/system-configs/alert-test 不一致`
  );
} else if (!/AdminEndpoints\.systemConfigAlertTest/.test(alertView)) {
  problems.push('AlertRuleView.vue 未调用 AdminEndpoints.systemConfigAlertTest ⇒ 端点是死代码');
}

// ---------- R10 桥的失败语义（不得拿 200 冒充未送达） ----------
// ⚠️ 判据必须落在**真正起作用的那几行**上：整个文件里搜 `msg_type` 是没用的，
// 注释和文档字符串里就有这个词，改坏代码也照样匹配 ⇒ 那是恒真判据。
if (relay.length < MIN_RELAY_CHARS) {
  fail(
    `feishu-relay.py 只读到 ${relay.length} 字符（期望 ≥ ${MIN_RELAY_CHARS}）：文件可能被清空或路径已变`
  );
}
const relayBody = pyFunction('build_feishu_body', relay);
if (!relayBody) {
  problems.push('feishu-relay.py 里找不到 build_feishu_body（报文构造处，锚点可能已变）');
} else {
  if (!/["']msg_type["']/.test(relayBody)) {
    problems.push('feishu-relay.py 的 build_feishu_body 未产出 `msg_type`（飞书要求下划线拼写）');
  }
  if (/["']msgtype["']/.test(relayBody)) {
    problems.push(
      'feishu-relay.py 的 build_feishu_body 用了 `msgtype`（钉钉/企微的拼写，飞书会 9499 拒收）'
    );
  }
}
const relayForward = pyFunction('forward_to_feishu', relay);
if (!relayForward) {
  problems.push('feishu-relay.py 里找不到 forward_to_feishu（投递与失败判定处）');
} else {
  if (!/if\s+not\s+FEISHU_WEBHOOK_URL:[\s\S]{0,200}?return\s+503,/.test(relayForward)) {
    problems.push(
      'feishu-relay.py 未在 `FEISHU_WEBHOOK_URL` 缺失时返回 503 ⇒ 会拿 200 冒充"已投递"，消息全丢无人知'
    );
  }
  if (!/if\s+code\s*!=\s*0:[\s\S]{0,200}?return\s+[45]\d\d,/.test(relayForward)) {
    problems.push(
      'feishu-relay.py 的「飞书业务码非 0」分支没有返回失败码（4xx/5xx）⇒ 飞书拒收（关键词/签名/IP 白名单）会被当成投递成功'
    );
  }
}

// R10b 防「被饿死」：HTTP/1.1(keep-alive) 必须配**多线程** server。
// 单线程 HTTPServer 处理完一个请求后会阻塞在「等同一连接的下一个请求」上，不再 accept 新连接；
// Alertmanager 是 Go 客户端、**会复用连接** ⇒ 桥被一条空闲连接占死，
// 表现为「第一条告警发得出去，之后全部静默超时」（2026-09-18 在真实栈上实测复现）。
// ⚠️ 判据落在**装配行**上，不是整个文件里搜 `ThreadingHTTPServer`——
// 说明文字里也会出现这个词，那样就是恒真判据。
const relayMain = pyFunction('main', relay);
if (!relayMain) {
  problems.push('feishu-relay.py 里找不到 main（HTTP server 装配处，锚点可能已变）');
} else if (!/server\s*=\s*ThreadingHTTPServer\(/.test(relayMain)) {
  problems.push(
    'feishu-relay.py 的 main 未用 ThreadingHTTPServer 装配 server ⇒ keep-alive 下会被单个复用连接饿死，第一条之后的告警全部静默丢失'
  );
}

// ---------- R11 链路接线（跨文件：alertmanager.yml ↔ compose） ----------
const services = composeServices(compose);
if (!services) {
  fail('docker-compose.full.yml 里找不到 services: 段：结构可能已变，门禁失去判别力');
}
if (services.size < MIN_SERVICES) {
  fail(`只解析出 ${services.size} 个 compose 服务（期望 ≥ ${MIN_SERVICES}）：结构可能已变`);
}

const receiverUrl = amConfig.match(/url:\s*['"]?https?:\/\/([A-Za-z0-9_.-]+):(\d+)(\/[^\s'"]*)?/);
if (!receiverUrl) {
  problems.push('alertmanager.yml 里找不到 receiver 的 http://host:port 地址（告警无处可发）');
} else {
  const [, host, port, path = ''] = receiverUrl;
  const relayBlock = services.get(host);
  if (!relayBlock) {
    problems.push(
      `alertmanager.yml 的 receiver 指向 ${host}，但 docker-compose.full.yml 里没有这个服务名 ⇒ 容器内解析不到、告警永远送不出（改名即断链）`
    );
  } else {
    // 判「有效值」：容器里真正监听哪个端口由 PORT 环境变量说了算，不是端口映射的左半边。
    const envPort = relayBlock.match(/PORT:\s*["']?(\d+)/);
    if (!envPort) {
      problems.push(
        `compose 服务 ${host} 未声明 PORT ⇒ 无法确认它监听哪个端口（receiver 写的是 ${port}）`
      );
    } else if (envPort[1] !== port) {
      problems.push(
        `alertmanager.yml 把告警发到 ${host}:${port}，而该服务实际监听 ${envPort[1]}（compose 的 PORT）⇒ 连接被拒`
      );
    }
    if (!RELAY_PATHS.includes(path)) {
      problems.push(
        `alertmanager.yml 的 receiver 路径是 ${path || '(空)'}，而 feishu-relay.py 只接受 ${RELAY_PATHS.join(' / ')} ⇒ 桥会回 404`
      );
    }
  }
}

// ---------- R12 规则有加载，就必须有下游 ----------
for (const promPath of PROM_CONFIGS) {
  const prom = readFileSync(promPath, 'utf8');
  const hasRules = /^rule_files:/m.test(prom);
  const alerting = topLevelBlock(prom, 'alerting');
  if (hasRules && !alerting) {
    problems.push(
      `${rel(promPath)} 加载了 rule_files 却没有 alerting 段 ⇒ 规则会评估、无人被通知（这是本仓反复出现的失效形态）`
    );
    continue;
  }
  if (!alerting) continue;
  const targets = [...alerting.matchAll(/["']([A-Za-z0-9_.-]+):(\d+)["']/g)].map((m) => ({
    host: m[1],
    port: m[2]
  }));
  if (!targets.length) {
    problems.push(`${rel(promPath)} 的 alerting 段里找不到 targets host:port（锚点可能已漂）`);
    continue;
  }
  for (const t of targets) {
    const targetBlock = services.get(t.host);
    if (!targetBlock) {
      problems.push(
        `${rel(promPath)} 的 alertmanager target 指向 ${t.host}，但 compose 里没有这个服务 ⇒ 告警推不到 Alertmanager`
      );
      continue;
    }
    const containerPort = (targetBlock.match(/["'][^"']*:(\d+)["']/) || [])[1];
    if (containerPort && containerPort !== t.port) {
      problems.push(
        `${rel(promPath)} 把告警推到 ${t.host}:${t.port}，但该服务的容器端口是 ${containerPort} ⇒ 推错端口`
      );
    }
  }
}

// ---------- R13 起得来 ----------
for (const name of [receiverUrl?.[1], 'alertmanager'].filter(Boolean)) {
  const block = services.get(name);
  if (!block) continue; // 服务名缺失已在 R11/R12 报过
  if (!/profiles:\s*\[[^\]]*["']alerting["']/.test(block)) {
    problems.push(
      `compose 服务 ${name} 未挂在 \`alerting\` profile 下 ⇒ 会被默认栈拉起（本机资源有限，故要求显式启用）`
    );
  }
}
const amService = services.get('alertmanager');
if (
  amService &&
  receiverUrl?.[1] &&
  !new RegExp(`depends_on:[\\s\\S]{0,80}${receiverUrl[1]}`).test(amService)
) {
  problems.push(
    `compose 的 alertmanager 未 depends_on ${receiverUrl[1]} ⇒ 先起 alertmanager 时告警会被丢`
  );
}
for (const [name, mount] of [
  [receiverUrl?.[1], 'monitoring/feishu-relay.py'],
  ['alertmanager', 'monitoring/alertmanager.yml']
]) {
  if (!name) continue;
  const block = services.get(name);
  if (block && !block.includes(mount)) {
    problems.push(`compose 服务 ${name} 未挂载 ${mount} ⇒ 容器里用的不是仓库这份配置`);
  }
}

// ---------- R14 Grafana 侧告警渠道 ----------
// 背景（2026-09-19 实测）】：Grafana 侧长期是 `type: email` + `addresses: ops@aicabinet.local`，
// 而全仓**没有任何 `GF_SMTP_*`** ⇒ 该 contact point 一旦被触发必然发不出去，看板上却看不出异常
// （「写了但永远不会送达」= 信号在骗读者）。Grafana 与 Alertmanager 一样**没有原生飞书 receiver**，
// 而桥 `feishu-relay.py` 的 `render_alert_text` 同时认两家的报文
// （都读 status/alerts[].labels|annotations）⇒ Grafana 复用同一个桥即可，不需要第二个组件。
const HAS_SMTP = /GF_SMTP_/.test(compose);

const grafanaService = services.get('grafana');
if (!grafanaService) {
  problems.push(
    'compose 里没有 grafana 服务 ⇒ 这些 provisioning 文件不会被任何容器加载（整套是死的）'
  );
} else if (!grafanaService.includes('monitoring/grafana/provisioning')) {
  problems.push(
    'compose 的 grafana 未挂载 monitoring/grafana/provisioning ⇒ 容器里用的不是仓库这份配置'
  );
}

const cpBlock = topLevelBlock(gfContactPoints, 'contactPoints');
if (cpBlock === null) {
  fail('contact-points.yml 里找不到顶层 contactPoints:（结构可能已变，R14 会失去判别力）');
}
// 每个 contact point 以两空格 + `- ` 起头；其下 receiver 以六空格 + `- ` 起头。
const cpChunks = cpBlock.split(/^ {2}- /m).slice(1);
const gfCpNames = [];
let gfReceiverCount = 0;

for (const chunk of cpChunks) {
  const nameMatch = chunk.match(/^\s{4}name:\s*["']?([A-Za-z0-9_.-]+)["']?/m);
  const cpName = nameMatch ? nameMatch[1] : '(未命名)';
  if (nameMatch) gfCpNames.push(cpName);

  for (const rc of chunk.split(/^\s{6}- /m).slice(1)) {
    gfReceiverCount += 1;
    const type = (rc.match(/^\s{8}type:\s*["']?([A-Za-z_]+)["']?/m) || [])[1] || '';
    if (!type) {
      problems.push(
        `Grafana contact point ${cpName} 的某个 receiver 没写 type ⇒ 无法判断它能不能送达`
      );
      continue;
    }

    if (type === 'email') {
      for (const m of rc.matchAll(/([^\s"'@,]+@[A-Za-z0-9_.-]+)/g)) {
        const domain = m[1].split('@')[1] || '';
        if (
          /\.local$/i.test(domain) ||
          /^(example\.(com|org|net)|localhost|invalid)$/i.test(domain)
        ) {
          problems.push(
            `Grafana contact point ${cpName} 的 email 收件地址是 ${m[1]}（本地/保留域，不可投递）⇒ 该渠道永远发不出去，看板上却看不出异常`
          );
        }
      }
      if (!HAS_SMTP) {
        problems.push(
          `Grafana contact point ${cpName} 用 email，但 compose 里没有任何 GF_SMTP_* ⇒ Grafana 未配 SMTP、通知必然失败（本仓真实渠道是飞书，应改 webhook 指向 feishu-alert-relay）`
        );
      }
    }

    if (type === 'webhook') {
      const url = (rc.match(/url:\s*["']?(https?:\/\/[^\s"']+)["']?/) || [])[1];
      if (!url) {
        problems.push(
          `Grafana contact point ${cpName} 的 webhook receiver 没有 url ⇒ 告警无处可发`
        );
        continue;
      }
      const parsed = url.match(/^https?:\/\/([A-Za-z0-9_.-]+):(\d+)(\/[^\s"']*)?$/);
      if (!parsed) {
        problems.push(`Grafana contact point ${cpName} 的 webhook url 解析不出 host:port：${url}`);
        continue;
      }
      const [, host, port, path = ''] = parsed;
      const block = services.get(host);
      if (!block) {
        problems.push(
          `Grafana contact point ${cpName} 的 webhook 指向 ${host}，但 docker-compose.full.yml 里没有这个服务名 ⇒ 容器内解析不到、告警永远送不出（改名即断链）`
        );
        continue;
      }
      // 判「有效值」：容器真正监听哪个端口由 PORT 环境变量说了算，不是端口映射的左半边。
      const envPort = block.match(/PORT:\s*["']?(\d+)/);
      if (!envPort) {
        problems.push(
          `compose 服务 ${host} 未声明 PORT ⇒ 无法确认它监听哪个端口（Grafana 写的是 ${port}）`
        );
      } else if (envPort[1] !== port) {
        problems.push(
          `Grafana 把告警发到 ${host}:${port}，而该服务实际监听 ${envPort[1]}（compose 的 PORT）⇒ 连接被拒`
        );
      }
      if (!RELAY_PATHS.includes(path)) {
        problems.push(
          `Grafana contact point ${cpName} 的 webhook 路径是 ${path || '(空)'}，而 feishu-relay.py 只接受 ${RELAY_PATHS.join(' / ')} ⇒ 桥会回 404`
        );
      }
    }
  }
}

if (cpChunks.length < MIN_GF_CONTACT_POINTS) {
  fail(
    `只解析出 ${cpChunks.length} 个 Grafana contact point（期望 ≥ ${MIN_GF_CONTACT_POINTS}）：结构可能已变`
  );
}
if (gfReceiverCount < MIN_GF_RECEIVERS) {
  fail(
    `只解析出 ${gfReceiverCount} 个 Grafana receiver（期望 ≥ ${MIN_GF_RECEIVERS}）：结构可能已变`
  );
}

// R14c 路由悬空：policies 引用的 receiver 必须真实存在（名字对不上 = 告警静默丢失）
const policyReceivers = [
  ...gfPolicies.matchAll(/^\s*receiver:\s*["']?([A-Za-z0-9_.-]+)["']?/gm)
].map((m) => m[1]);
if (!policyReceivers.length) {
  fail('policies.yml 里解析不到任何 receiver（结构可能已变，R14c 会失去判别力）');
}
for (const r of policyReceivers) {
  if (!gfCpNames.includes(r)) {
    problems.push(
      `policies.yml 的 receiver「${r}」在 contact-points.yml 里不存在（现有：${gfCpNames.join('/')}）⇒ 告警路由到空处、静默丢失`
    );
  }
}

// R14d 双通道重复：Grafana 规则不得与 Prometheus 规则重名
// （同一条件两条通道 = 飞书群收到两份重复告警，且两边阈值会各自分叉）
const promAlertNames = new Set(
  [...promAlertRules.matchAll(/^\s*-\s*alert:\s*([A-Za-z0-9_]+)/gm)].map((m) => m[1])
);
if (!promAlertNames.size) {
  fail('alert_rules.yml 里解析不到任何 `- alert:`（结构可能已变，R14d 会失去判别力）');
}
if (existsSync(GF_ALERTING_DIR)) {
  const gfRuleFiles = readdirSync(GF_ALERTING_DIR).filter(
    (n) => /\.ya?ml$/.test(n) && n !== 'contact-points.yml' && n !== 'policies.yml'
  );
  for (const rf of gfRuleFiles) {
    const text = readFileSync(join(GF_ALERTING_DIR, rf), 'utf8');
    for (const m of text.matchAll(/^\s*title:\s*["']?([A-Za-z0-9_]+)["']?/gm)) {
      if (promAlertNames.has(m[1])) {
        problems.push(
          `Grafana 规则文件 ${rf} 的 title「${m[1]}」与 prometheus/alert_rules.yml 同名 ⇒ 同一条件被两条通道各投递一次（飞书群出现重复告警、阈值还会分叉）`
        );
      }
    }
  }
}

if (problems.length) {
  console.error(`${TAG} FAIL: 发现 ${problems.length} 处告警渠道缺陷：`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}

console.log(
  `${TAG} OK: ${channels.length} 条渠道（${[...byName.keys()].join('/')}）；` +
    `${constants.size} 个 ops.alert.* 配置键全部 seed 且在运营台可见；` +
    '飞书报文 msg_type/content 与失败码判定就位；' +
    `监控栈接线一致（${receiverUrl[1]}:${receiverUrl[2]}${receiverUrl[3] || ''} ∈ ${services.size} 个 compose 服务）；` +
    `Grafana 侧 R14：${cpChunks.length} 个 contact point / ${gfReceiverCount} 个 receiver，` +
    `receiver「${policyReceivers.join('/')}」可解析、无假邮箱、未与 ${promAlertNames.size} 条 Prometheus 规则重名`
);
