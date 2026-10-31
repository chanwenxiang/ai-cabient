/**
 * 组织过滤的公共判定（CB-079）。
 *
 * 「切换组织」只是前端展示上下文，不改后端授权范围（后端始终按 allowedMerchantIds 全树放行），
 * 因此这里只负责「按所选组织收窄展示」，不承担任何鉴权语义。
 *
 * 2026-10-31：柜机页曾出现「chip 计数按全树、列表按组织」两个口径同屏，故把判定抽成一处，
 * 页面必须让列表与计数走同一个 scope，避免再各自写一遍过滤条件。
 */
export type OrgDeviceRow = { deviceId?: string; merchantId?: string };

/**
 * 该组织下的 deviceId 集合；返回 null 表示**不按组织收窄**。
 *
 * 设备表为空时也返回 null（既可能是该组织确实没有柜机，也可能是设备表拉取失败）：
 * 两种情况下都无法把待办/任务归属到柜机，宁可多显示，也不把它们静默清空。
 */
export function orgDeviceScope(
  org: string | undefined | null,
  devices: readonly OrgDeviceRow[]
): Set<string> | null {
  const key = (org || '').trim();
  if (!key || !devices.length) return null;
  const ids = new Set<string>();
  for (const d of devices) {
    if (d.deviceId && d.merchantId === key) ids.add(d.deviceId);
  }
  return ids;
}

/**
 * 按组织设备集收窄行；scope 为 null 时原样返回。
 *
 * 没有 deviceId 的行（不归属任何柜机，如账号级提醒）保留 —— 组织管不到它。
 */
export function keepRowsInOrg<T>(
  rows: readonly T[],
  scope: Set<string> | null,
  deviceIdOf: (row: T) => string | undefined
): T[] {
  if (!scope) return [...rows];
  return rows.filter((row) => {
    const id = deviceIdOf(row);
    return !id || scope.has(id);
  });
}
