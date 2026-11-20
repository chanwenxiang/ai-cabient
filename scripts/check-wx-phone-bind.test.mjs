#!/usr/bin/env node
/** `check-wx-phone-bind.mjs` 的负向夹具（2026-11-20 真机反馈 MA-371）。 */
import { readFileSync } from 'node:fs';
import { AUTH_SERVICE_FILE, wxPhoneBindProblems } from './check-wx-phone-bind.mjs';

const TAG = '[check-wx-phone-bind.test]';
let checks = 0;
function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`${TAG} 断言失败：${message}`);
}

const real = readFileSync(AUTH_SERVICE_FILE, 'utf8');
assert(real.length > 5000, '真实 AuthService.java 读取失败');
assert(wxPhoneBindProblems(real).length === 0, '真实仓面必须绿');

// ① 还原旧实炸形态：命中既有账号直接签发 token
const dropBind = real.replace(
  /return tokenFor\(bindAuthorizedPhone\(byOpenIdUser, phone\)\);/,
  'return tokenFor(byOpenIdUser);'
);
assert(dropBind !== real, '注入锚点 bindAuthorizedPhone 调用找不到');
assert(
  wxPhoneBindProblems(dropBind).some((p) => p.includes('必须调用 bindAuthorizedPhone')),
  '丢弃授权手机号必须红（MA-371 原缺陷形态）'
);

// ② wxLogin 只传 openid，不传换出的手机号
const dropPhoneArg = real.replace(
  /self\.loginOrCreateByOpenId\(session\.openId\(\), resolveWxPhoneNumber\(request\)\)/,
  'self.loginOrCreateByOpenId(session.openId(), null)'
);
assert(dropPhoneArg !== real, '注入锚点 resolveWxPhoneNumber 找不到');
assert(
  wxPhoneBindProblems(dropPhoneArg).some((p) => p.includes('resolveWxPhoneNumber')),
  'wxLogin 丢掉换号结果必须红'
);

// ③ 去掉「已有真实手机号不换绑」护栏
const noRebindGuard = real.replace(
  /if \(isRealPhoneNumber\(bound\.getPhoneNumber\(\)\)\) \{/,
  'if (false) {'
);
assert(noRebindGuard !== real, '注入锚点 isRealPhoneNumber(bound...) 找不到');
assert(
  wxPhoneBindProblems(noRebindGuard).some((p) => p.includes('不静默换绑')),
  '允许静默换绑必须红'
);

// ④ 去掉他人占用 409 护栏
const noConflict = real.replace(
  /throw new ResponseStatusException\(HttpStatus\.CONFLICT, "该手机号已绑定其他账号，请用手机号登录"\);/,
  ''
);
assert(noConflict !== real, '注入锚点 409 抛错找不到');
assert(
  wxPhoneBindProblems(noConflict).some((p) => p.includes('409')),
  '允许抢占他人手机号必须红'
);

// ⑤ 去掉行锁
const noLock = real.replace(
  /UserInfo locked = userInfoRepository\.findByIdForUpdate\(bound\.getUserId\(\)\)/,
  'UserInfo locked = bound;'
);
assert(noLock !== real, '注入锚点 findByIdForUpdate 找不到');
assert(
  wxPhoneBindProblems(noLock).some((p) => p.includes('行锁')),
  '不加行锁必须红'
);

// ⑥ 注释里的样例代码不得被当成实现（maskComments 生效证明）
const commented = real.replace(
  'return tokenFor(bindAuthorizedPhone(byOpenIdUser, phone));',
  'return tokenFor(bindAuthorizedPhone(byOpenIdUser, phone)); // 旧形态：return tokenFor(byOpenId.get());'
);
assert(wxPhoneBindProblems(commented).length === 0, '注释里的旧形态不得误判为红');

console.log(`${TAG} OK：${checks} 项断言全过（真树绿 / 五种注入红 / 注释不误判）`);
