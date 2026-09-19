#!/usr/bin/env node
/**
 * Prometheus 指标名一致性门禁 —— 「消费方引用的指标名，实现里到底有没有」。
 *
 * 背景（09-18 实测 + 可复现实验）
 * ------------------------------
 * 告警规则/看板/运维页都按 `cabinet_devices_total` 写，而运行中的 trade-service
 * `/actuator/prometheus` 里根本没有这个名字 —— 只有 `cabinet_devices 3.0`。
 * 用镜像里的 micrometer 1.15.12 跑最小复现（registry.gauge 三种命名并列导出）：
 *
 *   reg.gauge("cabinet.devices.total", …)  →  cabinet_devices        ← `_total` 被剥掉
 *   reg.gauge("cabinet.devices.count", …)  →  cabinet_devices_count  ← 保留
 *   reg.gauge("cabinet.devices.online", …) →  cabinet_devices_online ← 保留
 *   reg.counter("cabinet.door.open", …)    →  cabinet_door_open_total ← 追加
 *
 * ⇒ Micrometer 把 `_total` 当作 **Counter 的保留后缀**：Counter 追加、Gauge 剥离。
 * 于是任何按 `xxx_total` 写的 **Gauge** 引用都会静默失效（面板空白、告警永不触发），
 * 且**不报错**。本轮实证受害面：2 条告警（含 `DeviceAllOffline` critical）＋
 * Grafana 面板 ＋ 运维页 2 张卡片。
 *
 * 本门禁把这条转换规则固化下来，静态校验所有消费方。
 *
 * 三条规则
 * --------
 *   R1 死配置：`infra/**` 下任何含 `- alert:` 的规则文件，必须**被某个 prometheus 配置的
 *      `rule_files` 引用**；否则须在文件头带标记 `# gate: draft-unimplemented`
 *      （显式声明"这是未实现草稿，别挂"）。防「写了规则但没人加载」。
 *   R2 假指标名（规则）：被加载的规则文件里，expr 引用的每个指标名必须能在
 *      **代码里 Micrometer 注册点**（按上面的转换规则折算）或**内置白名单**中找到。
 *   R3 假指标名（看板/运维页）：Grafana 看板 JSON 与 admin-vue 的 `expr` 同上。
 *
 * 防「恒真 / 恒假」：注册点解析数 < MIN_REGISTERED 或 引用总数 < MIN_REFS ⇒ 红。
 * 负向对照（改回 `_total` → 必红）由 `scripts/devops/verify-metric-names-drift.py` 负责。
 *
 *   node scripts/check-prometheus-metric-names.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-prometheus-metric-names]';

const MIN_REGISTERED = 15;
const MIN_REFS = 8;
const DRAFT_MARK = '# gate: draft-unimplemented';

/** actuator / exporter 自带指标：无法从业务代码里找到注册点，按前缀放行（逐条注明来源）。 */
const BUILTIN = [
  /^up$/, // Prometheus 合成
  /^jvm_/, // Spring Boot Actuator（JVM）
  /^hikaricp_/, // Actuator（HikariCP）—— 注意实际是 hikaricp_ 而非 hikari_
  /^jdbc_/, // Actuator（DataSource）
  /^tomcat_/, // Actuator（Tomcat）
  /^process_/, // Actuator
  /^system_/, // Actuator
  /^disk_/, // Actuator
  /^executor_/, // Actuator（thread pool）
  /^http_server_requests_/, // Actuator（HTTP）
  /^spring_/, // Actuator（Kafka template 等）
  /^logback_/, // Actuator（日志）
  /^application_/, // Actuator（启动耗时）
  /^tasks_scheduled_/, // Actuator（@Scheduled）
  /^minio_/, // MinIO 自带 /minio/v2/metrics/cluster
  /^prometheus_/ // Prometheus 自身
];

const SKIP_DIR = new Set(['node_modules', 'target', 'dist', 'unpackage', '.git', 'coverage']);

const snake = (s) =>
  s
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/[.-]/g, '_')
    .toLowerCase();

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function walk(dir, filter, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (SKIP_DIR.has(name)) continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, filter, out);
    else if (filter(name)) out.push(p);
  }
  return out;
}

