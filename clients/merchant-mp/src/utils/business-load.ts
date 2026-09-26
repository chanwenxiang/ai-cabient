/**
 * 经营分析首屏 load 决策（debt-tracker M6d）。
 * 纯函数：禁止依赖 uni / merchantApi；五路 softFallback 调用仍在 business.vue。
 */

/** soft 刷新且已有 topSkus 时不盖全页 loading。 */
export function shouldShowBusinessFullLoading(soft: boolean, topSkuCount: number): boolean {
  return !soft || topSkuCount <= 0;
}

export function isStaleBusinessLoad(seq: number, currentLoadSeq: number): boolean {
  return seq !== currentLoadSeq;
}

/** 分析与结算同时失败 → 硬错误（与历史 `!a && !s` 一致）。 */
export function isBusinessBundleHardFail(analytics: unknown, settlement: unknown): boolean {
  return !analytics && !settlement;
}

export const BUSINESS_BUNDLE_HARD_FAIL_MESSAGE = '经营数据加载失败';

export function businessLoadErrorMessage(e: unknown, fallback = '加载失败'): string {
  return e instanceof Error ? e.message : fallback;
}

/**
 * soft 下是否应跳过把 loading 置 true 的反向：仅用于文档化。
 * 返回合并后的 analytics/settlement 是否保留旧值（调用方传入旧值）。
 */
export function coalesceBusinessBundle<T, S>(input: {
  analytics: T | null | undefined;
  settlement: S | null | undefined;
  prevAnalytics: T;
  prevSettlement: S;
}): { analytics: T; settlement: S; hardFail: boolean } {
  const hardFail = isBusinessBundleHardFail(input.analytics, input.settlement);
  return {
    analytics: input.analytics || input.prevAnalytics,
    settlement: input.settlement || input.prevSettlement,
    hardFail
  };
}
