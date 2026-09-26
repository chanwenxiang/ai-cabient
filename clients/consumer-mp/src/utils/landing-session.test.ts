import { describe, expect, it, vi } from 'vitest';
import {
  OPEN_TIMEOUT_MS,
  ORPHAN_GRACE_MS,
  POLL_FAIL_WARN_AT,
  SESSION_ACTIVE_STATES,
  SESSION_POLL_MS,
  SESSION_TERMINAL_STATES,
  abortSessionFallbackHint,
  beginCabinetEntryGate,
  blockedDeviceLandingError,
  classifyPollSessionState,
  concurrentEntryDecision,
  deviceStatusLabel,
  isAdoptableSession,
  isCabinetIdInvalid,
  isNetworkishErrorMessage,
  isTerminalSessionState,
  normalizeCabinetId,
  parseDeviceAvailability,
  pollErrorMessage,
  sessionOpenDecision,
  settleWithin,
  shouldResumeSessionPolling,
  withTimeout
} from './landing-session';

describe('landing-session · C5/C5b/C5c', () => {
  it('normalizeCabinetId 去空白并大写', () => {
    expect(normalizeCabinetId('  cab-01 ')).toBe('CAB-01');
  });

  it('isCabinetIdInvalid 拒绝空/非法字符', () => {
    expect(isCabinetIdInvalid('AB')).toBe(false);
    expect(isCabinetIdInvalid('A')).toBe(true);
  });

  it('isAdoptableSession：同柜 + 进行中态', () => {
    expect(isAdoptableSession({ deviceId: 'cab-1', state: 'SHOPPING' }, 'CAB-1')).toBe(true);
    expect(isAdoptableSession({ deviceId: 'cab-1', state: 'PAID' }, 'CAB-1')).toBe(false);
    expect(SESSION_ACTIVE_STATES).toContain('OPENING');
    expect(SESSION_TERMINAL_STATES).toContain('COMPLETED');
  });

  it('blockedDeviceLandingError 按 reason 分支', () => {
    expect(blockedDeviceLandingError(false, '').kind).toBe('other');
    expect(blockedDeviceLandingError(true, 'LOCKED').kind).toBe('device_paused');
  });

  it('settleWithin：超时返回 null', async () => {
    vi.useFakeTimers();
    const p = settleWithin(new Promise(() => {}), 50);
    vi.advanceTimersByTime(50);
    await expect(p).resolves.toBeNull();
    vi.useRealTimers();
  });

  it('settleWithin：在途失败也返回 null（故意，非 softFallback）', async () => {
    await expect(settleWithin(Promise.reject(new Error('boom')), 50)).resolves.toBeNull();
  });

  it('settleWithin：成功返回值', async () => {
    await expect(settleWithin(Promise.resolve(42), 50)).resolves.toBe(42);
  });

  it('C5b：可用性 / 并发拦截 / 弱网文案', () => {
    expect(ORPHAN_GRACE_MS).toBe(5000);
    expect(OPEN_TIMEOUT_MS).toBe(20_000);
    expect(POLL_FAIL_WARN_AT).toBe(3);
    const avail = parseDeviceAvailability({
      online: true,
      available: false,
      busyReason: 'SESSION'
    });
    expect(avail.blocked).toBe(true);
    expect(avail.reason).toBe('SESSION');
    expect(
      deviceStatusLabel(
        { online: false },
        {
          offline: '离线',
          paused: '暂停',
          replenishing: '补货',
          inUse: '使用中',
          onlineReady: '就绪'
        }
      )
    ).toBe('离线');
    expect(concurrentEntryDecision(false, 'A', 'B')).toBe('allow');
    expect(concurrentEntryDecision(true, 'cab-1', 'CAB-1')).toBe('same_cabinet');
    expect(concurrentEntryDecision(true, 'cab-1', 'CAB-2')).toBe('other_cabinet');
    expect(isNetworkishErrorMessage('网络超时')).toBe(true);
    expect(pollErrorMessage(1, true, 'x')).toBe('网络波动，正在重试…');
    expect(pollErrorMessage(3, true, 'x')).toContain('网络不稳定');
  });

  it('withTimeout：超时 reject，成功放行', async () => {
    vi.useFakeTimers();
    const pending = withTimeout(new Promise(() => {}), 30, '超时了');
    vi.advanceTimersByTime(30);
    await expect(pending).rejects.toThrow('超时了');
    vi.useRealTimers();
    await expect(withTimeout(Promise.resolve('ok'), 50, '超时了')).resolves.toBe('ok');
  });

  it('C5c：轮询/开门编排决策', () => {
    expect(SESSION_POLL_MS).toBe(2000);
    expect(isTerminalSessionState('COMPLETED')).toBe(true);
    expect(isTerminalSessionState('SHOPPING')).toBe(false);
    expect(shouldResumeSessionPolling('s1', 'SHOPPING')).toBe(true);
    expect(shouldResumeSessionPolling('', 'SHOPPING')).toBe(false);
    expect(classifyPollSessionState('SHOPPING').kind).toBe('shopping');
    expect(classifyPollSessionState('COMPLETED')).toEqual({
      kind: 'finish',
      state: 'COMPLETED'
    });
    expect(classifyPollSessionState('CANCELLED')).toEqual({
      kind: 'abort',
      state: 'CANCELLED'
    });
    expect(abortSessionFallbackHint('CANCELLED')).toBe('会话已取消');
    expect(abortSessionFallbackHint('FAILED')).toBe('购物未完成');
    expect(
      beginCabinetEntryGate({
        cabinetId: 'CAB-01',
        opening: false,
        enteringFlow: false,
        concurrent: 'allow'
      })
    ).toBe('ok');
    expect(
      beginCabinetEntryGate({
        cabinetId: 'CAB-01',
        opening: true,
        enteringFlow: false,
        concurrent: 'allow'
      })
    ).toBe('busy');
    expect(
      beginCabinetEntryGate({
        cabinetId: 'CAB-01',
        opening: false,
        enteringFlow: false,
        concurrent: 'other_cabinet'
      })
    ).toBe('concurrent_blocked');
    expect(
      beginCabinetEntryGate({
        cabinetId: 'X',
        opening: false,
        enteringFlow: false,
        concurrent: 'allow'
      })
    ).toBe('invalid_cabinet_id');
    expect(sessionOpenDecision('fulfilled')).toBe('adopt_fulfilled');
    expect(sessionOpenDecision('rejected')).toBe('try_orphan');
  });
});