const rel = (p) => relative(root, p).replace(/\\/g, '/');

// ── 1. 从 Java 注册点建「有效指标名」表 ────────────────────────────────────
// base -> 允许的后缀集合。Micrometer 的 `_total` 语义按类型分流，这正是本门禁的核心。
const allowedSuffixes = new Map();
function allow(base, suffixes) {
  const set = allowedSuffixes.get(base) ?? new Set();
  for (const s of suffixes) set.add(s);
  allowedSuffixes.set(base, set);
}

const javaFiles = walk(join(root, 'services'), (n) => n.endsWith('.java'));
let registered = 0;

for (const file of javaFiles) {
  const src = readFileSync(file, 'utf8');
  // 常量表：`String NAME = "a.b.c";`（含 static final）
  const consts = new Map();
  for (const m of src.matchAll(
    /(?:static\s+final\s+String|String)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]+)"/g
  )) {
    consts.set(m[1], m[2]);
  }
  // 注册点：receiver.counter("...") / Gauge.builder("...")，名字可为字面量或常量。
  // ⚠️ builder 的常量形式（如 SchedulingPoolCapacitySelfCheck 的 GAUGE_NAME）必须支持，
  // 否则会把它误判成「不存在的指标」（本项目已在 check-xxl-job-wiring 上踩过同类坑）。
  const reg =
    /(?:\.(counter|gauge|timer|summary)|(Counter|Gauge|Timer|DistributionSummary)\.builder)\(\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))/g;
  const bu = /\.baseUnit\(\s*"([^"]+)"\s*\)/;
  for (const m of src.matchAll(reg)) {
    const kind = (m[1] ?? m[2] ?? '').toLowerCase();
    const raw = m[3] ?? consts.get(m[4]);
    if (!raw) continue;
    const base = snake(raw);
    if (kind === 'counter') allow(base, ['_total']);
    else if (kind === 'gauge')
      allow(base.endsWith('_total') ? base.slice(0, -'_total'.length) : base, ['']);
    else if (kind === 'timer')
      allow(base, [
        '',
        '_count',
        '_sum',
        '_max',
        '_bucket',
        '_seconds',
        '_seconds_count',
        '_seconds_sum',
        '_seconds_max',
        '_seconds_bucket'
      ]);
    else if (kind === 'summary') allow(base, ['', '_count', '_sum', '_max']);
    else continue;
    registered++;
  }
  // DistributionSummary/Timer 的 baseUnit 会拼进名字（如 cabinet.charge.amount + cents）
  for (const m of src.matchAll(/(?:DistributionSummary|Timer)\.builder\(\s*"([^"]+)"/g)) {
    const tail = src.slice(m.index, m.index + 400);
    const unit = bu.exec(tail);
    if (!unit) continue;
    allow(`${snake(m[1])}_${snake(unit[1])}`, [
      '',
      '_count',
      '_sum',
      '_max',
      '_seconds_count',
      '_seconds_sum',
      '_seconds_max',
      '_seconds_bucket'
    ]);
    registered++;
  }
}

if (registered < MIN_REGISTERED) {
  fail(
    `只从 Java 解析出 ${registered} 个 Micrometer 注册点（期望 ≥ ${MIN_REGISTERED}）：` +
      `注册点写法或目录结构可能已变，本门禁已失去判别力`
  );
}

function isKnownMetric(name) {
  if (BUILTIN.some((re) => re.test(name))) return true;
  if (allowedSuffixes.has(name) && allowedSuffixes.get(name).has('')) return true;
  for (const [base, suffixes] of allowedSuffixes) {
    if (!name.startsWith(`${base}_`)) continue;
    if (suffixes.has(name.slice(base.length))) return true;
  }
  return false;
}

