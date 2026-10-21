import { describe, expect, it } from 'vitest';

import {
  cleanOfficialSpec,
  moveLeadingSpecToEnd,
  officialUnitTail,
  toEnglishSpecUnit
} from './barcode-spec-clean';

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

/** 2026-10-21：规格计量单位统一英文（毫升→ml、升→L、克→g、千克/公斤→kg…） */
describe('toEnglishSpecUnit', () => {
  it('中文计量单位换成英文缩写（只转换紧跟在数字后的单位）', () => {
    expect(toEnglishSpecUnit('330毫升')).toBe('330ml');
    expect(toEnglishSpecUnit('1.5升')).toBe('1.5L');
    expect(toEnglishSpecUnit('500克')).toBe('500g');
    expect(toEnglishSpecUnit('2千克')).toBe('2kg');
    expect(toEnglishSpecUnit('2公斤')).toBe('2kg');
    expect(toEnglishSpecUnit('250毫克')).toBe('250mg');
    expect(toEnglishSpecUnit('20厘米')).toBe('20cm');
    expect(toEnglishSpecUnit('5毫米')).toBe('5mm');
    expect(toEnglishSpecUnit('3米')).toBe('3m');
    expect(toEnglishSpecUnit('2平方米')).toBe('2m²');
    expect(toEnglishSpecUnit('2立方米')).toBe('2m³');
  });

  it('数字与单位之间有空格也能转换，并去掉空格', () => {
    expect(toEnglishSpecUnit('330 毫升')).toBe('330ml');
    expect(toEnglishSpecUnit('500 ml')).toBe('500ml');
  });

  it('已是英文的规格按标准大小写输出', () => {
    expect(toEnglishSpecUnit('330ML')).toBe('330ml');
    expect(toEnglishSpecUnit('1.5l')).toBe('1.5L');
    expect(toEnglishSpecUnit('250G')).toBe('250g');
  });

  it('只转换「数字+单位」，正文里的单位字不误伤', () => {
    expect(toEnglishSpecUnit('升级装')).toBe('升级装');
    expect(toEnglishSpecUnit('330毫升×24')).toBe('330ml×24');
    expect(toEnglishSpecUnit('250g*5')).toBe('250g*5');
  });

  it('空值与无单位规格原样返回', () => {
    expect(toEnglishSpecUnit('')).toBe('');
    expect(toEnglishSpecUnit('   ')).toBe('');
    expect(toEnglishSpecUnit('250')).toBe('250');
  });
});

/** 2026-10-21：官方名称常把规格放在最前（330ml雪碧柠檬味汽水）→ 挪到名称末尾 */
describe('moveLeadingSpecToEnd', () => {
  it('名称首部的规格挪到末尾（与种子数据「可口可乐 330ml」惯例一致）', () => {
    expect(moveLeadingSpecToEnd('330ml雪碧柠檬味汽水')).toBe('雪碧柠檬味汽水 330ml');
    expect(moveLeadingSpecToEnd('330 毫升雪碧柠檬味汽水')).toBe('雪碧柠檬味汽水 330ml');
    expect(moveLeadingSpecToEnd('1.5L可乐')).toBe('可乐 1.5L');
  });

  it('名称内部或末尾已有规格时不重复搬移', () => {
    expect(moveLeadingSpecToEnd('可口可乐 330ml')).toBe('可口可乐 330ml');
    expect(moveLeadingSpecToEnd('雪碧柠檬味汽水 330ml')).toBe('雪碧柠檬味汽水 330ml');
    expect(moveLeadingSpecToEnd('东方树叶茉莉花茶335ml')).toBe('东方树叶茉莉花茶335ml');
  });

  it('非「数字+单位」开头的名称原样返回（品牌数字、容量词不算规格）', () => {
    expect(moveLeadingSpecToEnd('7喜柠檬味')).toBe('7喜柠檬味');
    expect(moveLeadingSpecToEnd('24瓶装可乐')).toBe('24瓶装可乐');
    expect(moveLeadingSpecToEnd('东鹏特饮')).toBe('东鹏特饮');
    expect(moveLeadingSpecToEnd('')).toBe('');
  });

  it('包装后缀跟随规格一起挪走，不留在名称开头', () => {
    expect(moveLeadingSpecToEnd('1L装牛奶')).toBe('牛奶 1L装');
    expect(moveLeadingSpecToEnd('330ml罐装可乐')).toBe('可乐 330ml罐装');
    expect(moveLeadingSpecToEnd('500ml瓶装矿泉水')).toBe('矿泉水 500ml瓶装');
    expect(moveLeadingSpecToEnd('5g袋装盐')).toBe('盐 5g袋装');
  });

  it('单字母 m 是型号/品牌的一部分，不当作规格搬移', () => {
    expect(moveLeadingSpecToEnd('3M口罩')).toBe('3M口罩');
    expect(moveLeadingSpecToEnd('3m胶带')).toBe('3m胶带');
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
