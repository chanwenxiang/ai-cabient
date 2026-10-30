import { describe, expect, it } from 'vitest';
import { matchDeviceKeyword } from './prep-filter';

const CABINET = { deviceName: '张江万科店柜机', deviceId: 'EG943322621391' };

describe('matchDeviceKeyword', () => {
  it('空关键字不过滤', () => {
    expect(matchDeviceKeyword('', CABINET)).toBe(true);
    expect(matchDeviceKeyword('   ', CABINET)).toBe(true);
  });

  it('按柜名模糊匹配', () => {
    expect(matchDeviceKeyword('张江', CABINET)).toBe(true);
    expect(matchDeviceKeyword('张江9', CABINET)).toBe(false);
  });

  it('按编码匹配且不区分大小写', () => {
    expect(matchDeviceKeyword('eg9433', CABINET)).toBe(true);
    expect(matchDeviceKeyword('EG9433', CABINET)).toBe(true);
    expect(matchDeviceKeyword('94332262', CABINET)).toBe(true);
  });

  it('关键字两侧空白忽略', () => {
    expect(matchDeviceKeyword('  万科  ', CABINET)).toBe(true);
  });

  it('字段缺失不报错', () => {
    expect(matchDeviceKeyword('张江', {})).toBe(false);
    expect(matchDeviceKeyword('', { deviceName: null, deviceId: null })).toBe(true);
  });
});
