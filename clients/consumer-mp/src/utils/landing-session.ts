/**
 * 落地页会话/开门纯逻辑（debt-tracker C5 → C5b → C5c）。
 * 禁止把 UI 状态写进本模块；index.vue 只改 import，不改布局/视觉。
 * C5c：轮询/开门编排决策纯函数；定时器与 API 调用仍在页内。
 */
import type { OpenErrorKind } from '@aicabinet/shared-uni/format';

export const ACTIVE_SESSION_KEY = 'active_session_id';
export const REVIEW_SESSION_KEY = 'last_disputed_session_id';

/**
 * 会话「进行中」状态集合（非终态）。
 * 轮询恢复、孤儿会话接管、重复开门拦截共用同一份定义。
 */
export const SESSION_ACTIVE_STATES: readonly string[] = [
  'CREATED',
  'OPENING',
  'SHOPPING',
  'RECOGNIZING',
  'WAITING_UPLOAD',
  'SETTLING'
];

/** 终态：恢复轮询 / finishSession 分支共用。 */
export const SESSION_TERMINAL_STATES: readonly string[] = [
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'DISPUTED'
];

/**
 * C-2：开门超时后给「仍在途的 createSession」的宽限期，以及随后轮询 /sessions/active 的退避间隔。
 * 依据：request 层单次超时 12s + 内部失败重试 600ms + 再 12s ⇒ 最长约 24.6s 才有结论，
 * 而开门侧的 withTimeout 在 20s 就放弃了等待。
 */
export const ORPHAN_GRACE_MS = 5000;
export const ORPHAN_ADOPT_BACKOFF_MS: readonly number[] = [0, 1000, 2000];
export const OPEN_TIMEOUT_MS = 20_000;
export const POLL_FAIL_WARN_AT = 3;
/** 会话状态轮询间隔（ms）。 */
export const SESSION_POLL_MS = 2000;

export function normalizeCabinetId(id: string): string {
  return id.trim().toUpperCase();
}

export function isCabinetIdInvalid(cabinetId: string): boolean {
  return !/^[A-Z0-9][A-Z0-9_-]{1,63}$/.test(cabinetId);
}

/** 该会话能否被本次开门接管：同柜机且处于非终态。 */
export function isAdoptableSession(
  s: { deviceId?: string | null; state?: string | null } | null | undefined,
  cabinetId: string,
  activeStates: readonly string[] = SESSION_ACTIVE_STATES
): boolean {
  if (!s) return false;
  const same = normalizeCabinetId(String(s.deviceId || '')) === normalizeCabinetId(cabinetId);
  return same && activeStates.includes(String(s.state || ''));
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 孤儿开门宽限期：在 ms 内成功则返回结果。
 * 超时 **或** 在途 Promise 失败均返回 null（故意）：调用方必须继续 `/sessions/active` 轮询。
 * 禁止把本函数当成 softFallback（空列表伪装）；语义是「放弃等这一次 Promise」。
 * @see adoptOrphanSession（index.vue C-2）
 */
export function settleWithin<T>(promise: Promise<T>, ms: number): Promise<T | null> {
  return Promise.race<T | null>([
    promise.then(
      (value) => value,
      () => null
    ),
    sleep(ms).then(() => null)
  ]);
}

/**
 * 放弃等待（超时 reject），底层 Promise 仍可在途成功——与 settleWithin（失败→null）不同。
 * 用于 createSession / deviceProducts 的开门超时。
 */
export function withTimeout<T>(
  promise: Promise<T>,
  ms: number,
  timeoutMessage: string
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(timeoutMessage)), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (err) => {
        clearTimeout(timer);
        reject(err);
      }
    );
  });
}

export type DeviceAvailability = {
  online: boolean;
  reason: string;
  blocked: boolean;
};

/** 从 deviceStatus DTO 解析可用性（不含 UI 赋值）。 */
export function parseDeviceAvailability(status: {
  online?: boolean | null;
  onlineStatus?: string | null;
  busyReason?: string | null;
  available?: boolean | null;
}): DeviceAvailability {
  const online = status.online === true || (status.onlineStatus || '').toUpperCase() === 'ONLINE';
  const reason = String(status.busyReason || '').toUpperCase();
  return { online, reason, blocked: !online || status.available === false };
}

/**
 * 柜机状态短文案（page 赋给 deviceStatusText）。
 * labels 由调用方传入 UI_COPY，避免本模块依赖文案包。
 */
export function deviceStatusLabel(
  status: {
    online?: boolean | null;
    onlineStatus?: string | null;
    busyReason?: string | null;
    available?: boolean | null;
  },
  labels: {
    offline: string;
    paused: string;
    replenishing: string;
    inUse: string;
    onlineReady: string;
  }
): string {
  const avail = parseDeviceAvailability(status);
  if (!avail.online) return labels.offline;
  if (status.available === false && avail.reason === 'LOCKED') return labels.paused;
  if (status.available === false && avail.reason === 'REPLENISHMENT') return labels.replenishing;
  if (status.available === false || avail.reason === 'SESSION') return labels.inUse;
  return labels.onlineReady;
}

export type ConcurrentEntryDecision = 'allow' | 'same_cabinet' | 'other_cabinet';

