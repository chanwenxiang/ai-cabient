import type { DeviceProduct } from '@aicabinet/shared-types';
import { API_BASE_URL } from '@/config/api';

const CATEGORY_GLYPH: Record<string, string> = {
  饮料: '饮',
  零食: '零',
  乳品: '乳',
  方便食品: '面'
};

/** 相对路径补上 API 域名（后台上传的图片是 /api/v2/media/... 相对路径） */
function absoluteImageUrl(url?: string | null): string {
  const value = String(url || '').trim();
  if (!value) return '';
  if (/^https?:\/\//i.test(value) || value.startsWith('//')) return value;
  const base = (API_BASE_URL || '').replace(/\/$/, '');
  return `${base}${value.startsWith('/') ? value : '/' + value}`;
}

/**
 * 商品图：仅取后台上传地址。
 * 演示 SKU 本地图（static/sku/*.jpg）已于 2026-10-24 按产品拍板整体下线——
 * 正式商品图由后台在商品管理上传，本地兜底图永远不会被真实数据命中。
 * 无图时调用方用 productGlyph 首字占位（见 pages/index）。
 */
export function productThumb(p: DeviceProduct): string {
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
