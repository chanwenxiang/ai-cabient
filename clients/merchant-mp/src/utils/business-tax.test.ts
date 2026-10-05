import { describe, expect, it } from 'vitest';
import {
  buildSaveTaxProfileBody,
  emptyTaxProfileForm,
  mapTaxProfileToForm,
  taxProfileFormError
} from './business-tax';

describe('business-tax · M6c', () => {
  it('空表单与 API 投影', () => {
    expect(emptyTaxProfileForm()).toEqual({
      companyName: '',
      taxNo: '',
      address: '',
      phone: ''
    });
    expect(
      mapTaxProfileToForm({
        companyName: '甲公司',
        taxNo: 'T1',
        address: null,
        phone: undefined
      })
    ).toEqual({ companyName: '甲公司', taxNo: 'T1', address: '', phone: '' });
  });

  it('必填校验', () => {
    expect(taxProfileFormError(emptyTaxProfileForm())).toBe('请填写公司名与税号');
    expect(taxProfileFormError({ companyName: '  ', taxNo: 'x', address: '', phone: '' })).toBe(
      '请填写公司名与税号'
    );
    expect(
      taxProfileFormError({
        companyName: '甲',
        taxNo: '91330100MA27X8BJ0X',
        address: '',
        phone: ''
      })
    ).toBeNull();
  });

  it('税号须为 18 位统一社会信用代码格式（审计 P3）', () => {
    expect(taxProfileFormError({ companyName: '甲', taxNo: 'T1', address: '', phone: '' })).toBe(
      '税号应为 18 位统一社会信用代码（数字或大写字母，不含 I/O/S/V/Z）'
    );
    // 17 位
    expect(
      taxProfileFormError({ companyName: '甲', taxNo: '91330100MA27X8BJ0', address: '', phone: '' })
    ).not.toBeNull();
    // 含禁用字母 I
    expect(
      taxProfileFormError({
        companyName: '甲',
        taxNo: '91330100MA27X8BI0X',
        address: '',
        phone: ''
      })
    ).not.toBeNull();
    // 小写不通过
    expect(
      taxProfileFormError({
        companyName: '甲',
        taxNo: '91330100ma27x8bj0x',
        address: '',
        phone: ''
      })
    ).not.toBeNull();
  });

  it('保存 body 去空白并省略空选填', () => {
    expect(
      buildSaveTaxProfileBody('m1', {
        companyName: ' 甲 ',
        taxNo: ' T1 ',
        address: '  ',
        phone: '138'
      })
    ).toEqual({
      merchantId: 'm1',
      companyName: '甲',
      taxNo: 'T1',
      address: undefined,
      phone: '138'
    });
  });
});
