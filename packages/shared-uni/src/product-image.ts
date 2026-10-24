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
