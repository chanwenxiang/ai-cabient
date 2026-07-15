import type { DeviceProduct } from '@aicabinet/shared-types';

/** 小程序内本地商品图（外链 placehold 在微信里常被拦） */
const LOCAL_SKU_IMAGES: Record<string, string> = {
  'SKU-DEMO-001': '/static/sku/cola.png',
  'SKU-SODA-001': '/static/sku/sprite.png',
  'SKU-WATER-001': '/static/sku/water.png',
  'SKU-SNACK-001': '/static/sku/chips.png',
  'SKU-MILK-001': '/static/sku/milk.png',
  'SKU-NOODLE-001': '/static/sku/noodle.png'
};

const CATEGORY_EMOJI: Record<string, string> = {
  饮料: '🥤',
  零食: '🍟',
  乳品: '🥛',
  方便食品: '🍜'
};

export function productThumb(p: DeviceProduct): string {
  return LOCAL_SKU_IMAGES[p.skuId] || '';
}

export function productEmoji(p: DeviceProduct): string {
  return CATEGORY_EMOJI[p.category || ''] || '🛒';
}
