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
 * 用法：node scripts/check-scheduled-zone.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const roots = [
  path.resolve('services/trade-service/src/main/java'),
  path.resolve('services/device-service/src/main/java')
];

/** 参与「默认值对账」的 yml（只比基础档：profile 档是显式覆盖，允许不同）。 */
const baselineYmls = [
  path.resolve('services/trade-service/src/main/resources/application.yml'),
  path.resolve('services/device-service/src/main/resources/application.yml')
];

const offenders = [];

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

/** 匹配可能跨行的 @Scheduled(...)，要求含 cron= 时必须含 zone= */
const scheduledRe = /@Scheduled\s*\(([\s\S]*?)\)/g;
/** `cron = "${key:default}"`（default 可含空格，故用惰性匹配到首个 `}`） */
const cronPlaceholderRe = /cron\s*=\s*"\$\{([^:}]+)(?::([^}]*))?\}"/;
/** defaultValue 里可能是 `${ENV:0 30 10,14 * * *}` 或裸值 */
const ymlPlaceholderRe = /^\$\{[^:}]+(?::([^}]*))?\}$/;

/**
 * 极简 yml 展平：按缩进维护键栈，产出「点分键 → 标量值」。
 * 只需覆盖 application.yml 这类平坦的 properties 段；行内注释与空行忽略，`#` 之后不当值。
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
    // 行内注释：值里出现 " #" 才当注释（cron 里的 # 是「第几个」语义，本仓不用）
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
  const m = ymlPlaceholderRe.exec(value.trim());
  if (!m) {
    return value.includes('${') ? null : value.trim();
  }
  return (m[1] ?? '').trim();
}

const ymlDefaults = new Map();
for (const yml of baselineYmls) {
  if (!fs.existsSync(yml)) continue;
  for (const [key, value] of flattenYml(fs.readFileSync(yml, 'utf8'))) {
    const def = effectiveDefault(value);
    if (def != null) ymlDefaults.set(key, def);
  }
}

const javaDefaults = new Map();
for (const file of roots.flatMap((r) => walk(r))) {
  const text = fs.readFileSync(file, 'utf8');
  let m;
  scheduledRe.lastIndex = 0;
  while ((m = scheduledRe.exec(text))) {
    const attrs = m[1];
    if (!/\bcron\s*=/.test(attrs)) continue;
    const line = text.slice(0, m.index).split(/\n/).length;
    const rel = path.relative(process.cwd(), file).replace(/\\/g, '/');
    if (!/\bzone\s*=/.test(attrs)) {
      offenders.push(`${rel}:${line} @Scheduled(cron) missing zone=`);
    }
    const ph = cronPlaceholderRe.exec(attrs);
    if (!ph || ph[2] == null) continue;
    javaDefaults.set(ph[1].trim(), { def: ph[2].trim(), where: `${rel}:${line}` });
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

if (offenders.length) {
  console.error('S-P2-7 scheduled zone / cron-default check failed:\n' + offenders.join('\n'));
  process.exit(1);
}
console.log(
  `check-scheduled-zone: ok（${javaDefaults.size} 个 cron 占位，${ymlDefaults.size} 条 yml 默认值）`
);
