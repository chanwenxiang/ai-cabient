/**
 * 落地页会话/开门纯逻辑（debt-tracker C5 首刀）。
 * 禁止把 UI 状态写进本模块；index.vue 只改 import，不改布局/视觉。
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
