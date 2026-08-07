"use strict";
const EMOJI_GLYPH = {
  "📋": "订",
  "🎫": "券",
  "👑": "会",
  "💰": "充",
  "🛒": "购",
  "🔥": "热",
  "💳": "余",
  "❓": "助",
  "🔧": "修",
  "💬": "馈",
  "💚": "微",
  "💙": "支",
  "🧪": "模",
  "📱": "号",
  "🚪": "出",
  "🎁": "礼",
  "📒": "明",
  "⭐": "积",
  "🧊": "优"
};
function looksLikeEmoji(raw) {
  return /[\u2600-\u27BF]/.test(raw) || /[\uD800-\uDBFF][\uDC00-\uDFFF]/.test(raw);
}
function uiGlyph(emojiOrText, fallback = "惠") {
  const raw = String(emojiOrText || "").trim();
  if (!raw) return fallback;
  if (EMOJI_GLYPH[raw]) return EMOJI_GLYPH[raw];
  if (raw.length <= 2 && !looksLikeEmoji(raw)) return raw;
  return fallback;
}
exports.uiGlyph = uiGlyph;
