/**
 * 二级页统一返回：有历史则返回，否则回工作台。
 */
export function navigateBackOrHome(homeUrl = '/pages/home/home') {
  const pages = getCurrentPages();
  if (pages.length > 1) {
    uni.navigateBack({ delta: 1 });
    return;
  }
  uni.switchTab({
    url: homeUrl,
    fail: () => {
      uni.reLaunch({ url: homeUrl });
    }
  });
}
