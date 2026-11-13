import { API_BASE_URL } from './api-base';

/** OSS 缩略参数选项（P3-9）。仅对直连 OSS 的图片 URL 生效。 */
export interface ThumbOptions {
  width?: number;
  height?: number;
  mode?: 'fill' | 'lfit' | 'mfit' | 'fixed';
  quality?: number;
}

function isOssHost(host: string): boolean {
  return host.includes('aliyuncs.com') || host.startsWith('oss-');
}

/**
 * P3-9：对直连 OSS 的图片 URL 追加 `x-oss-process=image/resize` 下采样参数。
 * 移动端列表 / 封面 / 横幅不必拉原图，省首屏带宽。
 * 仅当 host 是 OSS（含 `aliyuncs.com` 或 `oss-` 前缀）才生效；
 * 已带 `x-oss-process` 的 URL 不重复加；后端相对路径 / 非 OSS 域名原样返回。
 */
function appendOssProcess(url: string, opts: ThumbOptions): string {
  const host = url
    .replace(/^https?:\/\//i, '')
    .split('/')[0]
    .split(':')[0];
  if (!isOssHost(host)) return url;
  if (/[?&]x-oss-process=/i.test(url)) return url;
  const w = opts.width ? `w_${opts.width}` : '';
  const h = opts.height ? `,h_${opts.height}` : '';
  const m = opts.mode ?? (opts.height ? 'fill' : 'lfit');
  const q = opts.quality ? `,q_${opts.quality}` : '';
  const process = `image/resize,m_${m},${w}${h}${q}`;
  const sep = url.includes('?') ? '&' : '?';
  return `${url}${sep}x-oss-process=${process}`;
}

/** 相对路径补上 API 域名（后台上传的图片是 /api/v2/media/... 相对路径）。两端共用一份。 */
export function absoluteImageUrl(url?: string | null, opts?: ThumbOptions): string {
  const value = String(url || '').trim();
  if (!value) return '';
  if (/^https?:\/\//i.test(value) || value.startsWith('//')) {
    return opts ? appendOssProcess(value, opts) : value;
  }
  const base = (API_BASE_URL || '').replace(/\/$/, '');
  return `${base}${value.startsWith('/') ? value : '/' + value}`;
}

/** 列表 / 封面 / 横幅场景的 OSS 缩略图（P3-9）：直连 OSS 时按宽高下采样，省首屏带宽。 */
export function thumbUrl(url?: string | null, width?: number, height?: number): string {
  return absoluteImageUrl(url, { width, height });
}

/**
 * 三端共用订单文本解析。
 *
 * 历史：本模块曾带「演示 SKU 本地图」兜底（skuImageFor + 名称规则 + default.jpg），
 * 2026-10-24 按产品拍板整体下线——演示图与正式商品图（后台上传）双轨并行只会
 * 让包体背着 ~860KB 永远不会被真实数据命中的照片，且订单行 DTO 本身不带
 * imageUrl，本地兜底图实际展示面为零。商品图改由各端在拿到后端 imageUrl 后
 * 自行渲染（见 consumer product-thumb.ts / merchant useReplenishmentDisplay.ts
 * 的 absoluteImageUrl）；无图时用首字占位（各页 product-mark / item-mark）。
 */

/** 从订单摘要文本里提取第一个商品名（lineSummary 形如「可口可乐 x1、矿泉水 x1」） */
export function firstProductName(lineSummary?: string | null): string {
  const text = cleanLineSummary(lineSummary);
  if (!text) return '';
  const first = text.split(/[、,，;；]/)[0] || text;
  return first.replace(/\s*x\s*\d+.*$/i, '').trim();
}

/** 去掉摘要里的批次/货道内部码（如 @B-WH-SPRITE-01、@L07-…） */
export function cleanLineSummary(summary?: string | null): string {
  if (summary == null) return '';
  return String(summary)
    .replace(/\s*@[^\s、,，;；]+/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
}
