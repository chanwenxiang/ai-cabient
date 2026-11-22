#!/usr/bin/env node
/**
 * 门禁：状态机「条件写入」类级棘轮（MA-396，F5 家族升级）。
 *
 * 背景：F5（状态机条件写入）此前只有实例级钉死（check-status-cas 钉 5 个文件 9 处），
 * 实测 trade-service 另有大量服务在钉死集外调 `updateById(`。同族缺陷已复发过一次：
 * MA-388 的「无条件 SET version=null 撤戳覆盖并发复核」就发生在覆盖外文件，靠人工复核
 * 抓到，不是靠门禁。本门禁把覆盖面升级为**全 trade-service 类级棘轮**。
 *
 * 判据：非控制器 Java 的方法体（花括号配平提取、先脱敏注释）里，
 * 「状态/版本突变（setStatus/setState/setVersion…）与落库写（updateById/update/…）共现」
 * 即记为一个**嫌疑站点**。站点必须登记在 `scripts/status-write-cas-baseline.json`
 * （键=相对路径#方法名，行号漂移免疫），且：
 *   - 新站点不在基线 ⇒ 红（要么改成条件 UPDATE WHERE status=快照态 / UPDATE … version 谓词，
 *     要么带 why 显式登记「锁内读改写」等安全形态）；
 *   - 修复后站点从代码消失但基线还挂着 ⇒ 红（基线只减不增，修掉必须摘牌）。
 *
 * 基线 2026-11-22 自 HEAD 种子（64 站点/32 文件），存量逐点复核（补 why/改条件写）按
 * ISSUES.md MA-396 派工后批推进；本门禁只拦**增量**。
 *
 * 已知边界（有意取舍）：方法名键对重载不区分（同方法多站点合一，why 里写明）；
 * 匿名类体按伪方法计入（宁滥勿漏）；本判据不区分「锁内读改写」与真·无条件写——
 * 两者都进基线显式化，语义复核留在后批。
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SCAN_DIR = join(ROOT, 'services/trade-service/src/main/java');
const BASELINE = join(ROOT, 'scripts', 'status-write-cas-baseline.json');
const TAG = '[check-status-write-cas]';
const MIN_FILES = 800;

const STATUS_MUT = /\.(?:set(?:\w*?)?Status|setState|setVersion)\s*\(/;
const WRITE = /\.(?:updateById|update|updateAllColumnById)\s*\(/;

function* walk(dir) {
  if (!existsSync(dir)) return;
  for (const name of readdirSync(dir)) {
    if (name === 'target' || name === 'node_modules') continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) yield* walk(p);
    else if (name.endsWith('.java')) yield p;
  }
}

export function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
}

/** 花括号配平提取方法体；签名行捕获方法名（new 匿名体记 <anonymous>:行号）。 */
export function methodSites(clean, relPath) {
  const lines = clean.split('\n');
  const sites = [];
  const depthDelta = (s) => {
    let d = 0;
    for (const ch of s) {
      if (ch === '{') d++;
      else if (ch === '}') d--;
    }
    return d;
  };
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    const ctrl = /^\s*(?:if|for|while|switch|try|catch|else|finally|do|synchronized)\b/.test(line);
    const m = /\b(\w+)\s*\([^;{}]*?\)\s*(?:throws\s[\w.,\s]+?)?\{/.exec(line);
    const hasBrace = line.includes('{');
    if (hasBrace && !ctrl && m) {
      const isAnon = /\bnew\s+[\w<>.[\],\s]+\(/.test(line.slice(0, m.index));
      const name = isAnon ? `<anonymous>:${i + 1}` : m[1];
      const net = depthDelta(line);
      if (net === 0) {
        // 单行方法：签名+体同行，净平衡即闭合
        sites.push({ key: `${relPath}#${name}`, line: i + 1, body: line + '\n' });
        i++;
        continue;
      }
      let depth = 0;
      let buf = '';
      let j = i;
      for (; j < lines.length; j++) {
        buf += lines[j] + '\n';
        depth += depthDelta(lines[j]);
        if (depth === 0 && buf.includes('{')) break;
      }
      sites.push({ key: `${relPath}#${name}`, line: i + 1, body: buf });
      i = j + 1;
      continue;
    }
    i++;
  }
  return sites;
}

export function suspectSites(src, relPath) {
  const clean = stripComments(src);
  return methodSites(clean, relPath)
    .filter((s) => STATUS_MUT.test(s.body) && WRITE.test(s.body))
    .map((s) => ({ key: s.key, line: s.line }));
}

function main() {
  const baseline = JSON.parse(readFileSync(BASELINE, 'utf8'));
  const registered = baseline.sites || {};
  const problems = [];
  let files = 0;
  const liveKeys = new Set();
  for (const p of walk(SCAN_DIR)) {
    const rel = relative(ROOT, p).replaceAll('\\', '/');
    files++;
    for (const s of suspectSites(readFileSync(p, 'utf8'), rel)) {
      liveKeys.add(s.key);
      if (!Object.prototype.hasOwnProperty.call(registered, s.key)) {
        problems.push(
          `新嫌疑站点未登记：${s.key}（第 ${s.line} 行附近）——状态/版本突变与 updateById/update 共现。` +
            `改成条件写入（UPDATE … WHERE status=<快照态> / version 谓词），或在 ` +
            `${relative(ROOT, BASELINE).replaceAll('\\', '/')} 以 why 显式登记安全形态（如锁内读改写）`
        );
      }
    }
  }
  if (files < MIN_FILES) {
    console.error(`${TAG} FAIL：只扫到 ${files} 个 Java 文件，扫描面塌陷`);
    process.exit(1);
  }
  // 棘轮只减不增：代码里已消失的基线站点必须摘牌
  for (const key of Object.keys(registered)) {
    if (!liveKeys.has(key)) {
      problems.push(
        `基线站点已消失但未摘牌：${key}——修复后请把它从基线删除（棘轮只减不增；若是改名请同步更新键）`
      );
    }
  }
  // why 必须非空（MA-387 同款：条目自述语义，不做哑登记）
  for (const [key, v] of Object.entries(registered)) {
    if (!v || typeof v.why !== 'string' || v.why.trim().length < 4) {
      problems.push(`基线站点缺 why：${key}（哑登记等于没登记）`);
    }
  }
  if (problems.length) {
    console.error(`${TAG} FAIL：${problems.length} 项：`);
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log(
    `${TAG} OK：${files} 个 Java 文件、${liveKeys.size} 个嫌疑站点全部在基线（基线 ${Object.keys(registered).length} 条，只减不增）`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
