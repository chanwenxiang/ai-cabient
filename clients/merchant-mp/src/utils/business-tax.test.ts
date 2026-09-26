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
      taxProfileFormError({ companyName: '甲', taxNo: 'T1', address: '', phone: '' })
    ).toBeNull();
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
