/**
 * 备货单「已生成」的柜机排序：按本柜最近一张补货任务的创建时间（后端 lastTaskAt）正/倒序，
 * 时间相同（或后端没带时间）时按编码兜底，保证两次渲染顺序一致。
 */
export type PrepOrderDir = 'asc' | 'desc';

interface TimedDevice {
  deviceId?: string | null;
  lastTaskAt?: string | number | null;
}

export function taskTimeMs(group: TimedDevice): number {
  const raw = group.lastTaskAt;
  const ms = raw == null ? 0 : new Date(raw).getTime();
  return Number.isFinite(ms) ? ms : 0;
}

export function sortByGeneratedTime<T extends TimedDevice>(groups: T[], dir: PrepOrderDir): T[] {
  const sign = dir === 'asc' ? 1 : -1;
  return [...groups].sort(
    (a, b) =>
      (taskTimeMs(a) - taskTimeMs(b)) * sign ||
      String(a.deviceId ?? '').localeCompare(String(b.deviceId ?? ''))
  );
}
