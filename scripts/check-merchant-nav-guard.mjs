#!/usr/bin/env node
/** 纯函数自测：merchant 登录路径判定（无 uni 运行时）。 */
import assert from 'node:assert/strict';

/** 与 merchant-api.isMerchantLoginPath 保持同步 */
function isMerchantLoginPath(url) {
  const path = String(url || '')
    .split('?')[0]
    .replace(/^\/+/, '')
    .replace(/\/+$/, '');
  return path === 'pages/login/login' || path.endsWith('/pages/login/login');
}

assert.equal(isMerchantLoginPath('/pages/login/login'), true);
assert.equal(isMerchantLoginPath('pages/login/login'), true);
assert.equal(isMerchantLoginPath('/pages/login/login?x=1'), true);
assert.equal(isMerchantLoginPath('/pages/home/home'), false);
assert.equal(isMerchantLoginPath(''), false);
console.log('merchant-nav-guard path helpers OK');
