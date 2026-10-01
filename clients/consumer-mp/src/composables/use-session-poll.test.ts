import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useSessionPoll } from './use-session-poll';

/**
 * 会话轮询调度壳判据（C13 切四）。
 * 全部用假定时器验，不碰网络；onTick 由测试注入模拟成功/失败。
 * 文案断言钉 pollErrorMessage/localizeApiMessage 的现状（经 formatError 本地化）。
 */
describe('useSessionPoll', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  function setup(onTick: () => Promise<void>, failWarnAt = 3) {
    const onCapped = vi.fn();
    const onRefreshOk = vi.fn();
    const poll = useSessionPoll({
      getSessionId: () => 'S-1',
      onTick,
      pollMs: 1000,
      failWarnAt,
      onCapped,
      onRefreshOk
    });
    return { poll, onCapped, onRefreshOk };
  }

  it('start 立即拉一次；成功清空错误', async () => {
    const onTick = vi.fn().mockResolvedValue(undefined);
    const { poll } = setup(onTick);
    poll.startPoll();
    await vi.runOnlyPendingTimersAsync();
    // 立即拉一次 + pending 的首个 interval 触发（sinon 语义：pending 即触发一次）
    expect(onTick).toHaveBeenCalledTimes(2);
    expect(poll.pollError.value).toBe('');
    poll.stopPoll();
  });

  it('start 幂等：重复调用只保留一个定时器（每次 start 立即重拉一次）', async () => {
    const onTick = vi.fn().mockResolvedValue(undefined);
    const { poll } = setup(onTick);
    poll.startPoll();
    poll.startPoll();
    await vi.runOnlyPendingTimersAsync();
    expect(onTick).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(2000);
    // 两个周期各 1 次（无叠雪球）
    expect(onTick).toHaveBeenCalledTimes(4);
    poll.stopPoll();
  });

  it('onTick 抛错：失败有可见文案；连续网络错误升级 warnAt 文案', async () => {
    const onTick = vi
      .fn()
      .mockRejectedValueOnce(new Error('boom')) // 非网络：本地化兜底文案
      .mockRejectedValue(new Error('timeout of 8000ms exceeded')); // 网络
    const { poll } = setup(onTick, 3);
    poll.startPoll();
    await vi.runOnlyPendingTimersAsync();
    expect(poll.pollError.value).not.toBe('');

    // pending interval 已消耗 streak=2（< warnAt）：网络波动短文案
    expect(poll.pollError.value).toBe('网络波动，正在重试…');
    // streak=3（≥ warnAt）：升级长文案
    await vi.advanceTimersByTimeAsync(1000);
    expect(poll.pollError.value).toBe(
      '网络不稳定，正在自动重试。可点「刷新会话状态」或检查网络后再试。'
    );
    poll.stopPoll();
  });

  it('总时长到期：停表 + onCapped + 同会话不再重燃', async () => {
    const onTick = vi.fn().mockResolvedValue(undefined);
    const { poll, onCapped } = setup(onTick);
    poll.startPoll();
    // 10 分钟（SESSION_POLL_MAX_DURATION_MS）后触发封顶
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000 + 1000);
    expect(onCapped).toHaveBeenCalledTimes(1);
    const callsAtCap = onTick.mock.calls.length;

    // 同会话再 start 不重燃
    poll.startPoll();
    await vi.advanceTimersByTimeAsync(3000);
    expect(onTick.mock.calls.length).toBe(callsAtCap);
  });

  it('refreshNow：成功回调一次；进行中防重入', async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const onTick = vi.fn().mockImplementation(() => gate);
    const { poll, onRefreshOk } = setup(onTick);
    const first = poll.refreshSessionNow();
    const second = poll.refreshSessionNow(); // 进行中 ⇒ 直接返回
    release();
    await Promise.all([first, second]);
    expect(onTick).toHaveBeenCalledTimes(1);
    expect(onRefreshOk).toHaveBeenCalledTimes(1);
  });
});
