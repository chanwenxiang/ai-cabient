/**
 * 消费者开门幂等尝试（debt-tracker C12b）。
 * 从 consumer-api 拆出，禁止页内自管 Storage key。
 */
import { secureRandomToken } from '@/utils/secure-id';

export const OPEN_ATTEMPT_KEY = 'consumer_open_attempt';

export type OpenAttempt = { deviceId: string; idempotencyKey: string; createdAt: number };

export function isOpenAttempt(value: unknown): value is OpenAttempt {
  if (!value || typeof value !== 'object') return false;
  const row = value as OpenAttempt;
  return typeof row.deviceId === 'string' && typeof row.idempotencyKey === 'string';
}

export function normalizeOpenAttemptDeviceId(deviceId: string): string {
  return deviceId.trim().toUpperCase();
}

export function buildOpenAttemptIdempotencyKey(now = Date.now()): string {
  return `consumer-open-${now.toString(36)}-${secureRandomToken(6)}-${secureRandomToken(6)}`;
}

export function getOrCreateOpenAttempt(deviceId: string): OpenAttempt {
  const normalized = normalizeOpenAttemptDeviceId(deviceId);
  const saved = uni.getStorageSync(OPEN_ATTEMPT_KEY);
  if (isOpenAttempt(saved) && saved.deviceId === normalized && saved.idempotencyKey) return saved;
  const attempt: OpenAttempt = {
    deviceId: normalized,
    idempotencyKey: buildOpenAttemptIdempotencyKey(),
    createdAt: Date.now()
  };
  uni.setStorageSync(OPEN_ATTEMPT_KEY, attempt);
  return attempt;
}

export function clearOpenAttempt(): void {
  uni.removeStorageSync(OPEN_ATTEMPT_KEY);
}
