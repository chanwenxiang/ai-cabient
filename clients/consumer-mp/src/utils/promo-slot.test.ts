import { describe, expect, it } from 'vitest';
import { resolvePromoSlot, type PromoSlotInput } from './promo-slot';

/**
 * 首页推广位来源选择的判据。
 *
 * 这几条守的是**优先级**，不是「有没有实现」：本项最容易出的错是顺序写反
 * （腾讯广告盖掉自家活动）或闸门用错（未加载完成就闪占位图），两者都只在
 * 特定时序下出现，靠肉眼很难稳定复现。
 */
function input(partial: Partial<PromoSlotInput> = {}): PromoSlotInput {
  return { loaded: true, hasOwnContent: false, wxAdAvailable: false, ...partial };
}

describe('首页推广位来源选择', () => {
  it('有自有投放 ⇒ 一定选自有，腾讯广告不得抢占', () => {
    expect(resolvePromoSlot(input({ hasOwnContent: true }))).toBe('self');
    expect(resolvePromoSlot(input({ hasOwnContent: true, wxAdAvailable: true }))).toBe('self');
  });

  it('无自有投放 + 腾讯广告可用 ⇒ 选腾讯广告', () => {
    expect(resolvePromoSlot(input({ wxAdAvailable: true }))).toBe('wxAd');
  });

  it('两者都没有 + 已加载完成 ⇒ 占位图', () => {
    expect(resolvePromoSlot(input())).toBe('placeholder');
  });

  it('🔴 两者都没有 + 尚未加载完成 ⇒ 什么都不渲染（防止真素材到达前闪占位图）', () => {
    expect(resolvePromoSlot(input({ loaded: false }))).toBe('none');
  });

  it('腾讯广告不依赖我们的接口 ⇒ 未加载完成也应能出广告', () => {
    // 自有素材还在请求中，但腾讯广告现在就能渲染，没有理由被我们的请求拖住
    expect(resolvePromoSlot(input({ loaded: false, wxAdAvailable: true }))).toBe('wxAd');
  });

  it('已加载完成且有自有内容 ⇒ 仍是自有（回归：别被 loaded 判反）', () => {
    expect(resolvePromoSlot(input({ loaded: true, hasOwnContent: true }))).toBe('self');
  });
});
