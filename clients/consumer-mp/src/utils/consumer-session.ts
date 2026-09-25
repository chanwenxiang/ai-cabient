/**
 * 消费端会话过期判定（纯函数，debt-tracker C3）。
 * Bearer JWT：本地 expires 到期必须清会话；Cookie 会话不在此硬清。
 */

/** 解析 Storage 中的过期时间戳（毫秒）；无效/缺失 → 0（不判过期，对齐 admin）。 */
export function parseConsumerExpiresAt(raw: unknown): number {
  const n = Number(raw || 0);
  return Number.isFinite(n) ? n : 0;
}

/** Bearer 本地过期：expiresAt > 0 且 now >= expiresAt。 */
export function isConsumerBearerExpired(expiresAt: number, nowMs = Date.now()): boolean {
  return expiresAt > 0 && nowMs >= expiresAt;
}
