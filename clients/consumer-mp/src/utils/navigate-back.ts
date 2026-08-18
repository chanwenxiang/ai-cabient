/**
 * 二级页统一返回：有历史则返回，否则回首页 Tab。
 * 用于自定义顶栏；系统顶栏页依赖 uni 自带返回键。
 */
export function navigateBackOrHome(homeUrl = '/pages/index/index') {
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
