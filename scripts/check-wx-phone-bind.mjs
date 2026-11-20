#!/usr/bin/env node
/**
 * 门禁：微信一键登录「手机号授权」必须真的落到账号上（2026-11-20 真机反馈 MA-371）。
 *
 * 实炸：`loginOrCreateByOpenId` 命中既有 openid 账号时直接 `tokenFor` 返回，
 * 而 `resolveWxPhoneNumber` 已经用 phoneCode 向微信换出了真实手机号 ⇒ 值被整段丢弃，
 * 库里仍是游客占位符 `wx{userId}`，个人中心一直显示「登录手机号未绑定」。
 *
 * 只钉「有没有调用」不够（会被无脑加个空调用糊过去），所以同时钉三道边界：
 * 已绑定真实号不换绑 / 授权号被他人占用要 409 / 占位符才允许覆盖。
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const AUTH_SERVICE_FILE = join(
  ROOT,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/AuthService.java'
);
const TAG = '[check-wx-phone-bind]';

/** 注释替换成等长空格：既不误判注释里的样例代码，也不破坏后续报错定位。 */
export function maskComments(raw) {
  return (
    raw
      .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
      // `//` 只在前置空白后成立，避免把 "https://..." 字符串当注释
      .replace(/(^|[ \t])\/\/[^\n]*/gm, (m, lead) => lead + ' '.repeat(m.length - lead.length))
  );
}

/** 取 `signature` 起的方法体（大括号配平），找不到返回 null。 */
export function methodBody(src, signature) {
  const start = src.indexOf(signature);
  if (start < 0) return null;
  const open = src.indexOf('{', start);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < src.length; i += 1) {
    const ch = src[i];
    if (ch === '{') depth += 1;
    else if (ch === '}') {
      depth -= 1;
      if (depth === 0) return src.slice(open, i + 1);
    }
  }
  return null;
}

export function wxPhoneBindProblems(raw) {
  const src = maskComments(raw);
  const problems = [];

  const login = methodBody(src, 'public LoginResponse loginOrCreateByOpenId(');
  if (!login) {
    problems.push('找不到 loginOrCreateByOpenId（结构已变，门禁失去判别力）');
  } else {
    if (!/bindAuthorizedPhone\s*\(/.test(login)) {
      problems.push(
        'openid 命中分支必须调用 bindAuthorizedPhone（否则 phoneCode 换出的手机号被丢弃）'
      );
    }
    // 旧实炸形态：命中既有账号直接签发 token
    if (/tokenFor\s*\(\s*byOpenId\.get\(\)\s*\)/.test(login)) {
      problems.push('openid 命中分支不得直接 tokenFor(byOpenId.get())（MA-371 原缺陷形态）');
    }
  }

  // wxLogin 必须把服务端换出的手机号传下去（而不是只传 openid）
  const wxLogin = methodBody(src, 'public LoginResponse wxLogin(');
  if (!wxLogin) {
    problems.push('找不到 wxLogin（结构已变）');
  } else if (
    !/loginOrCreateByOpenId\s*\(\s*session\.openId\(\)\s*,\s*resolveWxPhoneNumber/.test(wxLogin)
  ) {
    problems.push('wxLogin 必须把 resolveWxPhoneNumber(request) 传给 loginOrCreateByOpenId');
  }

  const bind = methodBody(src, 'private UserInfo bindAuthorizedPhone(');
  if (!bind) {
    problems.push('找不到 bindAuthorizedPhone 实现');
  } else {
    if (!/isRealPhoneNumber\s*\(\s*bound\.getPhoneNumber\(\)\s*\)/.test(bind)) {
      problems.push('bindAuthorizedPhone 必须先判「已有真实手机号不静默换绑」');
    }
    if (!/findByPhoneNumber\s*\(\s*phone\s*\)/.test(bind)) {
      problems.push('bindAuthorizedPhone 必须查授权号是否已被他人占用');
    }
    if (!/HttpStatus\.CONFLICT/.test(bind)) {
      problems.push('bindAuthorizedPhone 遇手机号被他人占用必须 409 拒绝抢占');
    }
    if (!/findByIdForUpdate\s*\(/.test(bind)) {
      problems.push('bindAuthorizedPhone 必须加行锁后再写（否则并发双账号抢同一手机号）');
    }
  }
  return problems;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const problems = wxPhoneBindProblems(readFileSync(AUTH_SERVICE_FILE, 'utf8'));
  if (problems.length) {
    console.error(`${TAG} FAIL:`);
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log(`${TAG} OK：一键授权手机号在既有 openid 账号上也会落库，且不抢占他人号码`);
}
