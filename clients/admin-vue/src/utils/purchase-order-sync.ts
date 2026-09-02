import { ElMessage } from 'element-plus';

/** 采购单审批后跨组件同步（仓库列表 ↔ 顶栏待办） */
export const PURCHASE_ORDER_REVIEWED_EVENT = 'aicabinet:purchase-order-reviewed';

export type PurchaseOrderPatch = Record<string, unknown> & {
  purchaseOrderId?: number | string;
};

export function emitPurchaseOrderReviewed(updated: PurchaseOrderPatch): void {
  if (!updated?.purchaseOrderId) return;
  globalThis.dispatchEvent(new CustomEvent(PURCHASE_ORDER_REVIEWED_EVENT, { detail: updated }));
}

export function onPurchaseOrderReviewed(
  handler: (updated: PurchaseOrderPatch) => void
): () => void {
  const listener = (e: Event) => {
    const detail = (e as CustomEvent<PurchaseOrderPatch>).detail;
    if (detail?.purchaseOrderId) handler(detail);
  };
  globalThis.addEventListener(PURCHASE_ORDER_REVIEWED_EVENT, listener);
  return () => globalThis.removeEventListener(PURCHASE_ORDER_REVIEWED_EVENT, listener);
}

export function showPurchaseReviewToast(
  updated: PurchaseOrderPatch | null | undefined,
  approve: boolean
): void {
  if (approve) {
    if (updated?.status === 'PENDING_APPROVAL') {
      const node = updated.approvalCurrentNodeName;
      ElMessage.success(
        typeof node === 'string' && node.trim()
          ? `已通过本节点，仍待「${node.trim()}」审批`
          : '已通过本节点，仍待下一节点审批'
      );
    } else {
      ElMessage.success('审批已全部通过，可进行收货');
    }
  } else {
    ElMessage.success('已驳回');
  }
}

/** 审批 API 失败时展示后端 message（含 403「非当前节点处理人」）。 */
export function formatPurchaseReviewError(error: unknown, fallback = '审批失败'): string {
  if (error instanceof Error && error.message.trim()) return error.message.trim();
  return fallback;
}