// ── 2. 收集消费方引用 ─────────────────────────────────────────────────────
const PROMQL_KEYWORDS = new Set([
  'rate',
  'irate',
  'increase',
  'delta',
  'sum',
  'avg',
  'min',
  'max',
  'count',
  'count_values',
  'stddev',
  'stdvar',
  'topk',
  'bottomk',
  'quantile',
  'histogram_quantile',
  'abs',
  'ceil',
  'clamp',
  'clamp_max',
  'clamp_min',
  'exp',
  'floor',
  'ln',
  'log2',
  'log10',
  'round',
  'scalar',
  'sqrt',
  'time',
  'timestamp',
  'vector',
  'label_replace',
  'label_join',
  'absent',
  'absent_over_time',
  'by',
  'without',
  'on',
  'ignoring',
  'group_left',
  'group_right',
  'bool',
  'offset',
  'and',
  'or',
  'unless',
  'le',
  'job',
  'instance',
  'severity',
  'team',
  'biz',
  'result',
  'status',
  'state',
  'reason',
  'realm',
  'error',
  'outcome',
  'code_namespace',
  'code_function',
  'area',
  'device_id',
  'ticket_id',
  'reason',
  'status_code',
  'method',
  'uri',
  'humanize1024',
  'humanizePercentage'
]);

/** 从一段 PromQL 里抽指标名：先剥掉 `{...}`（标签名）与 `[...]`（范围选择器，否则 `5m`/`1h` 会留下 `m`/`h`）。 */
function metricsIn(expr) {
  const noLabels = expr.replace(/\{[^}]*\}/g, ' ').replace(/\[[^\]]*\]/g, ' ');
  const out = [];
  for (const m of noLabels.matchAll(/[A-Za-z_][A-Za-z0-9_]*/g)) {
    const id = m[0];
    if (id.length <= 1) continue;
    if (PROMQL_KEYWORDS.has(id)) continue;
    if (id.includes('__')) continue;
    out.push(id);
  }
  return out;
}

/** 引用计数在下面的检查循环里累加（`refCount`），用于「锚点是否还有判别力」的守卫。 */

/** 解析 YAML 里 `expr:`（同行标量或 `|` 块）。 */
function exprsOfYaml(file) {
  // ⚠️ 必须容忍 CRLF：Windows 工作区 checkout 出来的 yml 行尾是 `\r\n`，而本函数用的 `$`（非 multiline）
  // 只在**字符串末尾**匹配、`.` 又不吃 `\r` ⇒ `/^(\s*)expr:\s*(.*)$/` 对 `"        expr: |\r"` 会
  // **整条匹配失败**，于是 R2 在任何 CRLF 规则文件上静默失去全部覆盖。
  // 本仓实测（2026-09-19）：在 CRLF 工作区把 `cabinet_devices_count` 改成并不存在的
  // `cabinet_devices_total`，门禁**仍打印 OK** —— 判据已被空转，正是「信号在骗读者」。
  // 故按 `/\r?\n/` 切行（顺带剥掉行尾 `\r`）。
  const lines = readFileSync(file, 'utf8').split(/\r?\n/);
  const found = [];
  for (let i = 0; i < lines.length; i++) {
    const m = /^(\s*)expr:\s*(.*)$/.exec(lines[i]);
    if (!m) continue;
    if (m[2].trim() === '|' || m[2].trim() === '') {
      const indent = m[1].length;
      let body = '';
      for (let j = i + 1; j < lines.length; j++) {
        const l = lines[j];
        if (l.trim() === '') continue;
        if (l.search(/\S/) <= indent) break;
        body += ` ${l.trim()}`;
      }
      found.push({ text: body, line: i + 1 });
    } else {
      found.push({ text: m[2], line: i + 1 });
    }
  }
  return found;
}

const ruleFiles = walk(join(root, 'infra'), (n) => /\.ya?ml$/.test(n)).filter((f) =>
  /^\s+- alert: /m.test(readFileSync(f, 'utf8'))
);

// ── R1：规则文件必须被加载，或显式标记为草稿 ──────────────────────────────
const promConfigs = [
  'infra/monitoring/prometheus.yml',
  'infra/monitoring/prometheus.compose.yml',
  'infra/monitoring/prometheus-full.yml'
]
  .map((p) => join(root, p))
  .filter((p) => existsSync(p));
if (promConfigs.length === 0) fail('找不到任何 prometheus 配置，门禁已失效');

