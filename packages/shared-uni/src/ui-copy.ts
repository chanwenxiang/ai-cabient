/**
 * 小程序 / H5 用户可见文案约定（X04 / X03）。
 * 页面与组件优先引用此处，避免加载/状态文案混用。
 */
export const UI_COPY = {
  loading: '加载中…',
  loadMore: '加载更多',
  loadFailed: '加载失败',
  pleaseWait: '请稍候',
  emptyDefault: '暂无数据',
  retry: '重试',
  networkHint: '请检查网络后重试',
  online: '在线',
  offline: '离线',
  onlineReady: '在线 · 可开门',
  replenishing: '补货中',
  paused: '暂停营业',
  inUse: '使用中',
  doorOpenShopping: '门已开 · 购物中',
  opening: '正在开门',
  available: '可开门',
  busy: '忙碌/停售',
  salesLocked: '停售'
} as const;

export type UiCopyKey = keyof typeof UI_COPY;

/** 在线/离线文案（X03：须与颜色/圆点一起展示） */
export function onlineLabel(online: boolean): string {
  return online ? UI_COPY.online : UI_COPY.offline;
}
