#!/usr/bin/env node
/**
 * 重启策略门禁 —— 长驻服务**必须**声明 `restart`，且只认 `unless-stopped` / `always`。
 *
 * 背景（实证，非推演）
 * -------------------
 * R15-M1：base compose 里 postgres/redis/emqx/minio/prometheus/grafana 都有
 * `restart: unless-stopped`，**唯 gateway 与 redpanda 没有**。后果：宿主/Docker 重启后
 * 全站入口与 Kafka 不会自动拉起 —— 业务能跑的时候一切正常，出事的时刻恰是最需要它的时刻。
 * 修法只补了 base（docker-compose.yml），full 栈入口照旧裸奔：
 *
 *   R19-P3#12 实测复发：`infra/docker-compose.full.yml` 的 gateway 仍无 restart。
 *
 * 这就是「修一条、不加门禁」的代价：**同一处缺口在两轮审计里各出现一次**。
 *
 * 为什么不是「所有文件都要求」
 * --------------------------
 * infra/ 下大半文件是**叠加层**（win-ports 只改端口、uat-isolated 只改端口段、ha 只改副本），
 * 它们不负责服务的运行策略，要求它们也写 restart 只会逼人抄垃圾。
 * 故判据分两档：
 *
 *   A. 必备文件（REQUIRED_FILES）= 真正被 `docker compose up` 拉起的运行时编排：
 *      base / apps / full / production / xxljob。里面每个**非一次性**服务都必须声明 restart。
 *   B. 其余文件「自证入选」：文件里只要声明过 ≥1 个 restart，就说明它管运行策略，
 *      则其中每个非一次性服务同样必须声明 —— 挡的是「新增服务忘了抄这一行」。
 *
 * 一次性容器（名字以 `-init` 结尾，如 minio-init / redpanda-init）豁免：起来干完就退，
 * 重启策略对它无意义，写了反而会被当成常驻服务误读。
 *
 * 顺带守住「缩进错置」这个病根
 * ----------------------------
 * R15-M3 的 8 个 logging 块里有一块缩进错置成 `services:` 直接子节点（非法块），
 * 修的时候删错了块，于是 minio 至今没有日志轮转；同一手法在 apps.yml 又插了一个
 * 无归属的 `logging:` 块（R19-1，已让整条生产部署链 compose 校验失败）。
 * 所以本门禁在解析阶段就拒绝「services 下出现无归属的 4 空格键」——
 * 这类结构错误在 YAML 层面可能只是「多了一个名叫 logging 的服务」，肉眼极易放过。
 *
 * 防恒真
 * ------
 *   - 扫到的 compose 文件数 ≥ MIN_FILES、service 总数 ≥ MIN_SERVICES
 *   - REQUIRED_FILES 每个都必须真实存在（改名即红，而不是静默少查一个文件）
 *   - 全文 restart 声明数 ≥ MIN_RESTART_DECLS（解析逻辑脱节时不会「零命中即通过」）
 *
 * 用法
 * ----
 *   node scripts/check-compose-restart.mjs
 *   node scripts/check-compose-restart.mjs --list    # 打印 文件/服务/restart 全表
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = process.env.GATE_ROOT
  ? resolve(process.env.GATE_ROOT)
  : resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-compose-restart]';
const LIST = process.argv.includes('--list');

const fail = (msg) => {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
};

/** 运行时编排（被 up 拉起的主/备文件）：内部每个非一次性服务都得有 restart。 */
const REQUIRED_FILES = [
  'docker-compose.yml',
  'docker-compose.apps.yml',
  'docker-compose.full.yml',
  'docker-compose.production.yml',
  'docker-compose.xxljob.yml'
];

/** 一次性容器：干完就退，重启策略无意义。 */
const ONE_SHOT = /-init$/;

/** 只有这两个值能在「宿主/Docker 重启」后把服务拉回来；no / on-failure 都不行。 */
const OK_VALUES = new Set(['unless-stopped', 'always']);

const MIN_FILES = 8;
const MIN_SERVICES = 30;
const MIN_RESTART_DECLS = 25;

/**
 * 逐文件解析 service 块（缩进法，不引 YAML 依赖：CI 不保证有 docker，也不该为门禁装库）。
 * 返回 { services, structureErrors }。
 */
