/**
 * 公告已读状态（客户端本地持久化，不依赖后端）。
 * 三端共用：列表页展示未读标记，详情页打开即标记已读。
 */

const KEY = 'announcement_read_map';

/** 已读表容量上限（R2-C6）：原先无界增长，公告一条条发下去 map 只增不减、永久占 storage。 */
const MAX_ENTRIES = 200;

type ReadMap = Record<string, number>;

export function announcementReadMap(): ReadMap {
  try {
    const raw = uni.getStorageSync(KEY);
    return raw && typeof raw === 'object' ? (raw as ReadMap) : {};
  } catch {
    return {};
  }
}

export function isAnnouncementUnread(id: number | string | null | undefined): boolean {
  if (id == null) return false;
  return !announcementReadMap()[String(id)];
}

export function markAnnouncementRead(id: number | string | null | undefined) {
  if (id == null) return;
  const map = announcementReadMap();
  map[String(id)] = Date.now();
  // 超出上限按「最久以前读过」淘汰；被淘汰的旧公告会重新显示为未读——
  // 相比无限增长，这是更小的代价（公告列表本身也翻不到那么早）。
  const ids = Object.keys(map);
  if (ids.length > MAX_ENTRIES) {
    ids
      .sort((a, b) => (map[a] || 0) - (map[b] || 0))
      .slice(0, ids.length - MAX_ENTRIES)
      .forEach((oldId) => {
        delete map[oldId];
      });
  }
  try {
    uni.setStorageSync(KEY, map);
  } catch {
    /* ignore */
  }
}
