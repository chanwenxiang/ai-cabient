/**
 * npx tsx packages/shared-rbac/src/match-permission.spec.ts
 */
import { matchPermission, merchantPackForPerm, permissionRealm, OPS_ADMIN_PERM } from './index.ts';

function assert(cond: unknown, msg: string) {
  if (!cond) throw new Error(msg);
}

assert(matchPermission([], undefined) === true, 'empty code => true');
assert(matchPermission([], '') === true, 'blank code => true');
assert(matchPermission([OPS_ADMIN_PERM], 'ops:order:list') === true, 'ops:admin short-circuit');
assert(
  matchPermission([OPS_ADMIN_PERM], 'merchant:orders:list') === false,
  'ops:admin must not cross merchant realm'
);
assert(matchPermission(['ops:order:list'], 'ops:order:list') === true, 'exact');
assert(matchPermission(['ops:rbac:role:*'], 'ops:rbac:role:add') === true, 'segment wildcard');
assert(matchPermission(['ops:rbac:*'], 'ops:rbac:role:add') === true, 'parent wildcard');
assert(matchPermission(['ops:order:list'], 'ops:order:refund') === false, 'no match');
assert(
  matchPermission(['merchant:replenishment:*'], 'merchant:replenishment:view') === true,
  'merchant wildcard'
);

assert(permissionRealm('ops:dashboard:view') === 'ops', 'ops realm');
assert(permissionRealm('merchant:orders:list') === 'merchant', 'merchant realm');
assert(permissionRealm('foo:bar') === null, 'unknown realm');

assert(merchantPackForPerm('merchant:devices:list') === 'field', 'field pack');
assert(merchantPackForPerm('merchant:wallet:view') === 'biz', 'biz pack');
assert(merchantPackForPerm('merchant:users:list') === 'team', 'team pack');
assert(merchantPackForPerm('merchant:portal:access') === null, 'pack-agnostic');
assert(merchantPackForPerm('merchant:nav:biz') === null, 'nav pack-agnostic');

console.log('match-permission.spec: ok');
