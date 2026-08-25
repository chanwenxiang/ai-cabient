/**
 * 三端共用商品图解析：订单/明细里没有远程图时，用本地演示图兜底。
 * 正式商品图由运营后台在商品管理上传（三端共用），此处仅作兜底。
 */

/** 演示 SKU 本地图（与 scripts/render-mp-assets.mjs 同步的商品照） */
const LOCAL_SKU_IMAGES: Record<string, string> = {
  'SKU-DEMO-001': '/static/sku/cola.jpg',
  'SKU-SODA-001': '/static/sku/sprite.jpg',
  'SKU-WATER-001': '/static/sku/water.jpg',
  'SKU-SNACK-001': '/static/sku/chips.jpg',
  'SKU-MILK-001': '/static/sku/milk.jpg',
  'SKU-NOODLE-001': '/static/sku/noodle.jpg'
};

const NAME_IMAGE_RULES: Array<[RegExp, string]> = [
  [/可口可乐|可乐|cola/i, '/static/sku/cola.jpg'],
  [/雪碧|sprite/i, '/static/sku/sprite.jpg'],
  [/矿泉水|纯净水|water/i, '/static/sku/water.jpg'],
  [/薯片|chips/i, '/static/sku/chips.jpg'],
  [/牛奶|milk/i, '/static/sku/milk.jpg'],
  [/牛肉面|方便面|泡面|noodle/i, '/static/sku/noodle.jpg']
];

const DEFAULT_SKU_IMAGE = '/static/sku/default.jpg';

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

/**
 * 解析商品图：优先 skuId 本地演示图，其次按商品名匹配，最后返回默认图。
 * 有后台 imageUrl 时请优先使用它，本函数仅作本地兜底。
 */
export function skuImageFor(
  skuId?: string | null,
  skuName?: string | null,
  lineSummary?: string | null
): string {
  if (skuId && LOCAL_SKU_IMAGES[skuId]) {
    return LOCAL_SKU_IMAGES[skuId];
  }
  const name = String(skuName || '').trim() || firstProductName(lineSummary);
  if (name) {
    for (const [rule, image] of NAME_IMAGE_RULES) {
      if (rule.test(name)) {
        return image;
      }
    }
  }
  return DEFAULT_SKU_IMAGE;
}
