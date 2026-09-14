import { nextTick, reactive, ref, type ComputedRef, type Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { yuanToCents } from '@/utils/display';
import { errorMessage } from '@/utils/error-message';
import { adminDevWarn } from '@/utils/admin-dev-log';
import {
  emitPurchaseOrderReviewed,
  showPurchaseReviewToast,
  formatPurchaseReviewError
} from '@/utils/purchase-order-sync';

/** 仓储多 Tab 共用行（字段随业务表变化） */
export type WarehousePurchaseRow = Record<string, any>;

type PurchaseLineFieldErrors = { skuId?: boolean; batchNo?: boolean; expiryDate?: boolean };

export type UseWarehousePurchaseOrdersDeps = {
  saving: Ref<boolean>;
  dialogBootLoading: Ref<boolean>;
  tab: Ref<string>;
  loadedTabs: Ref<Set<string>>;
  loadTab: (name: string, force?: boolean) => Promise<void>;
  purchaseOrders: Ref<WarehousePurchaseRow[]>;
  returnablePurchaseOrders: Ref<WarehousePurchaseRow[]>;
  suggestions: Ref<WarehousePurchaseRow[]>;
  skus: Ref<WarehousePurchaseRow[]>;
  filterWarehouseId: Ref<string>;
  suggestionCoverageDays: Ref<number>;
  activeSuppliers: ComputedRef<WarehousePurchaseRow[]>;
  activeWarehouses: ComputedRef<WarehousePurchaseRow[]>;
  pickSelected: <T extends WarehousePurchaseRow>(all: T[]) => T[];
  loadSuppliersSoft: () => Promise<void>;
  loadWarehousesSoft: () => Promise<void>;
  loadPurchase: () => Promise<void>;
  ensureMeta: () => Promise<void>;
};

function localDate() {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

/** 未手填外部单号时，创建采购单用时间戳单号。 */
function defaultPurchaseRefNo() {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `PO-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

/**
 * 采购单写路径：新建 / 建议生成 / 审批 / 收货 / 退货。
 * 列表加载与筛选项仍留在 WarehouseView。
 */
export function useWarehousePurchaseOrders(deps: UseWarehousePurchaseOrdersDeps) {
  const purchaseDialog = ref(false);
  const receiveDialog = ref(false);
  const returnDialog = ref(false);

  const purchaseForm = reactive<WarehousePurchaseRow>({
    supplierId: '',
    warehouseId: '',
    refNo: '',
    notes: '',
    lines: []
  });
  const purchaseFieldErrors = reactive<{
    supplierId: boolean;
    lineErrors: PurchaseLineFieldErrors[];
  }>({ supplierId: false, lineErrors: [] });

  const receiveForm = reactive<WarehousePurchaseRow>({
    purchaseOrderId: null,
    notes: '',
    receiveWarehouseId: '',
    lines: []
  });
  const returnForm = reactive<WarehousePurchaseRow>({
    purchaseOrderId: null,
    notes: '',
    lines: []
  });

  function newLine() {
    return {
      skuId: deps.skus.value[0]?.skuId || '',
      batchNo: '',
      productionDate: localDate(),
      expiryDate: '',
      orderedQty: 1,
      receivedQty: 0,
      unitCostYuan: 1
    };
  }

  function resetPurchaseFieldErrors() {
    purchaseFieldErrors.supplierId = false;
    purchaseFieldErrors.lineErrors = (purchaseForm.lines || []).map(() => ({}));
  }

  function clearPurchaseLineError(index: number, field: keyof PurchaseLineFieldErrors) {
    const row = purchaseFieldErrors.lineErrors[index];
    if (row) row[field] = false;
  }

  function validatePurchaseForm(): boolean {
    resetPurchaseFieldErrors();
    let ok = true;
    if (!purchaseForm.supplierId) {
      purchaseFieldErrors.supplierId = true;
      ok = false;
    }
    purchaseFieldErrors.lineErrors = purchaseForm.lines.map((line: WarehousePurchaseRow) => {
      const err: PurchaseLineFieldErrors = {};
      if (!line.skuId) {
        err.skuId = true;
        ok = false;
      }
      if (!String(line.batchNo || '').trim()) {
        err.batchNo = true;
        ok = false;
      }
      if (!line.expiryDate) {
        err.expiryDate = true;
        ok = false;
      }
      return err;
    });
    if (!ok) {
      ElMessage.warning('请完整填写供应商、商品、批次和到期日期');
      nextTick(() => {
        document
          .querySelector('.purchase-line-card .field-invalid, .form-grid .field-invalid')
          ?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      });
    }
    return ok;
  }

  function patchPurchaseOrderRow(updated: WarehousePurchaseRow) {
    if (!updated?.purchaseOrderId) return;
    const idx = deps.purchaseOrders.value.findIndex(
      (p) => String(p.purchaseOrderId) === String(updated.purchaseOrderId)
    );
    if (idx >= 0) {
      deps.purchaseOrders.value[idx] = { ...deps.purchaseOrders.value[idx], ...updated };
      deps.purchaseOrders.value = [...deps.purchaseOrders.value];
    }
  }

  async function openPurchase() {
    Object.assign(purchaseForm, {
      supplierId: '',
      warehouseId: '',
      refNo: defaultPurchaseRefNo(),
      notes: '',
      lines: [newLine()]
    });
    resetPurchaseFieldErrors();
    purchaseDialog.value = true;
    deps.dialogBootLoading.value = true;
    try {
      await Promise.all([
        deps.loadSuppliersSoft(),
        deps.loadWarehousesSoft(),
        deps.ensureMeta()
      ]);
      purchaseForm.supplierId = deps.activeSuppliers.value[0]?.supplierId || '';
      purchaseForm.warehouseId = deps.activeWarehouses.value[0]?.warehouseId || '';
    } finally {
      deps.dialogBootLoading.value = false;
    }
  }

  async function openPurchaseFromSuggestions() {
    const rows = deps.pickSelected(deps.suggestions.value);
    if (!rows.length) {
      return ElMessage.warning('当前没有可用的采购建议');
    }
    Object.assign(purchaseForm, {
      supplierId: '',
      warehouseId: deps.filterWarehouseId.value || '',
      refNo: defaultPurchaseRefNo(),
      notes: `由采购建议生成（覆盖 ${deps.suggestionCoverageDays.value} 天）`,
      lines: rows
        .map((r: WarehousePurchaseRow) => {
          const qty = Number(r.suggestQty);
          return {
            skuId: r.skuId,
            batchNo: '',
            productionDate: localDate(),
            expiryDate: '',
            // 建议量为 0/空时不静默改成 1：过滤掉无效行
            orderedQty: Number.isFinite(qty) && qty > 0 ? qty : 0,
            receivedQty: 0,
            unitCostYuan: 1
          };
        })
        .filter((line) => line.orderedQty > 0)
    });
    if (!purchaseForm.lines.length) {
      return ElMessage.warning('采购建议数量均为 0，无法生成采购单');
    }
    resetPurchaseFieldErrors();
    purchaseDialog.value = true;
    deps.dialogBootLoading.value = true;
    try {
      await Promise.all([
        deps.loadSuppliersSoft(),
        deps.loadWarehousesSoft(),
        deps.ensureMeta()
      ]);
      purchaseForm.supplierId = deps.activeSuppliers.value[0]?.supplierId || '';
      if (!purchaseForm.warehouseId) {
        purchaseForm.warehouseId = deps.activeWarehouses.value[0]?.warehouseId || '';
      }
    } finally {
      deps.dialogBootLoading.value = false;
    }
  }

  function addPurchaseLine() {
    purchaseForm.lines.push(newLine());
  }

  async function removePurchaseLine(index: number) {
    if (purchaseForm.lines.length <= 1) return;
    try {
      await ElMessageBox.confirm('确定删除该采购行吗？', '删除行', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        appendTo: document.body
      });
    } catch {
      return;
    }
    purchaseForm.lines.splice(index, 1);
  }

  async function savePurchase() {
    if (!validatePurchaseForm()) return;
    deps.saving.value = true;
    try {
      const refNo = String(purchaseForm.refNo || '').trim() || defaultPurchaseRefNo();
      const body = {
        supplierId: purchaseForm.supplierId,
        warehouseId: purchaseForm.warehouseId,
        refNo,
        notes: purchaseForm.notes,
        lines: purchaseForm.lines.map((l: WarehousePurchaseRow) => ({
          skuId: l.skuId,
          batchNo: l.batchNo,
          productionDate: l.productionDate,
          expiryDate: l.expiryDate,
          orderedQty: l.orderedQty,
          receivedQty: 0,
          unitCostCents: yuanToCents(l.unitCostYuan) ?? 0
        }))
      };
      await api.request('/api/v2/ops/admin/purchase-orders', 'POST', body);
      purchaseDialog.value = false;
      deps.tab.value = 'purchase';
      ElMessage.success('采购单已提交审批');
      deps.loadedTabs.value.delete('purchase');
      await deps.loadTab('purchase', true);
    } catch (e) {
      ElMessage.error(errorMessage(e, '创建失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  async function reviewPurchase(row: WarehousePurchaseRow, approve: boolean) {
    const label = row.refNo || row.purchaseOrderId;
    try {
      await ElMessageBox.confirm(
        approve ? `确认通过采购单 ${label}？` : `确认驳回采购单 ${label}？`,
        approve ? '审批通过' : '审批驳回',
        { type: approve ? 'info' : 'warning', appendTo: document.body }
      );
    } catch {
      return;
    }
    try {
      const updated = await api.request<WarehousePurchaseRow>(
        `/api/v2/ops/admin/purchase-orders/${row.purchaseOrderId}/review`,
        'POST',
        {
          approve,
          remark: approve ? '审批通过' : '审批驳回'
        }
      );
      patchPurchaseOrderRow(updated);
      emitPurchaseOrderReviewed(updated);
      showPurchaseReviewToast(updated, approve);
      deps.loadedTabs.value.delete('purchase');
      await deps.loadTab('purchase', true);
    } catch (e) {
      ElMessage.error(formatPurchaseReviewError(e));
    }
  }

  function openReceive(row: WarehousePurchaseRow) {
    Object.assign(receiveForm, {
      purchaseOrderId: row.purchaseOrderId,
      notes: '',
      receiveWarehouseId: row.warehouseId || '',
      lines: (row.lines || []).map((line: WarehousePurchaseRow) => ({
        ...line,
        minReceived: line.receivedQty || 0,
        receivedQty: line.receivedQty || 0
      }))
    });
    receiveDialog.value = true;
    deps.loadWarehousesSoft();
  }

  /** 采购收货确认（原 saveReceive） */
  async function receivePurchase() {
    deps.saving.value = true;
    try {
      await ElMessageBox.confirm('确认按累计收货数量入库？', '采购收货', {
        type: 'warning',
        appendTo: document.body
      });
      await api.request(
        `/api/v2/ops/admin/purchase-orders/${receiveForm.purchaseOrderId}/receive`,
        'POST',
        {
          lines: receiveForm.lines,
          notes: receiveForm.notes,
          receiveWarehouseId: receiveForm.receiveWarehouseId || undefined
        }
      );
      receiveDialog.value = false;
      ElMessage.success('收货完成');
      deps.loadedTabs.value.delete('purchase');
      deps.loadedTabs.value.delete('inventory');
      await deps.loadTab('purchase', true);
    } catch (e: unknown) {
      if (e !== 'cancel' && e !== 'close') ElMessage.error(errorMessage(e, '收货失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  async function openReturn() {
    Object.assign(returnForm, {
      purchaseOrderId: null,
      notes: '',
      lines: []
    });
    returnDialog.value = true;
    deps.dialogBootLoading.value = true;
    try {
      await Promise.all([
        deps.loadPurchase().catch((err) => {
          adminDevWarn('[warehouse] 弹窗启动预载采购单失败', err);
        }),
        deps.loadSuppliersSoft(),
        deps.loadWarehousesSoft(),
        deps.ensureMeta()
      ]);
      const first = deps.returnablePurchaseOrders.value[0];
      returnForm.purchaseOrderId = first?.purchaseOrderId || null;
      returnForm.notes = '';
      returnForm.lines = [];
      if (first) onReturnPoChange(first.purchaseOrderId);
    } finally {
      deps.dialogBootLoading.value = false;
    }
  }

  function onReturnPoChange(purchaseOrderId: number | string | null) {
    const po = deps.purchaseOrders.value.find((p) => p.purchaseOrderId === purchaseOrderId);
    returnForm.lines = (po?.lines || [])
      .map((line: WarehousePurchaseRow) => {
        const maxQty = Math.max(0, (line.receivedQty || 0) - (line.returnedQty || 0));
        return {
          purchaseLineId: line.lineId,
          skuId: line.skuId,
          batchNo: line.batchNo,
          receivedQty: line.receivedQty || 0,
          returnedQty: line.returnedQty || 0,
          maxQty,
          quantity: maxQty > 0 ? 1 : 0
        };
      })
      .filter((l: WarehousePurchaseRow) => l.maxQty > 0);
  }

  /** 采购退货确认（原 saveReturn） */
  async function returnPurchase() {
    if (!returnForm.purchaseOrderId) {
      return ElMessage.warning('请选择采购单');
    }
    const lines = (returnForm.lines || []).filter(
      (l: WarehousePurchaseRow) => (l.quantity || 0) > 0
    );
    if (!lines.length) {
      return ElMessage.warning('请填写退货数量');
    }
    deps.saving.value = true;
    try {
      await ElMessageBox.confirm('确认退货并扣减仓库库存？', '采购退货', {
        type: 'warning',
        appendTo: document.body
      });
      await api.request('/api/v2/ops/admin/purchase-returns', 'POST', {
        purchaseOrderId: returnForm.purchaseOrderId,
        notes: returnForm.notes,
        lines: lines.map((l: WarehousePurchaseRow) => ({
          purchaseLineId: l.purchaseLineId,
          quantity: l.quantity
        }))
      });
      returnDialog.value = false;
      ElMessage.success('退货完成');
      deps.loadedTabs.value.delete('returns');
      deps.loadedTabs.value.delete('purchase');
      deps.loadedTabs.value.delete('inventory');
      deps.loadedTabs.value.delete('movements');
      deps.tab.value = 'returns';
      await deps.loadTab('returns', true);
    } catch (e: unknown) {
      if (e !== 'cancel' && e !== 'close') ElMessage.error(errorMessage(e, '退货失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  // 兼容原 WarehouseView 方法名
  const saveReceive = receivePurchase;
  const saveReturn = returnPurchase;

  return {
    purchaseDialog,
    receiveDialog,
    returnDialog,
    purchaseForm,
    purchaseFieldErrors,
    receiveForm,
    returnForm,
    clearPurchaseLineError,
    patchPurchaseOrderRow,
    openPurchase,
    openPurchaseFromSuggestions,
    addPurchaseLine,
    removePurchaseLine,
    savePurchase,
    reviewPurchase,
    openReceive,
    receivePurchase,
    saveReceive,
    openReturn,
    onReturnPoChange,
    returnPurchase,
    saveReturn
  };
}
