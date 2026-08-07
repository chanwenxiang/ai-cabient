/**
 * 公告已读状态（客户端本地持久化，不依赖后端）。
 * 三端共用：列表页展示未读标记，详情页打开即标记已读。
 */

const KEY = 'announcement_read_map';

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
  try {
    uni.setStorageSync(KEY, map);
  } catch {
    /* ignore */
  }
}
