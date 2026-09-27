/**
 * 解析订单列表 lineSummary（后端形如：名称 x数量 ·货道A1 @批次、… 等N件）。
 * 列表「商品 / 货道」分列展示，避免货道黏在商品名上。
 */

export type ParsedGoodsLine = { title: string; qty: string; slot: string };

const EXTRA_SUFFIX_RE = /等\d{1,4}(?:种|件)\s*$/;
const SLOT_RE = /\s*·货道([A-Za-z0-9_-]+)\s*/;

export function parseGoodsLines(summary: string | null | undefined): ParsedGoodsLine[] {
  if (!summary?.trim()) return [];
  const trimmed = summary.trim();
  const extraSuffix = EXTRA_SUFFIX_RE.exec(trimmed);
  const base = (extraSuffix ? trimmed.slice(0, extraSuffix.index) : trimmed).trim();
  if (!base) return [];
  return base
    .split('、')
    .map((part) => {
      let raw = part.trim();
      if (!raw) return { title: '', qty: '', slot: '' };
      // 批次：… @BATCH
      const atIdx = raw.lastIndexOf(' @');
      if (atIdx > 0) raw = raw.slice(0, atIdx).trim();
      // 货道：… ·货道A1（可能在数量前后，统一剥掉）
      let slot = '';
      const slotMatch = SLOT_RE.exec(raw);
      if (slotMatch) {
        slot = slotMatch[1] || '';
        raw = (
          raw.slice(0, slotMatch.index) + raw.slice(slotMatch.index + slotMatch[0].length)
        ).trim();
      }
      const xIdx = raw.lastIndexOf(' x');
      if (xIdx > 0) {
        const qtyPart = raw.slice(xIdx + 2).trim();
        if (/^\d{1,6}$/.test(qtyPart)) {
          return { title: raw.slice(0, xIdx).trim(), qty: qtyPart, slot };
        }
      }
      return { title: raw.trim(), qty: '', slot };
    })
    .filter((g) => g.title);
}

export function goodsSlotsFromSummary(summary: string | null | undefined): string[] {
  return parseGoodsLines(summary)
    .map((g) => g.slot)
    .filter(Boolean);
}
