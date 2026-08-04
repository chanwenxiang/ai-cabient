/**
 * 三端权限匹配（分域，不混码）。
 *
 * - ops:* 仅运营后台；ops:admin 超管短路
 * - merchant:* 仅商户端（再叠加功能包，见 MerchantFeaturePacks）
 * - 与后端 PermissionService 分段通配规则同源；改规则时请两端同步
 */
/** 权限域前缀约定（菜单/鉴权不得跨域挂载） */
export declare const PERMISSION_REALMS: {
    readonly ops: "ops";
    readonly merchant: "merchant";
};
export type PermissionRealm = (typeof PERMISSION_REALMS)[keyof typeof PERMISSION_REALMS];
/** 运营超管权限码：持有则匹配任意 code */
export declare const OPS_ADMIN_PERM = "ops:admin";
/**
 * 若依风格：精确码，或分段通配（a:b:c ← a:b:* / a:*）。
 * @param granted 用户已授权权限码列表
 * @param code 待校验权限码；空/缺省视为通过（前端「无 perm 约束」）
 */
export declare function matchPermission(granted: readonly string[] | null | undefined, code?: string | null): boolean;
/** 权限码所属域；无法识别则 null */
export declare function permissionRealm(code?: string | null): PermissionRealm | null;
export type MerchantPackId = 'field' | 'biz' | 'team';
export declare function isMerchantPackAgnostic(permCode?: string | null): boolean;
/** @returns field / biz / team，或 null（包无关 / 未知） */
export declare function merchantPackForPerm(permCode?: string | null): MerchantPackId | null;
