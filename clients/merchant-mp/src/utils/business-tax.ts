/**
 * 经营页税档表单纯函数（debt-tracker M6c）。
 * 禁止依赖 uni / merchantApi；保存/拉取编排在 pages/tax。
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

/** 18 位统一社会信用代码字符集（GB 32100-2015；不含 I/O/S/V/Z）。 */
const USCC_RE = /^[0-9A-HJ-NPQRTUWXY]{18}$/;

/** 公司名与税号均必填，税号须为 18 位统一社会信用代码格式；通过返回 null。 */
export function taxProfileFormError(form: TaxProfileForm): string | null {
  if (!form.companyName.trim() || !form.taxNo.trim()) {
    return '请填写公司名与税号';
  }
  if (!USCC_RE.test(form.taxNo.trim())) {
    return '税号应为 18 位统一社会信用代码（数字或大写字母，不含 I/O/S/V/Z）';
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
