import { describe, expect, it } from 'vitest';

import { cleanOfficialSpec, officialUnitTail } from './barcode-spec-clean';

/** CB-025 追加：官方规格轻度清洗——只去包装形态英文前缀，其余原文不动 */
describe('cleanOfficialSpec', () => {
  it('去掉常见包装形态英文前缀 + 尾缀单位（单位是独立字段，不在规格里重复）', () => {
    expect(cleanOfficialSpec('CAN310mL/罐')).toBe('310mL');
    expect(cleanOfficialSpec('BOT550ml/瓶')).toBe('550ml');
    expect(cleanOfficialSpec('BTL500mL')).toBe('500mL');
    expect(cleanOfficialSpec('BOX120g/盒')).toBe('120g');
    expect(cleanOfficialSpec('BAG70g')).toBe('70g');
    expect(cleanOfficialSpec('PET500mL/瓶')).toBe('500mL');
    expect(cleanOfficialSpec('TIN340g/罐')).toBe('340g');
    expect(cleanOfficialSpec('JAR200g')).toBe('200g');
    expect(cleanOfficialSpec('CUP65g')).toBe('65g');
  });

  it('容忍前缀与数字之间的空格', () => {
    expect(cleanOfficialSpec('CAN 310mL/罐')).toBe('310mL');
  });

  it('不含包装前缀的官方原文：只去尾缀单位，其余保留', () => {
    expect(cleanOfficialSpec('310mL/罐')).toBe('310mL');
    expect(cleanOfficialSpec('500ml')).toBe('500ml');
    expect(cleanOfficialSpec('250g*5')).toBe('250g*5');
  });

  it('白名单外的大写缩写紧贴数字不误删（如 pH 值 PH5.5）', () => {
    expect(cleanOfficialSpec('PH5.5')).toBe('PH5.5');
    expect(cleanOfficialSpec('ML100')).toBe('ML100');
  });

  it('超过 2 字的尾缀不是单位词，保留官方原文', () => {
    expect(cleanOfficialSpec('340g/浓缩液')).toBe('340g/浓缩液');
  });

  it('只去一次前缀，剩余原文含字母不受影响', () => {
    expect(cleanOfficialSpec('CAN310mLCAN')).toBe('310mLCAN');
  });

  it('空白串按空处理（上游 res.spec 已 trim，纯空白等价于空）', () => {
    expect(cleanOfficialSpec('')).toBe('');
    expect(cleanOfficialSpec('   ')).toBe('');
  });
});

/** 单位顺带带出（2026-10-12）：官方规格「/罐」式尾缀 → 单位暗示，采纳与否由字典白名单决定 */
describe('officialUnitTail', () => {
  it('提取「/」后的短尾缀', () => {
    expect(officialUnitTail('CAN310mL/罐')).toBe('罐');
    expect(officialUnitTail('310mL/瓶')).toBe('瓶');
    expect(officialUnitTail('BOX120g/盒')).toBe('盒');
  });

  it('无尾缀或不合形态返回 null', () => {
    expect(officialUnitTail('500ml')).toBeNull();
    expect(officialUnitTail('250g*5')).toBeNull();
    expect(officialUnitTail('CAN310mL/ 罐')).toBeNull();
    expect(officialUnitTail('')).toBeNull();
  });

  it('尾缀超过 2 字（非单位词形态）不取；是否真为单位由调用方对照字典白名单', () => {
    expect(officialUnitTail('340g/听说')).toBe('听说');
    expect(officialUnitTail('340g/浓缩液')).toBeNull();
  });
});
