import { describe, expect, it } from 'vitest';
import {
  classifyMpTransportError,
  createMpApiError,
  isMpAuthFailure,
  isRetriableMpTransportError
} from '@aicabinet/shared-uni/request';

/**
 * 2026-10-23 第四轮审计：401 刷新链路只有「刷新端点明确 401」才登出，
 * 弱网/超时类失败不得误杀会话——判据即 isMpAuthFailure。
 * 负向对照：无 status/code 的传输层错误必须判 false（否则又会走登出）。
 */
describe('isMpAuthFailure（刷新失败是否登出的判据）', () => {
  it('401/403 或显式 UNAUTHORIZED/FORBIDDEN → true', () => {
    expect(isMpAuthFailure(createMpApiError('登录已失效', 401, 'UNAUTHORIZED'))).toBe(true);
    expect(isMpAuthFailure(createMpApiError('x', 403, 'FORBIDDEN'))).toBe(true);
  });
  it('传输层错误（无 status/code）→ false：弱网刷新失败不登出', () => {
    expect(isMpAuthFailure(createMpApiError('网络不太稳定，请稍后再试'))).toBe(false);
    expect(isMpAuthFailure(new Error('request:fail timeout'))).toBe(false);
  });
  it('业务错误 → false', () => {
    expect(isMpAuthFailure(createMpApiError('余额不足', 400, 'BALANCE_INSUFFICIENT'))).toBe(false);
  });
});

/**
 * 2026-10-23 审计残留：isRetriableMpTransportError 原先只靠中文文案 includes 判定，
 * 文案一改重试语义就静默漂移。现 fail 通道写入结构化 code，文案匹配仅作旧路径兜底。
 * 负向对照：改掉中文文案后，结构化 code 路径必须仍然可重试。
 */
describe('classifyMpTransportError', () => {
  it('timeout 类 errMsg → TIMEOUT', () => {
    expect(classifyMpTransportError('request:fail timeout')).toBe('TIMEOUT');
  });
  it('request:fail 网络层 → NETWORK', () => {
    expect(classifyMpTransportError('request:fail -118 连接超时')).toBe('NETWORK');
    expect(classifyMpTransportError('request:fail')).toBe('NETWORK');
  });
  it('空/非传输层错误 → null', () => {
    expect(classifyMpTransportError(undefined)).toBeNull();
    expect(classifyMpTransportError('')).toBeNull();
  });
});

describe('isRetriableMpTransportError', () => {
  it('结构化 code 命中可重试（即使文案不含关键词）', () => {
    const e = createMpApiError('随便什么文案', undefined, 'MP_TRANSPORT_TIMEOUT');
    expect(isRetriableMpTransportError(e)).toBe(true);
    const e2 = createMpApiError('随便什么文案', undefined, 'MP_TRANSPORT_NETWORK');
    expect(isRetriableMpTransportError(e2)).toBe(true);
  });
  it('业务错误 code 不命中（负向对照：判据能区分传输层与业务层）', () => {
    const e = createMpApiError('余额不足，请先充值', 400, 'BALANCE_INSUFFICIENT');
    expect(isRetriableMpTransportError(e)).toBe(false);
  });
  it('401/403 鉴权失败永不重试（优先于结构化 code）', () => {
    expect(isRetriableMpTransportError(createMpApiError('x', 401, 'MP_TRANSPORT_TIMEOUT'))).toBe(
      false
    );
    expect(isRetriableMpTransportError(createMpApiError('x', 403, 'MP_TRANSPORT_NETWORK'))).toBe(
      false
    );
  });
  it('旧路径兜底：普通 Error 中文文案仍可识别', () => {
    expect(isRetriableMpTransportError(new Error('请求超时，请稍后重试'))).toBe(true);
    expect(isRetriableMpTransportError(new Error('网络不太稳定，请稍后再试'))).toBe(true);
    expect(isRetriableMpTransportError(new Error('余额不足'))).toBe(false);
  });
  it('非 Error 输入直接 false', () => {
    expect(isRetriableMpTransportError('请求超时')).toBe(false);
    expect(isRetriableMpTransportError(null)).toBe(false);
  });
});
