import { describe, expect, it } from 'vitest';
import type { RequestDraftLine } from './request-draft';
import {
  REQUEST_EVIDENCE_MAX,
  applyAdjustDraftQty,
  applyToggleDraftLine,
  buildSubmitReplenishmentRequestBody,
  canAddRequestEvidence,
  canGoReplenishFromRequest,
  canStartRequestSubmit,
  evidencePreviewUrls,
  extractEvidenceFileIds,
  remainingEvidenceSlots,
  requestActionErrorMessage,
  selectedRequestLines
} from './request-submit';

function line(partial: Partial<RequestDraftLine>): RequestDraftLine {
  return {
    skuId: 'S1',
    skuName: '可乐',
    currentQty: 0,
    capacity: 10,
    suggestQty: 2,
    soldQty7d: 0,
    suggestReason: '',
    qty: 0,
    selected: false,
    ...partial
  };
}

describe('request-submit · M10c', () => {
  it('选中行与证据 id', () => {
    expect(
      selectedRequestLines([
        line({ selected: true, qty: 3 }),
        line({ skuId: 'S2', selected: true, qty: 0 }),
        line({ skuId: 'S3', selected: false, qty: 5 })
      ])
    ).toEqual([{ skuId: 'S1', requestedQty: 3 }]);
    expect(extractEvidenceFileIds([{ localPath: 'a', fileId: 1 }, { localPath: 'b' }])).toEqual([
      1
    ]);
  });

  it('提交 body 与门闩', () => {
    expect(
      buildSubmitReplenishmentRequestBody({
        deviceId: 'D1',
        notes: '  ',
        lines: [{ skuId: 'S1', requestedQty: 1 }],
        evidenceItems: [{ localPath: 'x', fileId: 9 }]
      })
    ).toEqual({
      deviceId: 'D1',
      notes: undefined,
      lines: [{ skuId: 'S1', requestedQty: 1 }],
      evidenceFileIds: [9]
    });
    expect(canStartRequestSubmit({ canSubmit: true, submitting: false, lineCount: 1 })).toBe('ok');
    expect(canStartRequestSubmit({ canSubmit: true, submitting: false, lineCount: 0 })).toBe(
      'no_lines'
    );
    expect(canAddRequestEvidence({ canRequest: true, currentCount: 5 })).toBe('full');
    expect(remainingEvidenceSlots(3)).toBe(2);
    expect(REQUEST_EVIDENCE_MAX).toBe(5);
  });

  it('切换/调数量与跳转', () => {
    const a = line({ suggestQty: 4 });
    expect(applyToggleDraftLine(a)).toBeNull();
    expect(a.selected).toBe(true);
    expect(a.qty).toBe(4);
    const b = line({ suggestQty: 0 });
    expect(applyToggleDraftLine(b)).toBe('请填写要货数量');
    applyAdjustDraftQty(a, -10);
    expect(a.qty).toBe(0);
    expect(a.selected).toBe(false);
    expect(canGoReplenishFromRequest({ status: 'ACCEPTED', replenishmentTaskId: 1 })).toBe(true);
    expect(canGoReplenishFromRequest({ status: 'SUBMITTED', replenishmentTaskId: 1 })).toBe(false);
    expect(requestActionErrorMessage(new Error('x'), 'f')).toBe('x');
    expect(evidencePreviewUrls([{ localPath: 'p' }, { localPath: '' }])).toEqual(['p']);
  });
});
