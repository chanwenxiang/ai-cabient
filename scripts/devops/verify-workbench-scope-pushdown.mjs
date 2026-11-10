/**
 * check-workbench-scope-pushdown 门禁的负向验证（沙箱化，铁律 4 / 75 / 84）。
 *
 *   ⓪ 基线：**未注入**的沙箱副本必须绿 —— 否则后面的「红」可能只是拷贝不完整造成的假象；
 *   ① 红 A：把争议取数换回「全局窗口 + inDeviceScope 内存过滤」→ 必须非零退出且两条都报；
 *   ② 红 B：只把 `inDeviceScope(` 这个词搬回来（窗口取数没动）→ 必须红（判据独立成条）；
 *   ③ 红 C：删掉一个「带范围」取数入口（改用全局窗口名）→ 必须红（正向锚点生效）；
 *   ④ 绿：同样的违规写法只出现在**注释**里 → 必须保持绿（证明等长注释剥离有效）；
 *   ⑤ 零污染：真实树两个文件 md5 前后一致、沙箱目录已删。
 *
 * 🔴 不改真实源码：注入全在 `.tmp/wb-gate-<pid>` 沙箱副本上进行，真实树一个字节都不写
 *    —— 本仓常有并发会话的未提交改动，异常中断等于把注入态留在别人的工作树上。
 * 子进程走 `scripts/lib/async-spawn.mjs`（Windows 下同步 spawn 会返回 status=null）。
 *
 * 锚点失配 = 红（探针与源码脱钩，必须重新对准，不许放行）。
 * 用法：node scripts/devops/verify-workbench-scope-pushdown.mjs
 */
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';
import { runAsync } from '../lib/async-spawn.mjs';

const ROOT = resolve(import.meta.dirname, '..', '..');
const GATE = 'scripts/check-workbench-scope-pushdown.mjs';
const SERVICE_REL = 'services/trade-service/src/main/java/com/aicabinet/trade/service';
const FILES = ['MerchantWorkbenchQueryService.java', 'OpsWorkbenchQueryService.java'];

const SANDBOX = resolve(ROOT, '.tmp', `wb-gate-${process.pid}`);
const problems = [];

function md5(file) {
  return createHash('md5').update(readFileSync(file)).digest('hex');
}

function sandboxPath(name) {
  return join(SANDBOX, SERVICE_REL, name);
}

async function runGate(root) {
  return runAsync('node', [join(ROOT, GATE), `--root=${root}`], { cwd: ROOT });
}

function inject(name, from, to) {
  const file = sandboxPath(name);
  const before = readFileSync(file, 'utf8');
  if (!before.includes(from)) {
    problems.push(`锚点失配：${name} 里找不到注入锚点 —— 门禁探针与源码脱钩，重新对准后再跑`);
    return false;
  }
  writeFileSync(file, before.replace(from, to));
  return true;
}

function restore(name, original) {
  writeFileSync(sandboxPath(name), original);
}

