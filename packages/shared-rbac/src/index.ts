/**
 * 三端权限匹配（分域，不混码）。
 *
 * - ops:* 仅运营后台；ops:admin 超管短路
 * - merchant:* 仅商户端（再叠加功能包，见 MerchantFeaturePacks）
 * - 与后端 PermissionService 分段通配规则同源；改规则时请两端同步
 */

/** 权限域前缀约定（菜单/鉴权不得跨域挂载） */
export const PERMISSION_REALMS = {
  ops: 'ops',
  merchant: 'merchant'
} as const;

export type PermissionRealm = (typeof PERMISSION_REALMS)[keyof typeof PERMISSION_REALMS];

/** 运营超管权限码：持有则匹配任意 code */
export const OPS_ADMIN_PERM = 'ops:admin';

/**
 * 若依风格：精确码，或分段通配（a:b:c ← a:b:* / a:*）。
 * @param granted 用户已授权权限码列表
 * @param code 待校验权限码；空/缺省视为通过（前端「无 perm 约束」）
 */
export function matchPermission(
  granted: readonly string[] | null | undefined,
  code?: string | null
): boolean {
  if (!code) return true;
  const perms = granted || [];
  if (perms.includes(OPS_ADMIN_PERM)) return true;
  if (perms.includes(code)) return true;
  const segments = code.split(':');
  for (let i = segments.length - 1; i >= 1; i--) {
    const wildcard = `${segments.slice(0, i).join(':')}:*`;
    if (perms.includes(wildcard)) return true;
  }
  return false;
}

/** 权限码所属域；无法识别则 null */
export function permissionRealm(code?: string | null): PermissionRealm | null {
  if (!code) return null;
  const head = code.split(':')[0];
  if (head === PERMISSION_REALMS.ops) return PERMISSION_REALMS.ops;
  if (head === PERMISSION_REALMS.merchant) return PERMISSION_REALMS.merchant;
  return null;
}

/**
 * 商户功能包前缀（与 MerchantFeaturePacks 保持一致）。
 * 门户 / nav 等包无关码返回 null。
 */
const FIELD_PREFIXES = [
  'merchant:devices:',
  'merchant:slots:',
  'merchant:temp:',
  'merchant:replenishment:',
  'merchant:alerts:',
  'merchant:inventory:'
] as const;

const BIZ_PREFIXES = [
  'merchant:orders:',
  'merchant:splits:',
  'merchant:settlements:',
  'merchant:pricing:',
  'merchant:disputes:',
  'merchant:reports:',
  'merchant:trend:',
  'merchant:analytics:',
  'merchant:coupon:',
  'merchant:line-wallet:',
  'merchant:wallet:'
] as const;

const TEAM_PREFIXES = ['merchant:profile:', 'merchant:users:'] as const;

export type MerchantPackId = 'field' | 'biz' | 'team';

export function isMerchantPackAgnostic(permCode?: string | null): boolean {
  if (!permCode || !permCode.trim()) return true;
  const code = permCode.trim();
  return (
    code === 'merchant' || code === 'merchant:portal:access' || code.startsWith('merchant:nav:')
  );
}

function matchesPrefix(code: string, prefixes: readonly string[]): boolean {
  for (const prefix of prefixes) {
    if (code.startsWith(prefix) || code === prefix.slice(0, -1)) return true;
  }
  return false;
}

/** @returns field / biz / team，或 null（包无关 / 未知） */
export function merchantPackForPerm(permCode?: string | null): MerchantPackId | null {
  if (!permCode || isMerchantPackAgnostic(permCode)) return null;
  const code = permCode.trim().toLowerCase();
  if (matchesPrefix(code, FIELD_PREFIXES)) return 'field';
  if (matchesPrefix(code, BIZ_PREFIXES)) return 'biz';
  if (matchesPrefix(code, TEAM_PREFIXES)) return 'team';
  return null;
}
