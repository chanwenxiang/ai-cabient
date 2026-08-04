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

const CATEGORY_GLYPH: Record<string, string> = {
  饮料: '饮',
  零食: '零',
  乳品: '乳',
  方便食品: '面'
};

export function productThumb(p: DeviceProduct): string {
  return LOCAL_SKU_IMAGES[p.skuId] || '';
}

/** Short Chinese mark when no local thumb image is available. */
export function productGlyph(p: DeviceProduct): string {
  const fromCat = CATEGORY_GLYPH[p.category || ''];
  if (fromCat) return fromCat;
  const name = String(p.skuName || '').trim();
  if (name) return name.slice(0, 1);
  return '品';
}

/** @deprecated use productGlyph */
export function productEmoji(p: DeviceProduct): string {
  return productGlyph(p);
}
