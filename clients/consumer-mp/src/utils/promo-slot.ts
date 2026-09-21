/**
 * 首页推广位（S1）的**来源选择**规则 —— 纯函数，便于单测。
 *
 * 为什么单独抽出来：这条优先级原本散在 `device-ad-banner.vue` 的三个布尔判断里，
 * 而 vitest 跑在 `environment: 'node'` 下（无 jsdom/组件测试），散在组件里的逻辑
 * **一条判据都落不下来**。「谁优先、什么时候什么都不渲染」恰是本项最容易改错的地方
 * （改错了表现是「活动被腾讯广告盖掉」或「素材到达前闪一下占位图」），所以抽成纯函数。
 *
 * 见 `docs/AD_MONETIZATION_DESIGN.md`。
 */
export type PromoSlotSource = 'none' | 'self' | 'wxAd' | 'placeholder';

export interface PromoSlotInput {
  /** 自有投放内容是否**已完成**一次加载（false = 仍在请求中）。 */
  loaded: boolean;
  /** 该柜是否有生效中的自有投放素材（我们自己的活动/推广）。 */
  hasOwnContent: boolean;
  /** 腾讯流量主广告是否**可渲染**（开关开 **且** 广告单元 ID 非空）。 */
  wxAdAvailable: boolean;
}

/**
 * 依次回答三个问题：自有？腾讯？还是占位？
 *
 * 1. **自有优先** —— 我们自己做活动时，这个位置必须归我们。腾讯广告是**兜底填充**，
 *    不抢占自有内容（否则「做活动」和「赚广告费」会互相打架）。
 * 2. 没有自有内容时才轮到腾讯广告（开关与 ID 都在读取处收敛过，这里只做取舍）。
 * 3. 两者都没有：**已加载完成**才给占位图；未加载完成一律 `'none'`。
 *    🔴 这条很关键 —— 用「已加载」而不是「素材为空」当闸门，否则真实素材到达前
 *    会先闪一下占位图，看起来像「投放内容丢了」（与 `device-ad-banner.vue` 的
 *    `loaded` 语义一致）。
 */
export function resolvePromoSlot(input: PromoSlotInput): PromoSlotSource {
  if (input.hasOwnContent) return 'self';
  if (input.wxAdAvailable) return 'wxAd';
  return input.loaded ? 'placeholder' : 'none';
}