const loadedBasenames = new Set();
for (const cfg of promConfigs) {
  for (const m of readFileSync(cfg, 'utf8').matchAll(/- \/etc\/prometheus\/([A-Za-z0-9_.-]+)/g)) {
    loadedBasenames.add(m[1]);
  }
}

const problems = [];
const draftFiles = new Set();
let refCount = 0;
for (const f of ruleFiles) {
  const head = readFileSync(f, 'utf8').split('\n').slice(0, 12).join('\n');
  const isDraft = head.includes(DRAFT_MARK);
  const loaded = loadedBasenames.has(f.split(/[\\/]/).pop());
  if (isDraft) {
    draftFiles.add(f);
    if (loaded) {
      problems.push(
        `${rel(f)} 既标了 \`${DRAFT_MARK}\` 又出现在 prometheus rule_files 里 —— 草稿不得被加载`
      );
    }
    continue;
  }
  if (!loaded) {
    problems.push(
      `${rel(f)}：含告警规则，但**没有被任何 prometheus 配置的 rule_files 引用**（改配置也不会生效）` +
        `—— 要么挂上，要么在文件头加 \`${DRAFT_MARK}\` 显式声明为未实现草稿`
    );
    continue;
  }
  // R2：被加载的规则文件 → 校验指标名
  for (const { text, line } of exprsOfYaml(f)) {
    for (const name of metricsIn(text)) {
      refCount++;
      if (!isKnownMetric(name)) {
        problems.push(
          `${rel(f)}:${line} 引用了不存在的指标 \`${name}\`（代码里无对应 Micrometer 注册点）—— 该规则永不触发`
        );
      }
    }
  }
}

// ── R3：看板与运维页 ─────────────────────────────────────────────────────
const dashboards = walk(join(root, 'infra/monitoring/grafana/provisioning/dashboards/json'), (n) =>
  n.endsWith('.json')
);
for (const f of dashboards) {
  const raw = readFileSync(f, 'utf8');
  // ⚠️ 看板 JSON 里 expr 内的引号是**转义的**（如 `{\"result\":\"success\"}`）。若用 `"([^"]+)"` 捕获，
  // 会在第一个 `\"` 处截断，把 `…{result=` 这种残片喂给 metricsIn —— 左花括号没闭合 ⇒ 花括号剥离失效 ⇒
  // **标签名被当成指标名**误报（历史面板只因 `result/state/status` 恰好在关键字白名单里才没暴露）。
  // 故按「非引号 or 转义序列」整体捕获后再反转义，让 expr 完整进入 metricsIn。
  for (const m of raw.matchAll(/"expr"\s*:\s*"((?:[^"\\]|\\.)+)"/g)) {
    const expr = m[1].replace(/\\(.)/g, '$1');
    for (const name of metricsIn(expr)) {
      refCount++;
      if (!isKnownMetric(name)) problems.push(`${rel(f)} 面板引用了不存在的指标 \`${name}\``);
    }
  }
}

const adminViews = walk(join(root, 'clients/admin-vue/src'), (n) => /\.(vue|ts)$/.test(n));
for (const f of adminViews) {
  const raw = readFileSync(f, 'utf8');
  for (const m of raw.matchAll(/expr:\s*(?:'([^']+)'|"([^"]+)")/g)) {
    const expr = m[1] ?? m[2];
    for (const name of metricsIn(expr)) {
      refCount++;
      if (!isKnownMetric(name)) problems.push(`${rel(f)} 引用了不存在的指标 \`${name}\``);
    }
  }
}

if (refCount < MIN_REFS) {
  fail(
    `只解析出 ${refCount} 处指标引用（期望 ≥ ${MIN_REFS}）：expr 锚点或目录结构可能已变，门禁已失去判别力`
  );
}

if (problems.length) {
  console.error(`${TAG} FAIL: 发现 ${problems.length} 处指标名/接线缺陷：`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}

console.log(
  `${TAG} OK: ${registered} 个注册点 → ${allowedSuffixes.size} 个有效指标名；` +
    `${ruleFiles.length} 个规则文件（草稿 ${draftFiles.size} 个）、${dashboards.length} 个看板、` +
    `${adminViews.length} 个 admin 视图的引用全部可解析`
);
