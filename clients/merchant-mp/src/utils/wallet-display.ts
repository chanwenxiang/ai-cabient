import { displayLabel } from '@aicabinet/shared-dict';
import { displayBizNo } from '@aicabinet/shared-uni/format';

const LEDGER_DICTS = ['wallet_entry_type', 'wallet_ledger_type'] as const;

const REF_TYPE_LABEL: Record<string, string> = {
  SPLIT: '分账单',
  SPLIT_REV: '分账冲正',
  SPLIT_PARTIAL: '分账单',
  SPLIT_PARTIAL_REV: '分账冲正',
  ORDER: '订单',
  WITHDRAW: '提现',
  ADJUST: '调账'
};

function isUnknownLabel(label: string) {
  return !label || label === '未知' || label === '暂无' || label === '-' || label === '—';
}

/** 钱包流水标题：优先字典，禁止把 SPLIT_* 铺成「未知」。 */
export function walletLedgerTitle(entryType?: string | null, remark?: string | null): string {
  const code = String(entryType || '').trim();
  if (code) {
    for (const dict of LEDGER_DICTS) {
      const label = displayLabel(dict, code, '');
      if (!isUnknownLabel(label) && !/^[A-Z][A-Z0-9_]*$/.test(label)) return label;
    }
  }
  const remarkHead = String(remark || '')
    .replace(/[0-9].*$/, '')
    .replace(/[_:：]/g, ' ')
    .trim();
  if (remarkHead && !/^[A-Z][A-Z0-9_]*$/.test(remarkHead)) return remarkHead;
  return '钱包变动';
}

export function walletLedgerRefText(refType?: string | null, refId?: string | null): string {
  const id = String(refId || '').trim();
  if (!id) return '';
  const code = String(refType || '').trim().toUpperCase();
  const kind = REF_TYPE_LABEL[code] || (code && !/^[A-Z0-9_]+$/.test(code) ? code : '关联单');
  return `${kind} ${displayBizNo(id)}`;
}

/** 备注里若只是标题+单号，列表不再重复铺。 */
export function walletLedgerRemark(remark?: string | null, title?: string): string {
  const raw = String(remark || '').trim();
  if (!raw) return '';
  const stripped = raw.replace(/[0-9A-Z_:]{8,}/g, '').replace(/\s+/g, ' ').trim();
  if (!stripped) return '';
  if (title && (stripped === title || title.includes(stripped) || stripped.includes(title))) {
    return '';
  }
  return stripped;
}
