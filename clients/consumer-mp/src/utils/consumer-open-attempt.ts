/**
 * 消费者开门幂等尝试（debt-tracker C12b）。
 * 从 consumer-api 拆出，禁止页内自管 Storage key。
 */
import { secureRandomToken } from '@/utils/secure-id';

export const OPEN_ATTEMPT_KEY = 'consumer_open_attempt';

/**
 * 幂等键有效期。超过即视为「新的一次开门意图」，换新键。
 * 依据（2026-11-10 审计 P0-4）：键原先永不失效，而后端 `validateIdempotentReplay` 只校验
 * user/device、不看状态 ⇒ 上次会话已结束（COMPLETED/CANCELLED）后再扫同一台柜，
 * 仍会回放那笔已结束的会话：柜门不开，人还被带进上一笔账单。
 */
export const OPEN_ATTEMPT_TTL_MS = 2 * 60 * 1000;

export type OpenAttempt = { deviceId: string; idempotencyKey: string; createdAt: number };

export function isOpenAttempt(value: unknown): value is OpenAttempt {
  if (!value || typeof value !== 'object') return false;
  const row = value as OpenAttempt;
  return (
    typeof row.deviceId === 'string' &&
    typeof row.idempotencyKey === 'string' &&
    typeof row.createdAt === 'number' &&
    Number.isFinite(row.createdAt)
  );
}

/**
 * 键是否仍在有效期内。
 * 没有 `createdAt` 的历史数据按「过期」处理（否则 `now - undefined = NaN`，比较恒 false，键永生）。
 * 时间回拨（age < 0）同样按过期处理，避免设备时间被改后键长期不换。
 */
export function isOpenAttemptFresh(attempt: OpenAttempt, now = Date.now()): boolean {
  if (!Number.isFinite(attempt.createdAt)) return false;
  const age = now - attempt.createdAt;
  return age >= 0 && age < OPEN_ATTEMPT_TTL_MS;
}

export function normalizeOpenAttemptDeviceId(deviceId: string): string {
  return deviceId.trim().toUpperCase();
}

export function buildOpenAttemptIdempotencyKey(now = Date.now()): string {
  return `consumer-open-${now.toString(36)}-${secureRandomToken(6)}-${secureRandomToken(6)}`;
}

export function getOrCreateOpenAttempt(deviceId: string, now = Date.now()): OpenAttempt {
  const normalized = normalizeOpenAttemptDeviceId(deviceId);
  const saved = uni.getStorageSync(OPEN_ATTEMPT_KEY);
  if (
    isOpenAttempt(saved) &&
    saved.deviceId === normalized &&
    saved.idempotencyKey &&
    isOpenAttemptFresh(saved, now)
  ) {
    return saved;
  }
  const attempt: OpenAttempt = {
    deviceId: normalized,
    idempotencyKey: buildOpenAttemptIdempotencyKey(now),
    createdAt: now
  };
  uni.setStorageSync(OPEN_ATTEMPT_KEY, attempt);
  return attempt;
}

export function clearOpenAttempt(): void {
  uni.removeStorageSync(OPEN_ATTEMPT_KEY);
}
