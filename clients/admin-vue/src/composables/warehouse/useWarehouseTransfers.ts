import { reactive, ref, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import { displayLabel } from '@aicabinet/shared-dict';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';

/** 仓储动态行（D15：禁止散落 Record<string, any>） */
export type WarehouseTransferRow = AdminDynamicRow;

export type UseWarehouseTransfersDeps = {
  saving: Ref<boolean>;
  loadedTabs: Ref<Set<string>>;
  loadTab: (name: string, force?: boolean) => Promise<void>;
  warehouses: Ref<WarehouseTransferRow[]>;
  loadWarehousesSoft: () => Promise<void>;
};

/**
 * 仓间调拨写路径：新建 / 发运 / 收货 / 取消。
 * 列表加载与筛选项仍留在 WarehouseView。
 */
export function useWarehouseTransfers(deps: UseWarehouseTransfersDeps) {
  const transferDialog = ref(false);
  const transferForm = reactive({
    fromWarehouseId: '',
    toWarehouseId: '',
    notes: '',
    skuId: '',
    batchNo: '',
    quantity: 1
  });

  async function openTransferCreate() {
    Object.assign(transferForm, {
      fromWarehouseId: deps.warehouses.value[0]?.warehouseId || '',
      toWarehouseId: deps.warehouses.value[1]?.warehouseId || '',
      notes: '',
      skuId: '',
      batchNo: '',
      quantity: 1
    });
    await deps.loadWarehousesSoft();
    transferDialog.value = true;
  }

  async function saveTransfer() {
    if (
      !transferForm.fromWarehouseId ||
      !transferForm.toWarehouseId ||
      !transferForm.skuId.trim()
    ) {
      ElMessage.warning('请填写调出/调入仓与 SKU');
      return;
    }
    deps.saving.value = true;
    try {
      await api.request(AdminEndpoints.warehouseTransfers(), 'POST', {
        fromWarehouseId: transferForm.fromWarehouseId,
        toWarehouseId: transferForm.toWarehouseId,
        notes: transferForm.notes,
        lines: [
          {
            skuId: transferForm.skuId.trim(),
            batchNo: transferForm.batchNo || '',
            quantity: transferForm.quantity
          }
        ]
      });
      transferDialog.value = false;
      ElMessage.success('调拨单已创建');
      deps.loadedTabs.value.delete('transfers');
      await deps.loadTab('transfers', true);
    } catch (e) {
      ElMessage.error(errorMessage(e, '创建失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  async function shipTransfer(row: WarehouseTransferRow) {
    await api.request(AdminEndpoints.warehouseTransferShip(row.transferId), 'POST');
    ElMessage.success('已发运');
    deps.loadedTabs.value.delete('transfers');
    await deps.loadTab('transfers', true);
  }

  async function receiveTransfer(row: WarehouseTransferRow) {
    await api.request(AdminEndpoints.warehouseTransferReceive(row.transferId), 'POST');
    ElMessage.success('已收货入库');
    deps.loadedTabs.value.delete('transfers');
    await deps.loadTab('transfers', true);
  }

  async function cancelTransfer(row: WarehouseTransferRow) {
    await api.request(AdminEndpoints.warehouseTransferCancel(row.transferId), 'POST');
    ElMessage.success(displayLabel('order_status', 'CANCELLED'));
    deps.loadedTabs.value.delete('transfers');
    await deps.loadTab('transfers', true);
  }

  return {
    transferDialog,
    transferForm,
    openTransferCreate,
    saveTransfer,
    shipTransfer,
    receiveTransfer,
    cancelTransfer
  };
}
