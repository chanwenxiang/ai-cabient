export function setAlertsTabBadge(count: number) {
  try {
    if (count > 0) {
      uni.setTabBarBadge({
        index: 2,
        text: count > 99 ? '99+' : String(count)
      });
    } else {
      uni.removeTabBarBadge({ index: 2 });
    }
  } catch {
    /* H5 / non-tab context */
  }
}
