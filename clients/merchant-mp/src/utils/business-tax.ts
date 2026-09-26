/**
 * 经营页税档表单纯函数（debt-tracker M6c）。
 * 禁止依赖 uni / merchantApi；保存/拉取编排仍在 business.vue。
 */

export type TaxProfileForm = {
  companyName: string;
  taxNo: string;
  address: string;
  phone: string;
};

export function emptyTaxProfileForm(): TaxProfileForm {
  return { companyName: '', taxNo: '', address: '', phone: '' };
}

/** 将 API 税档投影到表单（缺字段 → 空串）。 */
export function mapTaxProfileToForm(p: {
  companyName?: string | null;
  taxNo?: string | null;
  address?: string | null;
  phone?: string | null;
}): TaxProfileForm {
  return {
    companyName: p.companyName || '',
    taxNo: p.taxNo || '',
    address: p.address || '',
    phone: p.phone || ''
  };
}

/** 公司名与税号均必填；通过返回 null。 */
export function taxProfileFormError(form: TaxProfileForm): string | null {
  if (!form.companyName.trim() || !form.taxNo.trim()) {
    return '请填写公司名与税号';
  }
  return null;
}

export function buildSaveTaxProfileBody(
  merchantId: string,
  form: TaxProfileForm
): {
  merchantId: string;
  companyName: string;
  taxNo: string;
  address?: string;
  phone?: string;
} {
  return {
    merchantId,
    companyName: form.companyName.trim(),
    taxNo: form.taxNo.trim(),
    address: form.address.trim() || undefined,
    phone: form.phone.trim() || undefined
  };
}