/** C-3：已有进行中会话时是否拦截开门（不含 toast/导航）。 */
export function concurrentEntryDecision(
  sessionActive: boolean,
  currentDeviceId: string,
  targetCabinetId: string
): ConcurrentEntryDecision {
  if (!sessionActive) return 'allow';
  const current = normalizeCabinetId(currentDeviceId || '');
  const target = normalizeCabinetId(targetCabinetId || '');
  if (current && current === target) return 'same_cabinet';
  return 'other_cabinet';
}

export function isNetworkishErrorMessage(msg: string): boolean {
  return /超时|timeout|网络|无法连接|request:fail|ECONN|ENOTFOUND|abort/i.test(msg);
}

/** 轮询失败提示文案（不含 formatError）。 */
export function pollErrorMessage(
  failStreak: number,
  isNetwork: boolean,
  formattedError: string,
  warnAt: number = POLL_FAIL_WARN_AT
): string {
  if (failStreak >= warnAt) {
    return isNetwork
      ? '网络不稳定，正在自动重试。可点「刷新会话状态」或检查网络后再试。'
      : formattedError;
  }
  if (isNetwork) return '网络波动，正在重试…';
  return formattedError;
}

export type BlockedDeviceLandingError = {
  kind: OpenErrorKind;
  msg: string;
  toastTitle: string;
};

/** 柜机不可用时的落地错误文案（纯函数；fallback 供未知 reason）。 */
export function blockedDeviceLandingError(
  online: boolean,
  reason: string,
  fallbackStatusText = '暂时无法开门'
): BlockedDeviceLandingError {
  if (!online) {
    return {
      kind: 'other',
      msg: '该柜机当前离线，请稍后再试或更换其他柜机。',
      toastTitle: '暂时无法开门'
    };
  }
  if (reason === 'LOCKED') {
    return {
      kind: 'device_paused',
      msg: '柜机已暂停营业，请稍后再试或换一台',
      toastTitle: '柜机暂停营业'
    };
  }
  if (reason === 'REPLENISHMENT') {
    return {
      kind: 'device_busy',
      msg: '柜机正在补货，请稍后再试',
      toastTitle: '柜机正忙'
    };
  }
  if (reason === 'SESSION') {
    return {
      kind: 'device_busy',
      msg: '柜机正在被使用，请稍后再试',
      toastTitle: '柜机正忙'
    };
  }
  return { kind: 'other', msg: fallbackStatusText, toastTitle: '暂时无法开门' };
}

export function isTerminalSessionState(
  state: string | null | undefined,
  terminal: readonly string[] = SESSION_TERMINAL_STATES
): boolean {
  return terminal.includes(String(state || ''));
}

/** C-1：本地已有进行中会话时是否应恢复轮询（不含 startPoll 副作用）。 */
export function shouldResumeSessionPolling(
  sessionId: string | null | undefined,
  state: string | null | undefined,
  active: readonly string[] = SESSION_ACTIVE_STATES
): boolean {
  return Boolean(sessionId) && active.includes(String(state || ''));
}

export type PollSessionOutcome =
  | { kind: 'shopping' }
  | { kind: 'active_other' }
  | { kind: 'finish'; state: 'COMPLETED' | 'DISPUTED' }
  | { kind: 'abort'; state: 'FAILED' | 'CANCELLED' };

/** 单次 getSession 后的分支决策（不含 API / 定时器）。 */
export function classifyPollSessionState(state: string | null | undefined): PollSessionOutcome {
  const s = String(state || '').toUpperCase();
  if (s === 'SHOPPING') return { kind: 'shopping' };
  if (s === 'COMPLETED' || s === 'DISPUTED') return { kind: 'finish', state: s };
  if (s === 'FAILED' || s === 'CANCELLED') return { kind: 'abort', state: s };
  return { kind: 'active_other' };
}

/** 终态会话中止时的兜底提示（优先用 sessionStateHint）。 */
export function abortSessionFallbackHint(state: 'FAILED' | 'CANCELLED'): string {
  return state === 'CANCELLED' ? '会话已取消' : '购物未完成';
}

export type BeginCabinetEntryGate = 'ok' | 'busy' | 'concurrent_blocked' | 'invalid_cabinet_id';

/**
 * 开门入口门闩（不含 toast / 写 entryChannel）。
 * concurrent 由 concurrentEntryDecision 算出后传入。
 */
export function beginCabinetEntryGate(input: {
  cabinetId: string;
  opening: boolean;
  enteringFlow: boolean;
  concurrent: ConcurrentEntryDecision;
}): BeginCabinetEntryGate {
  if (!input.cabinetId || input.opening || input.enteringFlow) return 'busy';
  if (input.concurrent !== 'allow') return 'concurrent_blocked';
  if (isCabinetIdInvalid(input.cabinetId)) return 'invalid_cabinet_id';
  return 'ok';
}

export type SessionOpenDecision = 'adopt_fulfilled' | 'try_orphan';

export function sessionOpenDecision(resultStatus: 'fulfilled' | 'rejected'): SessionOpenDecision {
  return resultStatus === 'fulfilled' ? 'adopt_fulfilled' : 'try_orphan';
}
