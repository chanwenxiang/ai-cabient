"use strict";
const LOCAL_SKU_IMAGES = {
  "SKU-DEMO-001": "/static/sku/cola.png",
  "SKU-SODA-001": "/static/sku/sprite.png",
  "SKU-WATER-001": "/static/sku/water.png",
  "SKU-SNACK-001": "/static/sku/chips.png",
  "SKU-MILK-001": "/static/sku/milk.png",
  "SKU-NOODLE-001": "/static/sku/noodle.png"
};
const CATEGORY_EMOJI = {
  饮料: "🥤",
  零食: "🍟",
  乳品: "🥛",
  方便食品: "🍜"
};
function productThumb(p) {
  return LOCAL_SKU_IMAGES[p.skuId] || "";
}
function productEmoji(p) {
  return CATEGORY_EMOJI[p.category || ""] || "🛒";
}
exports.productEmoji = productEmoji;
exports.productThumb = productThumb;
