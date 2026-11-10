import { describe, expect, it } from 'vitest';
import type { ReplenishDraftLine } from './self-replenish-draft';
import {
  applyAdjustDraftQty,
  applyToggleDraftLine,
  buildSelfReplenishBody,
  canStartSelfReplenish,
  decidePrepResubmitWithRefresh,
  newSelfReplenishRequestNo,
  nextPrepRequestNo,
  nextRequestNoAfterSubmit,
  prepSubmitFingerprint,
  requestActionErrorMessage,
  selectedReplenishLines,
  shouldBlockPrepResubmit,
  takePrepRequestNo
} from './self-replenish-submit';

function line(partial: Partial<ReplenishDraftLine>): ReplenishDraftLine {
  return {
    skuId: 'S1',
    skuName: '可乐',
    currentQty: 0,
    capacity: 10,
    suggestQty: 2,
    soldQty7d: 0,
    suggestReason: '',
    qty: 0,
    selected: false,
    ...partial
  };
}

describe('self-replenish-submit', () => {
  it('选中行 → 提交明细', () => {
    expect(
      selectedReplenishLines([
        line({ selected: true, qty: 3 }),
        line({ skuId: 'S2', selected: true, qty: 0 }),
        line({ skuId: 'S3', selected: false, qty: 5 })
      ])
    ).toEqual([{ skuId: 'S1', qty: 3 }]);
  });

  it('提交 body 与门闩', () => {
    expect(
      buildSelfReplenishBody({
        deviceId: 'D1',
        notes: '  ',
        lines: [{ skuId: 'S1', qty: 1 }],
        clientRequestNo: 'self-abc-00000001'
      })
    ).toEqual({
      deviceId: 'D1',
      notes: undefined,
      lines: [{ skuId: 'S1', qty: 1 }],
      clientRequestNo: 'self-abc-00000001'
    });
    expect(
      buildSelfReplenishBody({
        deviceId: 'D1',
        notes: ' 优先可乐 ',
        lines: [],
        clientRequestNo: 'self-abc-00000002'
      }).notes
    ).toBe('优先可乐');
    expect(canStartSelfReplenish({ canSubmit: true, submitting: false, lineCount: 1 })).toBe('ok');
    expect(canStartSelfReplenish({ canSubmit: true, submitting: false, lineCount: 0 })).toBe(
      'no_lines'
    );
    expect(canStartSelfReplenish({ canSubmit: false, submitting: true, lineCount: 1 })).toBe(
      'blocked'
    );
  });

  it('切换/调数量', () => {
    const a = line({ suggestQty: 4 });
    expect(applyToggleDraftLine(a)).toBeNull();
    expect(a.selected).toBe(true);
    expect(a.qty).toBe(4);
    const b = line({ suggestQty: 0 });
    expect(applyToggleDraftLine(b)).toBe('请填写补货数量');
    applyAdjustDraftQty(a, -10);
    expect(a.qty).toBe(0);
    expect(a.selected).toBe(false);
    expect(requestActionErrorMessage(new Error('x'), 'f')).toBe('x');
  });

  it('幂等键：生成 self- 前缀、随机尾确定性注入', () => {
    let seq = 0;
    const rand = () => (seq === 0 ? 0 : 0.5);
    const no1 = newSelfReplenishRequestNo(1730000000000, rand);
    expect(no1.startsWith('self-')).toBe(true);
    expect(no1.endsWith('00000000')).toBe(true);
    // 同参数确定性：同 rand ⇒ 同键；不同 rand ⇒ 不同尾
    seq = 1;
    const no2 = newSelfReplenishRequestNo(1730000000000, rand);
    expect(no2).not.toBe(no1);
    expect(newSelfReplenishRequestNo(1730000000000, rand)).toBe(no2);
    // 不同时刻 ⇒ 不同键
    seq = 0;
    expect(newSelfReplenishRequestNo(1730000000001, rand)).not.toBe(no1);
    // 长度远小于后端 64 上限
    expect(no1.length).toBeLessThan(64);
  });

  it('幂等键重试语义：仅传输类错误保留键（重放），成功/业务失败清空（新意图）', () => {
    expect(nextRequestNoAfterSubmit('self-abc-00000001', 'transport_error')).toBe(
      'self-abc-00000001'
    );
    expect(nextRequestNoAfterSubmit('self-abc-00000001', 'success')).toBe('');
    expect(nextRequestNoAfterSubmit('self-abc-00000001', 'business_error')).toBe('');
  });

  // P0-3（2026-11-10）：备货单一次提交多柜，成功后清键会让「部分失败重提」重复建任务。
  it('备货单幂等键：页面级 store 同柜复用，成功/传输未知保留，仅业务失败清空', () => {
    const store = new Map<string, string>();
    let n = 0;
    const gen = () => `self-test-${++n}`;
    const first = takePrepRequestNo(store, 'D1', gen);
    expect(first).toBe('self-test-1');
    // 同柜再取：复用既有键（重试=同一意图），不生成新键
    expect(takePrepRequestNo(store, 'D1', gen)).toBe(first);
    // 不同柜独立键，设备间不互相重放
    expect(takePrepRequestNo(store, 'D2', gen)).toBe('self-test-2');
    // 成功 / 传输未知 ⇒ 保留（服务端按同键重放原任务）
    expect(nextPrepRequestNo(first, 'success')).toBe(first);
    expect(nextPrepRequestNo(first, 'transport_error')).toBe(first);
    // 服务端明确业务失败 ⇒ 清空（下次提交是新意图）
    expect(nextPrepRequestNo(first, 'business_error')).toBe('');
    // 清空后同柜再取必须换新键
    store.delete('D1');
    expect(takePrepRequestNo(store, 'D1', gen)).toBe('self-test-3');
  });

  // P0-3 残留（2026-11-10）：后端按 key 回放不核对明细，改量重提会被旧键静默回放。
  // 指纹必须对「数量/SKU 变化」敏感、对「行序」不敏感，页面据此显式拦截改量重提。
  it('备货明细指纹：行序无关，数量或 SKU 变化即不同', () => {
    const a = [
      { skuId: 'S1', qty: 3 },
      { skuId: 'S2', qty: 5 }
    ];
    const reordered = [
      { skuId: 'S2', qty: 5 },
      { skuId: 'S1', qty: 3 }
    ];
    expect(prepSubmitFingerprint(a)).toBe(prepSubmitFingerprint(reordered));
    expect(prepSubmitFingerprint(a)).toBe(prepSubmitFingerprint([...a]));
    // 改量 ⇒ 指纹变化（触发拦截）
    expect(
      prepSubmitFingerprint([
        { skuId: 'S1', qty: 4 },
        { skuId: 'S2', qty: 5 }
      ])
    ).not.toBe(prepSubmitFingerprint(a));
    // 换 SKU ⇒ 指纹变化
    expect(
      prepSubmitFingerprint([
        { skuId: 'S3', qty: 3 },
        { skuId: 'S2', qty: 5 }
      ])
    ).not.toBe(prepSubmitFingerprint(a));
    // 空明细有确定指纹（整柜全 0 的柜在页面层已剔除，这里只钉函数行为）
    expect(prepSubmitFingerprint([])).toBe('');
  });

  // R2-B1 残留（2026-11-10）：「改量重提」的拦截只在**那张任务还活着**时成立。
  // 任务已取消/已完成时继续拦，会把用户锁死（提示去任务页改量，而任务页里没有可改的任务）。
  it('改量重提判据：本页没提交过/量没变 ⇒ 放行；任务进行中 ⇒ 拦；任务已结束 ⇒ 作废旧键重提', () => {
    // 本页没成功提交过：没有可回放的旧键，直接提交
    expect(
      shouldBlockPrepResubmit({
        prevFingerprint: undefined,
        fingerprint: 'S1:3',
        lastTaskStatus: null
      })
    ).toBe('proceed');
    // 量没变 = 同一意图：复用旧键由后端回放是正确的
    expect(
      shouldBlockPrepResubmit({
        prevFingerprint: 'S1:3',
        fingerprint: 'S1:3',
        lastTaskStatus: 'PENDING'
      })
    ).toBe('proceed');
    // 改了量、任务还在进行中 ⇒ 拦（复用旧键会静默回放旧数量）
    expect(
      shouldBlockPrepResubmit({
        prevFingerprint: 'S1:3',
        fingerprint: 'S1:4',
        lastTaskStatus: 'PENDING'
      })
    ).toBe('block_changed_with_active_task');
    expect(
      shouldBlockPrepResubmit({
        prevFingerprint: 'S1:3',
        fingerprint: 'S1:4',
        lastTaskStatus: 'IN_PROGRESS'
      })
    ).toBe('block_changed_with_active_task');
    // 🔴 状态未知（预览是提交前加载的，那时本柜还没任务）必须按「还在进行中」拦——
    // 按 null 放行会把 P0-3 的修复整个废掉（重复建任务 + 重复扣分仓库存）
    expect(
      shouldBlockPrepResubmit({
        prevFingerprint: 'S1:3',
        fingerprint: 'S1:4',
        lastTaskStatus: null
      })
    ).toBe('block_changed_with_active_task');
    expect(
      shouldBlockPrepResubmit({
        prevFingerprint: 'S1:3',
        fingerprint: 'S1:4',
        lastTaskStatus: 'SOMETHING_NEW'
      })
    ).toBe('block_changed_with_active_task');
    // 任务已结束（取消/完成/失败）⇒ 作废旧键、按新意图真提一次
    for (const ended of ['CANCELLED', 'COMPLETED', 'FAILED', 'REJECTED']) {
      expect(
        shouldBlockPrepResubmit({
          prevFingerprint: 'S1:3',
          fingerprint: 'S1:4',
          lastTaskStatus: ended
        })
      ).toBe('renew_after_ended_task');
    }
    // 大小写与空白不影响判据（后端状态字面量偶有大小写差异时不该把用户锁死）
    expect(
      shouldBlockPrepResubmit({
        prevFingerprint: 'S1:3',
        fingerprint: 'S1:4',
        lastTaskStatus: ' cancelled '
      })
    ).toBe('renew_after_ended_task');
  });

  // 提交时实时复查（收口「预览后、提交前被取消」的快照竞态）：只在快照判拦分支复查，
  // 新鲜状态终态 ⇒ 作废重提；仍进行中 ⇒ 维持拦截；复查失败 ⇒ fail-closed 维持拦截。
  it('改量重提实时复查：快照判拦后按新鲜状态分流，失败 fail-closed', async () => {
    const base = { prevFingerprint: 'S1:3', fingerprint: 'S1:4', lastTaskStatus: 'PENDING' };
    // 快照说进行中，但提交瞬间任务已被取消 ⇒ 作废旧键重提
    expect(await decidePrepResubmitWithRefresh(base, 'D1', async () => 'CANCELLED')).toBe(
      'renew_after_ended_task'
    );
    // 新鲜状态仍进行中 ⇒ 维持拦截
    expect(await decidePrepResubmitWithRefresh(base, 'D1', async () => 'IN_PROGRESS')).toBe(
      'block_changed_with_active_task'
    );
    // 复查抛错（网络）⇒ 不静默放行，维持原拦截
    expect(
      await decidePrepResubmitWithRefresh(base, 'D1', async () => {
        throw new Error('network');
      })
    ).toBe('block_changed_with_active_task');
    // 快照本就放行/终态 ⇒ 不触发复查（refresh 不被调用）
    let called = 0;
    const spy = async () => {
      called += 1;
      return 'CANCELLED';
    };
    expect(
      await decidePrepResubmitWithRefresh(
        { prevFingerprint: undefined, fingerprint: 'S1:4', lastTaskStatus: null },
        'D1',
        spy
      )
    ).toBe('proceed');
    expect(
      await decidePrepResubmitWithRefresh(
        { prevFingerprint: 'S1:3', fingerprint: 'S1:4', lastTaskStatus: 'COMPLETED' },
        'D1',
        spy
      )
    ).toBe('renew_after_ended_task');
    expect(called).toBe(0);
  });
});
