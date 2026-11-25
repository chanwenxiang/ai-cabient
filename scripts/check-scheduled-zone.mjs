/**
 * S-P2-7 门禁：@Scheduled(cron=...) 必须带 zone=（含属性占位），防日界用系统默认时区。
 *
 * 规则二（MA-406 实证补充）：**注解里的 cron 默认值必须与 application.yml 同一属性键的默认值一致**。
 * 成因：`@Scheduled(cron = "${aicabinet.reconciliation.scheduled-cron:0 30 10,14 * * *}")` 的
 * 注解默认只是「属性缺失时」的兜底；application.yml 写了 `scheduled-cron: ${RECON_SCHEDULED_CRON:0 30 1 * * *}`
 * ⇒ 属性**存在**，注解默认永远不生效 ⇒ 代码里改成 10:30、实际仍按 yml 的 01:30 触发，
 * 而运营台文案（V503 schedule_desc）又写 10:30 ⇒「页面说 A、实际跑 B」，日结对账连续失败 6 天才被发现。
 * 两处默认值本就该是同一个真值，故这里硬判：不一致即红。
 *
 * 规则三：已拍板的日任务必须用固定 cron；30 秒/1 分钟任务必须按契约错开 initialDelay，
 * 防止重启后恢复「每日时间漂移」和「同一秒集中触发」。
 *
 * 判据做成纯函数（`findCronDefaultMismatches` / `findTimingContractViolations` / `flattenYml` / `effectiveDefault`）供
 * `check-scheduled-zone.test.mjs` 用合成夹具钉住正负两向——没有夹具的判据改了就没人拦得住退化。
 *
 * 用法：node scripts/check-scheduled-zone.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { maskJavaComments } from './check-scheduled-task-watched.mjs';

const ROOTS = [
  path.resolve('services/trade-service/src/main/java'),
  path.resolve('services/device-service/src/main/java')
];

/** 参与「默认值对账」的 yml（只比基础档：profile 档是显式覆盖，允许不同）。 */
const BASELINE_YMLS = [
  path.resolve('services/trade-service/src/main/resources/application.yml'),
  path.resolve('services/device-service/src/main/resources/application.yml')
];

/** 匹配可能跨行的 @Scheduled(...)，要求含 cron= 时必须含 zone= */
const SCHEDULED_RE = /@Scheduled\s*\(([\s\S]*?)\)/g;
/** `cron = "${key:default}"`（default 可含空格，故用惰性匹配到首个 `}`） */
const CRON_PLACEHOLDER_RE = /cron\s*=\s*"\$\{([^:}]+)(?::([^}]*))?\}"/;
/** 值可能是 `${ENV:0 30 10,14 * * *}` 或裸值 */
const YML_PLACEHOLDER_RE = /^\$\{[^:}]+(?::([^}]*))?\}$/;

/** 已拍板的调度时刻契约：日任务用墙钟 cron，30 秒/1 分钟任务首次执行错峰。 */
export const TIMING_CONTRACTS = [
  { file: 'GrowthLogArchiveScheduler.java', method: 'archive', cron: '0 0 3 * * *' },
  { file: 'SkuReviewScheduler.java', method: 'scan', cron: '0 0 4 * * *' },
  { file: 'PointsExpiryScheduler.java', method: 'scan', cron: '0 5 0,6,12,18 * * *' },
  { file: 'CouponExpiryReminderScheduler.java', method: 'scan', cron: '0 5 0,6,12,18 * * *' },
  {
    file: 'ReconciliationScheduler.java',
    method: 'runDailyReconciliation',
    cron: '0 30 10,14 * * *'
  },
  { file: 'ScheduleZones.java', taskKey: 'reconciliation', xxlCron: '0 30 10,14 * * ?' },
  {
    file: 'CompensationTaskScheduler.java',
    method: 'processCompensationTasks',
    initialDelay: 5000
  },
  { file: 'ChargePendingReconciler.java', method: 'run', initialDelay: 10000 },
  { file: 'OpsExceptionScannerService.java', method: 'scan', initialDelay: 15000 },
  { file: 'DevicePresenceService.java', method: 'markStaleDevicesOffline', initialDelay: 20000 },
  { file: 'DeviceTempPlanService.java', method: 'scheduledApply', initialDelay: 25000 },
  {
    file: 'JiangyiProductStatePollScheduler.java',
    method: 'pollReviewingProducts',
    initialDelay: 30000
  },
  {
    file: 'ReplenishmentTimeoutScheduler.java',
    method: 'expireStaleCheckedInTasks',
    initialDelay: 35000
  },
  { file: 'SessionExpireService.java', method: 'expireStaleOpeningSessions', initialDelay: 40000 },
  {
    file: 'SessionExpireService.java',
    method: 'expireStaleRestockShoppingSessions',
    initialDelay: 45000
  },
  {
    file: 'SessionExpireService.java',
    method: 'expireStaleConsumerShoppingSessions',
    initialDelay: 50000
  },
  {
    file: 'SessionExpireService.java',
    method: 'expireStaleRecognizingSessions',
    initialDelay: 55000
  },
  { file: 'RefundRetryScheduler.java', method: 'run', initialDelay: 58000 }
];