async function main() {
  if (existsSync(SANDBOX)) rmSync(SANDBOX, { recursive: true, force: true });
  mkdirSync(join(SANDBOX, SERVICE_REL), { recursive: true });

  const realHashes = new Map(FILES.map((f) => [f, md5(join(ROOT, SERVICE_REL, f))]));
  const originals = new Map();
  for (const f of FILES) {
    const src = join(ROOT, SERVICE_REL, f);
    cpSync(src, sandboxPath(f));
    originals.set(f, readFileSync(src, 'utf8'));
  }

  // ⓪ 基线
  const baseline = await runGate(SANDBOX);
  if (baseline.status !== 0) {
    problems.push(
      `基线不绿（exit=${baseline.status}）：${(baseline.stderr || baseline.stdout).slice(0, 400)}`
    );
  }

  // ① 红 A：争议换回「窗口 + 内存过滤」
  const merchant = 'MerchantWorkbenchQueryService.java';
  if (
    inject(
      merchant,
      'disputeRepository.findByStatusAndDeviceIdsOrderByCreatedAtDesc("OPEN", bizDeviceIds, WORKBENCH_ITEM_CAP)',
      'disputeRepository.findByStatusOrderByCreatedAtDesc("OPEN", WORKBENCH_ITEM_CAP).stream()\n' +
        '                .filter(d -> inDeviceScope(bizDeviceIds, d.getSessionDeviceId()))'
    )
  ) {
    const r = await runGate(SANDBOX);
    const msg = `${r.stderr || ''}${r.stdout || ''}`;
    const hits = ['inDeviceScope(', 'disputeRepository.findByStatusOrderByCreatedAtDesc('].filter(
      (k) => msg.includes(k)
    );
    if (r.status === 0) problems.push('红 A 未生效：窗口取数 + 内存过滤被放行了');
    else if (hits.length < 2) problems.push(`红 A 报得不够：只命中 ${JSON.stringify(hits)}`);
    restore(merchant, originals.get(merchant));
  }

  // ② 红 B：只把 inDeviceScope 搬回来
  const ops = 'OpsWorkbenchQueryService.java';
  if (
    inject(
      ops,
      'deviceRepository.findDeployedByDeviceIdInAndOnlineStatusNotOrderByUpdatedAtAsc(',
      'deviceRepository.findDeployedByDeviceIdInAndOnlineStatusNotOrderByUpdatedAtAsc(\n' +
        '                        inDeviceScope(scopedDevices, "X") ? null : undefined,'
    )
  ) {
    const r = await runGate(SANDBOX);
    if (r.status === 0)
      problems.push('红 B 未生效：只要出现 inDeviceScope( 就该红，与窗口取数无关');
    restore(ops, originals.get(ops));
  }

  // ③ 红 C：删掉一个带范围的取数入口（换成全局窗口名）
  if (
    inject(
      ops,
      'splitRepository.findTopNByStatusAndDeviceIdInOrderByCreatedAtAsc(status, scopedDevices, 20)',
      'splitRepository.findTop20ByStatusOrderByCreatedAtAsc(status)'
    )
  ) {
    const r = await runGate(SANDBOX);
    const msg = `${r.stderr || ''}${r.stdout || ''}`;
    if (r.status === 0) problems.push('红 C 未生效：带范围入口被换回窗口取数仍判绿');
    else if (!msg.includes('findTopNByStatusAndDeviceIdInOrderByCreatedAtAsc')) {
      problems.push('红 C 没报到「正向锚点缺失」这一条 —— 只靠窗口判据命中的话，整段删除就会假绿');
    }
    restore(ops, originals.get(ops));
  }

  // ④ 绿：违规写法只写在注释里
  if (
    inject(
      ops,
      '    void collectDisputeActionItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {',
      '    void collectDisputeActionItems(Set<String> scopedDevices, List<OpsActionItemDto> items) {\n' +
        '        // 反例（历史写法）：disputeRepository.findByStatusOrderByCreatedAtDesc("OPEN", 20)\n' +
        '        //   .filter(d -> inDeviceScope(scopedDevices, d.getSessionDeviceId()))'
    )
  ) {
    const r = await runGate(SANDBOX);
    if (r.status !== 0) {
      problems.push(
        `注释免疫失效：注释里的反例把门禁打红了（必须 stripJavaComments 抹成等长空格）—— ` +
          `${(r.stderr || '').slice(0, 300)}`
      );
    }
    restore(ops, originals.get(ops));
  }

  // ⑤ 零污染
  for (const f of FILES) {
    const now = md5(join(ROOT, SERVICE_REL, f));
    if (now !== realHashes.get(f))
      problems.push(`真实树被写入：${f} 的 md5 从 ${realHashes.get(f)} 变成 ${now}`);
  }
  rmSync(SANDBOX, { recursive: true, force: true });
  if (existsSync(SANDBOX)) problems.push(`沙箱残留：${SANDBOX}`);

  if (problems.length) {
    console.error(`[verify-workbench-scope-pushdown] FAIL: ${problems.length} 项`);
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log(
    '[verify-workbench-scope-pushdown] OK：基线绿 → ①窗口+内存过滤红 → ②仅 inDeviceScope 红 → ' +
      '③带范围入口缺失红 → ④注释免疫绿；真实树 md5 未变、沙箱已清'
  );
}

main().catch((error) => {
  console.error('[verify-workbench-scope-pushdown] 异常：', error);
  rmSync(SANDBOX, { recursive: true, force: true });
  process.exit(1);
});
