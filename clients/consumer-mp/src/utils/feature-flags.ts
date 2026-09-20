import { consumerApi } from './consumer-api';

/**
 * C 端「扩展功能」开关的进程内缓存。
 *
 * 背景：后端把开关值随 `GET /api/v2/public/consumer-config`（`SystemConfigService#consumerPublicConfig`）
 * 一起下发；但该接口原先只在首页被调用、且只取了客服电话等字段，别的页面拿不到开关。
 * 这里做一次性缓存，任何页面都能同步读取，且不重复请求。
 *
 * 🔴 fail-closed：配置没取到（网络失败 / 字段缺失 / 值不是 true|1）时一律按「关」处理
 * ⇒ 新入口不显示，行为与接入前完全一致。绝不因为「拿不到配置」而把新功能放出来。
 */
let cache: Record<string, string> | null = null;
let inflight: Promise<Record<string, string>> | null = null;

function enabled(raw: unknown): boolean {
  return raw === true || raw === 'true' || raw === '1';
}

/** 拉取并缓存公开配置（进程内一次）。失败时缓存空表 ⇒ 所有开关按「关」。 */
export async function loadConsumerFlags(): Promise<Record<string, string>> {
  if (cache) return cache;
  if (inflight) return inflight;
  inflight = consumerApi
    .consumerPublicConfig()
    .then((cfg) => {
      cache = cfg || {};
      return cache;
    })
    .catch(() => {
      cache = {};
      return cache;
    })
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

/**
 * 用页面里**已经**取到的公开配置预置缓存，避免同一页面为了读开关再发一次请求。
 *
 * 首页（`pages/index/index.vue`）与「我的」（`pages/mine/mine.vue`）本来就会拉
 * `GET /api/v2/public/consumer-config`，让它们把结果顺手喂进来即可。
 *
 * 🔴 只接受成功响应：传 `null`/`undefined`（网络失败）时**不动缓存**，
 * 留给 `loadConsumerFlags()` 重试 —— 否则一次失败会把整个进程的开关永久钉死在「关」。
 */
export function seedConsumerFlags(cfg: Record<string, string> | null | undefined): void {
  if (!cfg) return;
  cache = cfg;
}

/**
 * 订单列表关键字搜索入口（`consumer.order_search.enabled`）。**关闭时订单页不渲染搜索框。**
 *
 * 注意：缓存未热（尚未 `loadConsumerFlags()`/`seedConsumerFlags()`）时恒返回 `false`。
 * 需要在首屏就正确显示的场景，调用方应先同步读一次再异步刷新（见 orders.vue）。
 */
export function orderSearchEnabled(): boolean {
  return enabled(cache?.orderSearchEnabled);
}

/**
 * 首页券包入口前置（`consumer.coupon_entry.enabled`）。
 *
 * **开**：首页（落地页）底部显示「我的券包」入口；
 * **关**（默认）：首页不显示，券包入口仍只在「我的」页等既有位置 —— 即接入前行为。
 */
export function couponEntryEnabled(): boolean {
  return enabled(cache?.couponEntryEnabled);
}

/**
 * 本柜商品详情弹层（`consumer.product_detail.enabled`）。
 *
 * **开**：商品卡片出现「详情」入口，点开弹层显示大图/分类/在柜数量/描述；
 * **关**（默认）：商品卡片维持原样（缩略图、名称、价格、分类），即接入前行为。
 */
export function productDetailEnabled(): boolean {
  return enabled(cache?.productDetailEnabled);
}

/**
 * 首页广告位（S1，`consumer.ad_banner.enabled`）。见 `docs/AD_MONETIZATION_DESIGN.md`。
 *
 * **开**：首页在柜机状态卡下方渲染广告位 —— 该柜有生效投放则显示真实素材，没有则显示占位图；
 * **关**（默认）：首页**不渲染广告位**，即接入前行为（fail-closed）。
 *
 * ⚠️ 与「计量」无关：占位图不上报曝光/点击；真实素材的上报由组件内部按 campaignId 决定。
 */
export function adBannerEnabled(): boolean {
  return enabled(cache?.adBannerEnabled);
}
