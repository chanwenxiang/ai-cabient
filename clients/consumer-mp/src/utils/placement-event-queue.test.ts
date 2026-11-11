import { describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_MAX_PENDING,
  createPlacementEventQueue,
  placementEventKey,
  type PlacementEventBody
} from './placement-event-queue';

function sample(overrides: Partial<PlacementEventBody> = {}): PlacementEventBody {
  return {
    position: 'POPUP_BEFORE_OPEN',
    activityId: 7,
    eventType: 'IMPRESSION',
    ...overrides
  };
}

describe('placementEventKey', () => {
  it('同事件同键，任一字段不同即不同键', () => {
    expect(placementEventKey(sample())).toBe(placementEventKey(sample()));
    expect(placementEventKey(sample({ eventType: 'CLICK' }))).not.toBe(placementEventKey(sample()));
    expect(placementEventKey(sample({ position: 'BANNER' }))).not.toBe(placementEventKey(sample()));
    expect(placementEventKey(sample({ activityId: 8 }))).not.toBe(placementEventKey(sample()));
  });

  it('可选 id 缺失不会撞上 id=0（空串占位而非 falsy 折叠）', () => {
    const noId = sample({ activityId: undefined, campaignId: 3 });
    const zeroId = sample({ activityId: 0, campaignId: 3 });
    expect(placementEventKey(noId)).not.toBe(placementEventKey(zeroId));
  });
});

describe('未登录暂存 / 登录后补发', () => {
  it('未登录时 report 不发请求，只入队', () => {
    const send = vi.fn(async () => undefined);
    const q = createPlacementEventQueue({ isLoggedIn: () => false, send });

    q.report(sample());

    expect(send).not.toHaveBeenCalled();
    expect(q.pendingCount()).toBe(1);
  });

  it('已登录时 report 立即发送，不入队（首屏之后的正常路径）', () => {
    const send = vi.fn(async () => undefined);
    const q = createPlacementEventQueue({ isLoggedIn: () => true, send });

    q.report(sample());

    expect(send).toHaveBeenCalledTimes(1);
    expect(q.pendingCount()).toBe(0);
  });

  it('登录成功后 flush 按入队顺序补发，发完清空', async () => {
    let loggedIn = false;
    const sent: PlacementEventBody[] = [];
    const q = createPlacementEventQueue({
      isLoggedIn: () => loggedIn,
      send: async (body) => {
        sent.push(body);
      }
    });

    q.report(sample({ eventType: 'IMPRESSION' }));
    q.report(sample({ eventType: 'CLICK' }));
    expect(q.pendingCount()).toBe(2);

    loggedIn = true;
    await expect(q.flush()).resolves.toBe(2);

    expect(sent.map((b) => b.eventType)).toEqual(['IMPRESSION', 'CLICK']);
    expect(q.pendingCount()).toBe(0);
  });

  it('flush 时仍未登录：一条都不发，队列保留（登录失败/被拒）', async () => {
    const send = vi.fn(async () => undefined);
    const q = createPlacementEventQueue({ isLoggedIn: () => false, send });
    q.report(sample());

    await expect(q.flush()).resolves.toBe(0);
    expect(send).not.toHaveBeenCalled();
    expect(q.pendingCount()).toBe(1);
  });

  it('同一事件重复 report 只占一个槽位', () => {
    const q = createPlacementEventQueue({ isLoggedIn: () => false, send: async () => undefined });

    q.report(sample());
    q.report(sample());
    q.report(sample({ eventType: 'CLICK' }));

    expect(q.pendingCount()).toBe(2);
  });

  it('超出上限丢最旧的，保留最近的', async () => {
    let loggedIn = false;
    const sent: PlacementEventBody[] = [];
    const q = createPlacementEventQueue({
      isLoggedIn: () => loggedIn,
      send: async (body) => {
        sent.push(body);
      },
      maxSize: 2
    });

    q.report(sample({ activityId: 1 }));
    q.report(sample({ activityId: 2 }));
    q.report(sample({ activityId: 3 }));
    expect(q.pendingCount()).toBe(2);

    loggedIn = true;
    await q.flush();
    expect(sent.map((b) => b.activityId)).toEqual([2, 3]);
  });

  it('默认上限是 20 且可清零', () => {
    const q = createPlacementEventQueue({ isLoggedIn: () => false, send: async () => undefined });
    for (let i = 0; i < DEFAULT_MAX_PENDING + 5; i += 1) {
      q.report(sample({ activityId: i + 1 }));
    }
    expect(q.pendingCount()).toBe(DEFAULT_MAX_PENDING);
    q.clear();
    expect(q.pendingCount()).toBe(0);
  });
});

describe('失败与并发', () => {
  it('补发中途失败：保留失败那条及其后，已成功的出队，且留痕', async () => {
    let loggedIn = false;
    const errors: unknown[] = [];
    const send = vi
      .fn<(body: PlacementEventBody) => Promise<void>>()
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('429'));
    const q = createPlacementEventQueue({
      isLoggedIn: () => loggedIn,
      send,
      onSendError: (_b, err) => errors.push(err)
    });

    q.report(sample({ activityId: 1 }));
    q.report(sample({ activityId: 2 }));
    q.report(sample({ activityId: 3 }));

    loggedIn = true;
    await expect(q.flush()).resolves.toBe(1);

    expect(send).toHaveBeenCalledTimes(2);
    expect(q.pendingCount()).toBe(2);
    expect(errors).toHaveLength(1);

    // 网络恢复后重试，剩余两条应继续发出去（不因一次失败就丢事件）
    send.mockResolvedValue(undefined);
    await expect(q.flush()).resolves.toBe(2);
    expect(q.pendingCount()).toBe(0);
  });

  it('flush 并发调用只跑一轮，不重复上报', async () => {
    const gate = { loggedIn: false };
    const send = vi.fn(async () => undefined);
    const q = createPlacementEventQueue({ isLoggedIn: () => gate.loggedIn, send });
    q.report(sample({ activityId: 5 }));
    gate.loggedIn = true;

    const [a, b] = await Promise.all([q.flush(), q.flush()]);
    expect(a + b).toBe(1);
    expect(send).toHaveBeenCalledTimes(1);
  });
});
