import type { DeviceProduct } from '@aicabinet/shared-types';
import { API_BASE_URL } from '@/config/api';

/** 小程序内本地商品图（演示 SKU 兜底；正式商品图由后台在商品管理上传，三端共用） */
const LOCAL_SKU_IMAGES: Record<string, string> = {
  'SKU-DEMO-001': '/static/sku/cola.jpg',
  'SKU-SODA-001': '/static/sku/sprite.jpg',
  'SKU-WATER-001': '/static/sku/water.jpg',
  'SKU-SNACK-001': '/static/sku/chips.jpg',
  'SKU-MILK-001': '/static/sku/milk.jpg',
  'SKU-NOODLE-001': '/static/sku/noodle.jpg'
};

const CATEGORY_GLYPH: Record<string, string> = {
  饮料: '饮',
  零食: '零',
  乳品: '乳',
  方便食品: '面'
};

/** 相对路径补上 API 域名（后台上传的图片是 /api/v2/media/... 或 /admin/sku-demo/...） */
function absoluteImageUrl(url?: string | null): string {
  const value = String(url || '').trim();
  if (!value) return '';
  if (/^https?:\/\//i.test(value) || value.startsWith('//')) return value;
  const base = (API_BASE_URL || '').replace(/\/$/, '');
  return `${base}${value.startsWith('/') ? value : '/' + value}`;
}

export function productThumb(p: DeviceProduct): string {
  // 真机调试优先用包内 static（不依赖局域网拉 /admin/sku-demo）
  const local = LOCAL_SKU_IMAGES[p.skuId];
  if (local) return local;
  return absoluteImageUrl(p.imageUrl) || '';
}

/** 无本地图时的中文占位（仅作兜底，不替代真实商品图） */
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