function scheduledAttrsForMethod(src, method) {
  const methodMatch = new RegExp(`\\b${method}\\s*\\(`).exec(src);
  if (!methodMatch) return null;
  const before = src.slice(0, methodMatch.index);
  const matches = [...before.matchAll(/@Scheduled\s*\(([\s\S]*?)\)/g)];
  const scheduled = matches.at(-1);
  if (!scheduled) return null;
  const line = src.slice(0, scheduled.index).split('\n').length;
  return { attrs: scheduled[1], line };
}

function numericAttr(attrs, name) {
  const match = new RegExp(`\\b${name}\\s*=\\s*([0-9_]+)`).exec(attrs);
  return match ? Number(match[1].replaceAll('_', '')) : null;
}

/** 校验固定时刻与启动错峰；返回空数组=绿。 */
export function findTimingContractViolations(javaSources) {
  const offenders = [];
  const seenInitialDelays = new Map();
  for (const contract of TIMING_CONTRACTS) {
    const src = javaSources.find((item) => path.basename(item.rel) === contract.file);
    if (!src) {
      offenders.push(
        `${contract.file} 不存在，无法核对 ${contract.method ?? contract.taskKey} 的调度契约`
      );
      continue;
    }
    if (contract.xxlCron != null) {
      const masked = maskJavaComments(src.text);
      const anchor = masked.indexOf('XXL_CRON_BY_TASK = Map.ofEntries(');
      const end = anchor < 0 ? -1 : masked.indexOf(');', anchor);
      const block = anchor < 0 || end < 0 ? '' : masked.slice(anchor, end);
      const actual = new RegExp(`Map\\.entry\\("${contract.taskKey}",\\s*"([^"]+)"\\)`).exec(
        block
      )?.[1];
      if (actual !== contract.xxlCron) {
        offenders.push(
          `${contract.file} XXL_CRON_BY_TASK[${contract.taskKey}] 应为 "${contract.xxlCron}"，实得 "${actual ?? '缺失'}"`
        );
      }
      continue;
    }
    const scheduled = scheduledAttrsForMethod(maskJavaComments(src.text), contract.method);
    if (!scheduled) {
      offenders.push(`${contract.file}:${contract.method} 未找到 @Scheduled`);
      continue;
    }
    const where = `${contract.file}:${scheduled.line} ${contract.method}`;
    if (contract.cron != null) {
      const rawCron = new RegExp(`\\bcron\\s*=\\s*"([^"]+)"`).exec(scheduled.attrs)?.[1];
      const cron = rawCron?.match(/^\$\{[^:}]+:([^}]*)\}$/)?.[1] ?? rawCron;
      if (cron !== contract.cron) {
        offenders.push(`${where} cron 应为 "${contract.cron}"，实得 "${cron ?? '缺失'}"`);
      }
      if (!/\bzone\s*=/.test(scheduled.attrs)) {
        offenders.push(`${where} cron 缺少 zone=`);
      }
    }
    if (contract.initialDelay != null) {
      const actual = numericAttr(scheduled.attrs, 'initialDelay');
      if (actual !== contract.initialDelay) {
        offenders.push(
          `${where} initialDelay 应为 ${contract.initialDelay}ms，实得 ${actual ?? '缺失'}`
        );
      }
      if (actual != null) {
        const previous = seenInitialDelays.get(actual);
        if (previous) {
          offenders.push(`${where} 与 ${previous} 的 initialDelay 相同（${actual}ms），未错峰`);
        } else {
          seenInitialDelays.set(actual, where);
        }
      }
    }
  }
  return offenders;
}

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, out);
      continue;
    }
    if (!entry.name.endsWith('.java')) continue;
    out.push(full);
  }
  return out;
}

