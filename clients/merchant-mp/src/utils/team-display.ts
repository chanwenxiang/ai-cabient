/**
 * 团队成员展示：角色/状态一律中文，禁止把 roleKey 铺到界面。
 */
const ROLE_LABELS: Record<string, string> = {
  merchant: '商户管理员',
  merchant_admin: '商户管理员',
  merchant_store_manager: '店长',
  merchant_finance: '财务',
  merchant_replenisher: '补货员',
  merchant_staff: '店员'
};

/** 像 merchant_store_manager 这种内部码，不能当中文名展示。 */
export function isInternalRoleKey(value?: string | null): boolean {
  const s = (value || '').trim();
  return /^[a-z][a-z0-9_]{2,}$/.test(s);
}

export function teamRoleLabel(roleKey?: string | null, roleName?: string | null): string {
  const name = (roleName || '').trim();
  if (name && !isInternalRoleKey(name)) return name;
  const key = (roleKey || '').trim();
  if (key && ROLE_LABELS[key]) return ROLE_LABELS[key];
  return '成员';
}

export function teamStatusLabel(status?: string | null): string {
  return status === 'INACTIVE' ? '已停用' : '启用中';
}

export function teamMemberTitle(
  displayName?: string | null,
  phoneNumber?: string | null,
  userId?: number | null
): string {
  const name = (displayName || '').trim();
  if (name) return name;
  const phone = (phoneNumber || '').trim();
  if (phone) return phone;
  return userId != null ? `用户 ${userId}` : '成员';
}
