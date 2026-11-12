#!/usr/bin/env node
/**
 * 门禁：infra/docker-compose*.yml 里的 `image:` 必须指向**真实可拉取**的引用。
 *
 * 成因（R19-16，2026-11-12）：`infra/docker-compose.prod-server.yml` 的 minio-init 写着
 * `image: minio/mc:server-pin` —— 起因是「跟服务端 pin 一致」这句**备注被当成了 tag**，
 * 而 minio/mc 没有这个 tag。后果不是「版本旧」而是「整段不执行」：生产 `docker compose up`
 * 拉不到 mc ⇒ minio-init 容器起不来 ⇒ 桶/生命周期策略从未初始化，而 compose 的失败
 * 藏在 pull 阶段，日志里只有一句 manifest unknown。
 *
 * 判据（任一满足即通过）：
 *   ① 钉 digest：`repo[:tag]@sha256:<64 hex>`（最稳，可复现）
 *   ② 带版本语义的 tag：tag 首字符是数字或 `v` 后接数字（16-alpine / 5.8.6 / v24.1.1 / 25.12.0.117093-community）
 *   ③ 变量 tag：含 `${...}`（由部署环境注入）
 *   ④ 基础变体白名单：alpine / slim / bookworm / bullseye / jammy（`nginx:alpine` 这类官方变体无数字）
 * 显式禁止：无 tag（等价 `:latest`）、`latest`、以及任何「看着像备注」的 tag。
 *
 * 用法：
 *   node scripts/check-compose-image-tags.mjs             # 检查真实 compose 文件
 *   node scripts/check-compose-image-tags.mjs --self-test # 负向自测（占位 tag 必须变红）
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const INFRA = 'infra';

/** 官方基础镜像的无版本变体 tag（egress 见文件头 ④） */
const BASE_VARIANTS = new Set(['alpine', 'slim', 'bookworm', 'bullseye', 'jammy']);

/**
 * 解析单份 compose 文本 → [{ line, ref, isVar, digest, tag }]
 * 注释行与不含 `image:` 的行不计。
 */
export function parseImageRefs(src) {
  const out = [];
  src.split(/\r?\n/).forEach((raw, i) => {
    const m = raw.match(/^\s*image:\s*("([^"]*)"|'([^']*)'|([^\s#]+))/);
    if (!m) return;
    const ref = (m[2] ?? m[3] ?? m[4] ?? '').trim();
    if (!ref) return;
    out.push({ line: i + 1, ...splitRef(ref) });
  });
  return out;
}

function splitRef(ref) {
  const at = ref.indexOf('@');
  const nameAndTag = at >= 0 ? ref.slice(0, at) : ref;
  const digest = at >= 0 ? ref.slice(at + 1) : null;
  const slash = nameAndTag.lastIndexOf('/');
  const colon = nameAndTag.lastIndexOf(':');
  const tag = colon > slash ? nameAndTag.slice(colon + 1) : null;
  return { ref, digest, tag, isVar: ref.includes('${') };
}

/** 单条引用是否合规 → null（通过）或失败理由。 */
export function judge(refInfo) {
  const { ref, digest, tag, isVar } = refInfo;
  if (digest != null) {
    if (!/^sha256:[0-9a-f]{64}$/.test(digest)) {
      return `digest 形态不对（应 sha256:64 位小写 hex）：${ref}`;
    }
    return null;
  }
  if (isVar) return null;
  if (tag == null) return `缺 tag（等价 :latest，不可复现）：${ref}`;
  if (tag === 'latest') return `浮动 tag :latest（不可复现）：${ref}`;
  if (/^v?\d/.test(tag)) return null;
  if (BASE_VARIANTS.has(tag)) return null;
  return `tag 不像真实版本号（疑为备注被当成 tag）：${ref}`;
}

function scanInfra() {
  const files = readdirSync(join(ROOT, INFRA))
    .filter((f) => /^docker-compose.*\.ya?ml$/.test(f))
    .sort();
  const bad = [];
  let checked = 0;
  for (const file of files) {
    const src = readFileSync(join(ROOT, INFRA, file), 'utf8');
    for (const entry of parseImageRefs(src)) {
      checked++;
      const why = judge(entry);
      if (why) bad.push(`infra/${file}:${entry.line}  ${why}`);
    }
  }
  return { files, checked, bad };
}

function selfTest() {
  const cases = [
    // [来源, 期望是否违规]
    ['    image: minio/mc:server-pin', true],
    ['    image: nginx', true],
    ['    image: nginx:latest', true],
    ['    image: postgres:16-alpine', false],
    ['    image: emqx/emqx:5.8.6@sha256:a1e3d10fa1dcc8c94325b815a93b5b5974253a97cb36470b44e47d5d3ef9cd13', false],
    ['    image: ai-cabinet/trade-service:${IMAGE_TAG:-local}', false],
    ['    image: nginx:alpine', false],
    ['    image: minio/mc@sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727 # 钉 digest', false],
    ['    image: foo/bar@sha256:zzz', true],
    ['    image: "sonarqube:25.12.0.117093-community"', false]
  ];
  let failed = 0;
  for (const [src, expectBad] of cases) {
    const entries = parseImageRefs(src);
    const actualBad = entries.length === 0 ? true : judge(entries[0]) != null;
    if (actualBad !== expectBad) {
      failed++;
      console.error(`  ✗ ${expectBad ? '应违规' : '应通过'}：${src}`);
    }
  }
  if (failed) {
    console.error(`check-compose-image-tags --self-test: ${failed} 项失败`);
    process.exit(1);
  }
  console.log(`check-compose-image-tags --self-test: ${cases.length} 例全部符合预期`
    + '（占用 tag / 缺 tag / latest / 坏 digest 必须红，版本 tag / 变量 / 基础变体 / digest 必须绿）');
}

if (process.argv.includes('--self-test')) {
  selfTest();
} else {
  // 自带负向自测：判据若被改坏（例如把「不像版本号」的分支删掉），门禁会在真实树全绿
  // 的情况下继续假绿——这是 compose 端口门禁曾经的形态。每跑都先自证一次（毫秒级）。
  selfTest();
  const { files, checked, bad } = scanInfra();
  if (bad.length) {
    console.error('[check-compose-image-tags] 以下镜像引用不可拉取或不可复现：');
    for (const b of bad) console.error('  - ' + b);
    console.error('修法：钉 digest（首选）或改成真实版本 tag；变量 tag 请写成 ${VAR:-default}。');
    process.exit(1);
  }
  console.log(`[check-compose-image-tags] OK：${checked} 条 image 引用全部可拉取（${files.length} 个 compose 文件）`);
}
