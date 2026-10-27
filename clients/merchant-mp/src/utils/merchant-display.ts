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

export type MerchantBindingState = 'bound' | 'unbound' | 'unknown';

/**
 * CB-070：`GET /merchant/me` 自己就要求 merchant:portal:access，所以「缺功能权限」与「平台管理员误登门户」
 * 会直接 403、前端拿不到任何资料；此时把空商户名说成「未绑定商户」是凭空造成因。
 * 只有真的取到了资料（在线或弱网缓存）才有资格断言绑定状态，否则交回 unknown 让错误态自己说话。
 */
export function merchantBindingState(
  profile: { merchants?: unknown[] } | null | undefined
): MerchantBindingState {
  if (!profile) return 'unknown';
  return profile.merchants?.length ? 'bound' : 'unbound';
}
