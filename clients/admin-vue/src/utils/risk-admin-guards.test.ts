import { describe, expect, it } from 'vitest';
import {
  canDisposeRiskEvent,
  normalizeDispositionNote,
  parseMaxDisputes,
  validDispositionNote
} from './risk-admin-guards';

describe('risk admin guards', () => {
  it('仅 OPEN 的 INFO/WARN/BLOCK 可人工处置', () => {
    for (const severity of ['INFO', 'WARN', 'BLOCK']) {
      expect(canDisposeRiskEvent({ dispositionStatus: 'OPEN', severity })).toBe(true);
    }
    expect(canDisposeRiskEvent({ dispositionStatus: 'ACKED', severity: 'WARN' })).toBe(false);
    expect(canDisposeRiskEvent({ dispositionStatus: 'AUTO_CLEARED', severity: 'INFO' })).toBe(
      false
    );
    expect(canDisposeRiskEvent({ dispositionStatus: 'OPEN', severity: 'CRITICAL' })).toBe(false);
  });

  it('备注去空白且必填、最长 256', () => {
    expect(normalizeDispositionNote('  已复核  ')).toBe('已复核');
    expect(validDispositionNote('   ')).toBe(false);
    expect(validDispositionNote('已复核')).toBe(true);
    expect(validDispositionNote('x'.repeat(256))).toBe(true);
    expect(validDispositionNote('x'.repeat(257))).toBe(false);
  });

  it('运行阈值只接受正整数文本', () => {
    expect(parseMaxDisputes(' 3 ')).toBe(3);
    for (const value of ['', '0', '-1', '1.5', 'abc', '9007199254740992']) {
      expect(parseMaxDisputes(value)).toBeNull();
    }
    expect(parseMaxDisputes(3)).toBe(3);
    expect(parseMaxDisputes(0)).toBeNull();
  });
});
