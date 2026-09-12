import { navigateBackOrHome as sharedNavigateBackOrHome } from '@aicabinet/shared-uni/navigate-back';

/** 商户端无历史栈时默认回工作台 */
export function navigateBackOrHome(homeUrl = '/pages/home/home') {
  sharedNavigateBackOrHome(homeUrl);
}