/**
 * 极简 yml 展平：按缩进维护键栈，产出「点分键 → 标量值」。
 * 只需覆盖 application.yml 这类平坦的 properties 段；空行/整行注释忽略，` #` 之后当行内注释。
 */
export function flattenYml(text) {
  const out = new Map();
  const stack = [];
  for (const rawLine of text.split(/\r?\n/)) {
    if (!rawLine.trim() || rawLine.trim().startsWith('#')) continue;
    const indent = rawLine.match(/^ */)[0].length;
    const line = rawLine.trim();
    const idx = line.indexOf(':');
    if (idx <= 0) continue;
    const key = line.slice(0, idx).trim();
    let value = line.slice(idx + 1).trim();
    const hashIdx = value.indexOf(' #');
    if (hashIdx >= 0) value = value.slice(0, hashIdx).trim();
    while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
    stack.push({ indent, key });
    if (value === '') continue;
    out.set(stack.map((s) => s.key).join('.'), value.replace(/^["']|["']$/g, ''));
  }
  return out;
}

/** 取「真实生效的默认值」：`${ENV:x}` ⇒ x；裸值 ⇒ 原值；无法解析 ⇒ null（不猜）。 */
export function effectiveDefault(value) {
  if (value == null) return null;
  const m = YML_PLACEHOLDER_RE.exec(value.trim());
  if (!m) {
    return value.includes('${') ? null : value.trim();
  }
  return (m[1] ?? '').trim();
}

/**
 * 纯判定：给定 Java 源码与 yml 文本，返回违规定位清单（空数组=绿）。
 *
 * @param {{javaSources: Array<{rel: string, text: string}>, ymlSources: Array<{rel?: string, text: string}>}} input
 */
export function findCronDefaultMismatches(input) {
  const offenders = [];
  const ymlDefaults = new Map();
  for (const yml of input.ymlSources ?? []) {
    for (const [key, value] of flattenYml(yml.text)) {
      const def = effectiveDefault(value);
      if (def != null) ymlDefaults.set(key, def);
    }
  }

  const javaDefaults = new Map();
  for (const src of input.javaSources ?? []) {
    let m;
    SCHEDULED_RE.lastIndex = 0;
    while ((m = SCHEDULED_RE.exec(src.text))) {
      const attrs = m[1];
      if (!/\bcron\s*=/.test(attrs)) continue;
      const line = src.text.slice(0, m.index).split(/\n/).length;
      const where = `${src.rel}:${line}`;
      if (!/\bzone\s*=/.test(attrs)) {
        offenders.push(`${where} @Scheduled(cron) missing zone=`);
      }
      const ph = CRON_PLACEHOLDER_RE.exec(attrs);
      if (!ph || ph[2] == null) continue;
      javaDefaults.set(ph[1].trim(), { def: ph[2].trim(), where });
    }
  }

  for (const [key, java] of javaDefaults) {
    const ymlDef = ymlDefaults.get(key);
    // yml 未声明该键 ⇒ 注解默认就是真值，无需对账
    if (ymlDef == null) continue;
    if (ymlDef !== java.def) {
      offenders.push(
        `${java.where} cron 默认值与 application.yml 不一致：${key}\n` +
          `    @Scheduled 默认 = "${java.def}"\n` +
          `    application.yml 默认 = "${ymlDef}"  ← 属性存在 ⇒ 注解默认永不生效，实际按 yml 跑`
      );
    }
  }
  return offenders;
}

const isCli =
  process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));
if (isCli) {
  const javaSources = ROOTS.flatMap((root) => walk(root)).map((file) => ({
    rel: path.relative(process.cwd(), file).replace(/\\/g, '/'),
    text: fs.readFileSync(file, 'utf8')
  }));
  const ymlSources = BASELINE_YMLS.filter((p) => fs.existsSync(p)).map((p) => ({
    rel: path.relative(process.cwd(), p).replace(/\\/g, '/'),
    text: fs.readFileSync(p, 'utf8')
  }));
  const offenders = findCronDefaultMismatches({ javaSources, ymlSources });
  offenders.push(...findTimingContractViolations(javaSources));
  if (offenders.length) {
    console.error('S-P2-7 scheduled zone / cron-default check failed:\n' + offenders.join('\n'));
    process.exit(1);
  }
  console.log(
    `check-scheduled-zone: ok（${javaSources.length} 个 Java 源，${ymlSources.length} 个基础 yml）`
  );
}
