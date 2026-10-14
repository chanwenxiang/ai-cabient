/**
 * tabBar 未读角标（2026-10-14 审计补齐）：merchant 端早有 todo-badge，
 * consumer 端此前没有——消息中心入口在「我的」，未读时 tabBar 无任何提示。
 *
 * 策略：登录态下拉 /member/notifications/unread-count，「我的」tab 挂角标
 * （>99 显示 99+，0 清除）；未登录/请求失败静默清除。onShow 触发，
 * switchTab 切页即刷新，无需常驻轮询（弱端友好）。
 */
import { consumerApi, isConsumerLoggedIn } from '@/utils/consumer-api';

/** pages.json tabBar 顺序：0=首页(购物) 1=订单 2=我的 */
const MINE_TAB_INDEX = 2;
let refreshing = false;

export async function refreshMineTabBadge(): Promise<void> {
  if (refreshing) return;
  refreshing = true;
  try {
    if (!isConsumerLoggedIn()) {
      uni.removeTabBarBadge({ index: MINE_TAB_INDEX });
      return;
    }
    const { count } = await consumerApi.notificationUnreadCount();
    const n = Number(count) || 0;
    if (n > 0) {
      uni.setTabBarBadge({
        index: MINE_TAB_INDEX,
        text: n > 99 ? '99+' : String(n)
      });
    } else {
      uni.removeTabBarBadge({ index: MINE_TAB_INDEX });
    }
  } catch {
    /* 角标是提示性 UI，任何失败静默清除即可，不影响功能 */
    try {
      uni.removeTabBarBadge({ index: MINE_TAB_INDEX });
    } catch {
      /* ignore */
    }
  } finally {
    refreshing = false;
  }
}
