import { describe, expect, it } from 'vitest';
import { keepRowsInOrg, orgDeviceScope } from './org-scope';

const devices = [
  { deviceId: 'CAB-A1', merchantId: 'MCH-A' },
  { deviceId: 'CAB-A2', merchantId: 'MCH-A' },
  { deviceId: 'CAB-B1', merchantId: 'MCH-B' }
];

type Row = { deviceId?: string; title: string };
const rows: Row[] = [
  { deviceId: 'CAB-A1', title: 'A 的离线' },
  { deviceId: 'CAB-B1', title: 'B 的离线' },
  { title: '账号级提醒（无柜机）' }
];

describe('orgDeviceScope', () => {
  it('未选组织时返回 null（不收窄）', () => {
    expect(orgDeviceScope('', devices)).toBeNull();
    expect(orgDeviceScope(undefined, devices)).toBeNull();
  });

  it('设备表为空时退化为不收窄，避免把待办静默清空', () => {
    expect(orgDeviceScope('MCH-B', [])).toBeNull();
  });

  it('只收集所选组织下的柜机，大小写与空白按商户号精确匹配', () => {
    expect([...orgDeviceScope('MCH-B', devices)!]).toEqual(['CAB-B1']);
    expect([...orgDeviceScope(' MCH-A ', devices)!]).toEqual(['CAB-A1', 'CAB-A2']);
    expect([...(orgDeviceScope('mch-b', devices) as Set<string>)]).toEqual([]);
  });
});

describe('keepRowsInOrg', () => {
  it('scope 为 null 时原样返回', () => {
    expect(keepRowsInOrg(rows, null, (r) => r.deviceId)).toEqual(rows);
  });

  it('剔除其它组织的行，保留无 deviceId 的行', () => {
    const scope = orgDeviceScope('MCH-B', devices);
    expect(keepRowsInOrg(rows, scope, (r) => r.deviceId).map((r) => r.title)).toEqual([
      'B 的离线',
      '账号级提醒（无柜机）'
    ]);
  });

  it('组织内没有任何柜机时，只剩无柜机归属的行', () => {
    const scope = orgDeviceScope('MCH-NONE', devices);
    expect(keepRowsInOrg(rows, scope, (r) => r.deviceId).map((r) => r.title)).toEqual([
      '账号级提醒（无柜机）'
    ]);
  });
});
