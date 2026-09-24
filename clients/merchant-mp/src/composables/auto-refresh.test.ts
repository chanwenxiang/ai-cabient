import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createAutoRefresher,
  isOrderTerminal,
  isSettlementBatchTerminal,
  SETTLEMENT_BATCH_PENDING_STATUSES,
  SETTLEMENT_BATCH_TERMINAL_STATUSES
} from '@aicabinet/shared-uni/auto-refresh';
import { DICT } from '@aicabinet/shared-dict';

/**
 * 商家端接线侧的判据。
 *
 * 轮询器本体的纯逻辑单测在 `clients/consumer-mp/src/composables/auto-refresh.test.ts` ——
 * 两端共用同一份实现（`packages/shared-uni/src/auto-refresh.ts`，两端接线文件逐字节同源），
 * 所以这里**不重复**覆盖那 6 条反模式约束，只测商家端特有的三件事：
 *  ① 结算终态集合与 shared-dict 的双向一致性（防手写枚举静默 fail-open）
 *  ② 商家页面实际用到的终态判定真值表
 *  ③ 原语在商家侧确实能跑起来（防「共享包改了、商家端没跟上」这类静默失效）
 */
describe('结算终态集合 ↔ 字典一致性', () => {
  const dictKeys = Object.keys(DICT.settlement_batch_status);

  it('字典里的每个状态都必须被显式归类（终态或未终态）', () => {
    const covered = new Set<string>([
      ...SETTLEMENT_BATCH_TERMINAL_STATUSES,
      ...SETTLEMENT_BATCH_PENDING_STATUSES
    ]);
    const uncovered = dictKeys.filter((k) => !covered.has(k));
    // 🔴 字典新增状态时这里会红 ⇒ 逼人显式判断它算不算终态，
    //    否则 settlement 页会把它当成「未终态」永远轮询，或当成终态提前停表。
    expect(uncovered, `未归类状态：${uncovered.join(', ')}`).toEqual([]);
  });

  it('终态与未终态不得交叉', () => {
    const overlap = SETTLEMENT_BATCH_TERMINAL_STATUSES.filter((s) =>
      (SETTLEMENT_BATCH_PENDING_STATUSES as readonly string[]).includes(s)
    );
    expect(overlap).toEqual([]);
  });

  it('两个集合里不得出现字典中不存在的状态（防拼错）', () => {
    const all = [...SETTLEMENT_BATCH_TERMINAL_STATUSES, ...SETTLEMENT_BATCH_PENDING_STATUSES];
    expect(all.filter((s) => !dictKeys.includes(s))).toEqual([]);
  });
});

describe('isSettlementBatchTerminal', () => {
  it.each(['SETTLED', 'PAID', 'FAILED', 'PARTIAL_FAILED', 'COMPLETED'])('%s 算终态 ⇒ 停表', (s) =>
    expect(isSettlementBatchTerminal(s)).toBe(true)
  );

  it.each(['PENDING', 'PROCESSING'])('%s 未到终态 ⇒ 继续等后端', (s) =>
    expect(isSettlementBatchTerminal(s)).toBe(false)
  );

  it('大小写不敏感、两侧空白可容忍', () => {
    expect(isSettlementBatchTerminal(' settled ')).toBe(true);
  });

  it('空 / 未知状态一律视为未终态（宁可多轮询一拍，也不要漏掉推进）', () => {
    expect(isSettlementBatchTerminal('')).toBe(false);
    expect(isSettlementBatchTerminal(undefined)).toBe(false);
    expect(isSettlementBatchTerminal('SOMETHING_NEW')).toBe(false);
  });
});

describe('merchant 订单终态口径', () => {
  it.each(['PAID', 'COMPLETED', 'REFUNDED', 'PARTIAL_REFUNDED', 'CANCELLED', 'FAILED'])(
    '%s 是终态 ⇒ 停表',
    (s) => expect(isOrderTerminal(s)).toBe(true)
  );

  it.each(['PENDING', 'PROCESSING', 'DISPUTED'])('%s 仍在推进 ⇒ 继续轮询', (s) =>
    expect(isOrderTerminal(s)).toBe(false)
  );
});

describe('原语在商家端可用', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('批次从「结算中」推进到「已结算」：推进期间持续跟进，到终态立即停表', async () => {
    let status = 'PROCESSING';
    const load = vi.fn().mockResolvedValue(undefined);
    const r = createAutoRefresher({
      intervalMs: 1000,
      load,
      shouldContinue: () => !isSettlementBatchTerminal(status)
    });

    r.start();
    await vi.advanceTimersByTimeAsync(3000);
    expect(load).toHaveBeenCalledTimes(3);
    expect(r.running()).toBe(true);

    status = 'SETTLED';
    await vi.advanceTimersByTimeAsync(1000);
    expect(load).toHaveBeenCalledTimes(3); // 这一拍不再 load
    expect(r.running()).toBe(false);
    r.dispose();
  });
});