function parseServices(text) {
  const lines = text.split('\n');
  const services = [];
  const structureErrors = [];
  let inServices = false;
  let current = null;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const ln = i + 1;

    if (!inServices) {
      if (/^services:\s*$/.test(line)) inServices = true;
      continue;
    }
    if (/^[A-Za-z_]/.test(line)) break; // 顶层键（volumes: / networks: / x-…）⇒ services 段结束

    const svc = line.match(/^ {2}([A-Za-z0-9_.-]+):\s*$/);
    if (svc) {
      current = { name: svc[1], restart: null, line: ln, props: 0 };
      services.push(current);
      continue;
    }

    // 病根：services: 之下出现 4 空格键却没有归属的服务 ⇒ 某块缩进错置了。
    // （R19-1 的 apps.yml 就是这一形态：多了个名叫 logging 的服务，YAML 解析到此直接崩。）
    const orphan = line.match(/^ {4}([A-Za-z0-9_.-]+):\s*$/);
    if (orphan && current === null) {
      structureErrors.push(
        `第 ${ln} 行「${orphan[1]}:」缩进 4 空格却无归属服务 —— 典型的块缩进错置` +
          '（R15-M3/R19-1 同款），它会被当成一个名叫该键的服务，或让整份 compose 校验失败'
      );
      continue;
    }

    if (current) {
      if (/^ {4}[A-Za-z0-9_.-]+:/.test(line)) current.props++;
      const r = line.match(/^ {4}restart:\s*(.+?)\s*$/);
      if (r) current.restart = r[1].replace(/^["']|["']$/g, '');
    }
  }

  // 解析出「一个属性都没有」的服务，通常也是缩进错置的副产物
  for (const s of services) {
    if (s.props === 0)
      structureErrors.push(`服务 ${s.name}（第 ${s.line} 行）未解析到任何属性，疑似缩进错置`);
  }

  return { services, structureErrors };
}

const files = readdirSync(join(root, 'infra'))
  .filter((f) => /^docker-compose.*\.ya?ml$/.test(f))
  .sort();
if (files.length < MIN_FILES) {
  fail(
    `只找到 ${files.length} 个 infra/docker-compose*.yml（期望 ≥ ${MIN_FILES}）：命名规则可能已变`
  );
}

const missingRequired = REQUIRED_FILES.filter((f) => !files.includes(f));
if (missingRequired.length) {
  fail(
    `必备文件不见了：${missingRequired.join(', ')}　` +
      '⇒ 本门禁对它们的检查已静默失效（文件改名/删除？请同步 REQUIRED_FILES）'
  );
}

const problems = [];
const table = [];
let total = 0;
let restartDecls = 0;

for (const f of files) {
  const text = readFileSync(join(root, 'infra', f), 'utf8');
  const { services, structureErrors } = parseServices(text);
  if (!services.length) {
    fail(`${f} 里一个 service 都没解析到（anchor 漂了即失去判别力）`);
  }
  problems.push(...structureErrors.map((e) => `${f} :: ${e}`));

  // 档 B：文件自己声明过 restart ⇒ 它管运行策略 ⇒ 全量要求
  const optIn = services.some((s) => s.restart !== null) || REQUIRED_FILES.includes(f);

  for (const s of services) {
    total++;
    if (s.restart !== null) restartDecls++;
    if (ONE_SHOT.test(s.name)) continue; // 一次性容器豁免
    table.push({ file: f, name: s.name, restart: s.restart, scoped: optIn });

    if (s.restart === null) {
      if (optIn) {
        problems.push(
          `${f} :: ${s.name} 未声明 restart` +
            (REQUIRED_FILES.includes(f)
              ? '（运行时编排文件的常驻服务）'
              : '（同文件其它服务都声明了，唯它漏了）') +
            '　← 宿主/Docker 重启后不会自动拉起'
        );
      }
      continue;
    }
    if (!OK_VALUES.has(s.restart)) {
      problems.push(
        `${f} :: ${s.name} 的 restart=${s.restart}，只接受 unless-stopped / always` +
          '　← no / on-failure 在「Docker 守护进程重启」场景下不会拉起服务'
      );
    }
  }
}

if (total < MIN_SERVICES) {
  fail(`只解析出 ${total} 个 service（期望 ≥ ${MIN_SERVICES}）：解析逻辑可能已与文件结构脱节`);
}
if (restartDecls < MIN_RESTART_DECLS) {
  fail(
    `全文只解析到 ${restartDecls} 处 restart 声明（期望 ≥ ${MIN_RESTART_DECLS}）：` +
      '解析可能整体失配，此时"零缺失"是假绿'
  );
}

if (LIST) {
  for (const r of table) {
    console.log(
      `${String(r.restart ?? '(缺失)').padEnd(14)} ${r.scoped ? '必查' : '豁免'}  ${r.file}  ${r.name}`
    );
  }
  process.exit(0);
}

if (problems.length) {
  console.error(`${TAG} FAIL: ${problems.length} 处重启策略不合规：`);
  for (const p of problems) console.error(`  - ${p}`);
  console.error(
    `\n  判据：${REQUIRED_FILES.join(' / ')} 内每个常驻服务必须有 restart；` +
      '其余文件只要声明过一个 restart 就同样受查。\n' +
      '  一次性容器（*-init）豁免。取值只接受 unless-stopped / always。\n' +
      '  实证：R15-M1（base 漏 gateway/redpanda）→ R19 复发（full 栈 gateway）——入口不会自己回来。'
  );
  process.exit(1);
}

console.log(
  `${TAG} OK: ${files.length} 个 compose 文件、${total} 个 service 解析成功；` +
    `${REQUIRED_FILES.length} 个运行时编排 + 全部自证入选文件内，常驻服务均已声明 restart（${restartDecls} 处）`
);
