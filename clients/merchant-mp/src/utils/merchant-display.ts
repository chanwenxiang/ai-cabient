/**
 * 商户展示名：过滤编码损坏的纯问号名（????）。
 * 源文件请保持 UTF-8。
 */
export function isCorruptedMerchantName(name?: string | null): boolean {
  if (!name) return false;
  const n = name.trim();
  if (!n) return false;
  if (/^\?+$/.test(n)) return true;
  return (n.match(/\?/g) || []).length >= 2 && n.includes('???');
}

export function formatMerchantNames(
  list?: { merchantName?: string }[],
  emptyLabel = '未绑定商户'
): string {
  const names = (list || [])
    .map((m) => (m.merchantName || '').trim())
    .filter((n) => n && !isCorruptedMerchantName(n));
  return names.length ? names.join('、') : emptyLabel;
}
