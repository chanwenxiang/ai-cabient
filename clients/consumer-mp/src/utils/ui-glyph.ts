/** Map marketing/cover emoji (and a few aliases) to short Chinese glyphs for UI marks. */
const EMOJI_GLYPH: Record<string, string> = {
  '📋': '订',
  '🎫': '券',
  '👑': '会',
  '💰': '充',
  '🛒': '购',
  '🔥': '热',
  '💳': '余',
  '❓': '助',
  '🔧': '修',
  '💬': '馈',
  '💚': '微',
  '💙': '支',
  '🧪': '模',
  '📱': '号',
  '🚪': '出',
  '🎁': '礼',
  '📒': '明',
  '⭐': '积',
  '🧊': '优'
};

function looksLikeEmoji(raw: string) {
  // BMP symbols + most emoji use surrogate pairs
  return /[\u2600-\u27BF]/.test(raw) || /[\uD800-\uDBFF][\uDC00-\uDFFF]/.test(raw);
}

export function uiGlyph(emojiOrText?: string | null, fallback = '惠'): string {
  const raw = String(emojiOrText || '').trim();
  if (!raw) return fallback;
  if (EMOJI_GLYPH[raw]) return EMOJI_GLYPH[raw];
  if (raw.length <= 2 && !looksLikeEmoji(raw)) return raw;
  return fallback;
}
