"use strict";
const LOCAL_SKU_IMAGES = {
  "SKU-DEMO-001": "/static/sku/cola.png",
  "SKU-SODA-001": "/static/sku/sprite.png",
  "SKU-WATER-001": "/static/sku/water.png",
  "SKU-SNACK-001": "/static/sku/chips.png",
  "SKU-MILK-001": "/static/sku/milk.png",
  "SKU-NOODLE-001": "/static/sku/noodle.png"
};
const CATEGORY_GLYPH = {
  饮料: "饮",
  零食: "零",
  乳品: "乳",
  方便食品: "面"
};
function productThumb(p) {
  return LOCAL_SKU_IMAGES[p.skuId] || "";
}
function productGlyph(p) {
  const fromCat = CATEGORY_GLYPH[p.category || ""];
  if (fromCat) return fromCat;
  const name = String(p.skuName || "").trim();
  if (name) return name.slice(0, 1);
  return "品";
}
exports.productGlyph = productGlyph;
exports.productThumb = productThumb;
