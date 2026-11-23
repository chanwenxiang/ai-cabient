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
 * 判据做成纯函数（`findCronDefaultMismatches` / `flattenYml` / `effectiveDefault`）供
 * `check-scheduled-zone.test.mjs` 用合成夹具钉住正负两向——没有夹具的判据改了就没人拦得住退化。
 *
 * 用法：node scripts/check-scheduled-zone.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

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
  if (offenders.length) {
    console.error('S-P2-7 scheduled zone / cron-default check failed:\n' + offenders.join('\n'));
    process.exit(1);
  }
  console.log(
    `check-scheduled-zone: ok（${javaSources.length} 个 Java 源，${ymlSources.length} 个基础 yml）`
  );
}
