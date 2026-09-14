import { computed, reactive, ref, type ComputedRef, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';

/** 仓储多 Tab 共用行（字段随业务表变化） */
export type WarehouseBinRow = Record<string, any>;

export type UseWarehouseBinsDeps = {
  saving: Ref<boolean>;
  loadedTabs: Ref<Set<string>>;
  loadTab: (name: string, force?: boolean) => Promise<void>;
  bins: Ref<WarehouseBinRow[]>;
  binStock: Ref<WarehouseBinRow[]>;
  skus: Ref<WarehouseBinRow[]>;
  filterWarehouseId: Ref<string>;
  activeWarehouses: ComputedRef<WarehouseBinRow[]>;
  loadWarehousesSoft: () => Promise<void>;
  ensureMeta: () => Promise<void>;
};

function localDate() {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

/**
 * 货位写路径：档案增改 / 入库到货位 / 货位移库。
 * 列表加载与筛选项仍留在 WarehouseView。
 */
export function useWarehouseBins(deps: UseWarehouseBinsDeps) {
  const binDialog = ref(false);
  const binInboundDialog = ref(false);
  const binMoveDialog = ref(false);

  const binForm = reactive<WarehouseBinRow>({
    editing: false,
    warehouseId: '',
    binCode: '',
    binName: '',
    status: 'ACTIVE'
  });
  const binInboundForm = reactive<WarehouseBinRow>({
    warehouseId: '',
    binCode: '',
    skuId: '',
    batchNo: '',
    productionDate: localDate(),
    expiryDate: '',
    quantity: 1
  });
  const binMoveForm = reactive<WarehouseBinRow>({
    fromBinId: null,
    toBinId: null,
    skuId: '',
    batchNo: '',
    quantity: 1
  });

  const allBins = computed(() => deps.bins.value);
  const sourceBinSkus = computed(() => {
    const rows = deps.binStock.value.filter((r) => r.binId === binMoveForm.fromBinId);
    const map = new Map<string, WarehouseBinRow>();
    for (const r of rows) {
      if (!map.has(r.skuId)) {
        map.set(r.skuId, { skuId: r.skuId, skuName: r.skuName });
      }
    }
    return [...map.values()];
  });
  const sourceBinMaxQty = computed(() =>
    deps.binStock.value
      .filter((r) => r.binId === binMoveForm.fromBinId && r.skuId === binMoveForm.skuId)
      .reduce((s, r) => s + (Number(r.quantity) || 0), 0)
  );

  function activeBinsFor(warehouseId: string) {
    return deps.bins.value.filter((b) => b.warehouseId === warehouseId && b.status === 'ACTIVE');
  }

  function binLabel(b: WarehouseBinRow) {
    return b.binName ? `${b.binCode} · ${b.binName}` : b.binCode;
  }

  function openBinDialog(row?: WarehouseBinRow) {
    Object.assign(binForm, {
      editing: !!row,
      warehouseId: row?.warehouseId || deps.activeWarehouses.value[0]?.warehouseId || '',
      binCode: row?.binCode || '',
      binName: row?.binName || '',
      status: row?.status || 'ACTIVE'
    });
    binDialog.value = true;
  }

  async function saveBin() {
    if (!binForm.warehouseId || !binForm.binCode.trim()) {
      return ElMessage.warning('请填写仓库和货位编码');
    }
    deps.saving.value = true;
    try {
      await api.request(AdminEndpoints.warehouseBins, 'PUT', {
        warehouseId: binForm.warehouseId,
        binCode: binForm.binCode.trim(),
        binName: binForm.binName,
        status: binForm.status
      });
      binDialog.value = false;
      ElMessage.success('货位已保存');
      deps.loadedTabs.value.delete('bins');
      await deps.loadTab('bins', true);
    } catch (e) {
      ElMessage.error(errorMessage(e, '保存失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  function onBinInboundWarehouse() {
    const first = activeBinsFor(binInboundForm.warehouseId)[0];
    binInboundForm.binCode = first?.binCode || '';
  }

  async function openBinInbound() {
    Object.assign(binInboundForm, {
      warehouseId: deps.filterWarehouseId.value || deps.activeWarehouses.value[0]?.warehouseId || '',
      binCode: '',
      skuId: deps.skus.value[0]?.skuId || '',
      batchNo: '',
      productionDate: localDate(),
      expiryDate: '',
      quantity: 1
    });
    binInboundDialog.value = true;
    try {
      await Promise.all([deps.loadWarehousesSoft(), deps.ensureMeta()]);
    } catch {
      /* 保留旧值 */
    }
    onBinInboundWarehouse();
  }

  async function saveBinInbound() {
    if (
      !binInboundForm.warehouseId ||
      !binInboundForm.binCode ||
      !binInboundForm.skuId ||
      !binInboundForm.batchNo.trim() ||
      !binInboundForm.expiryDate
    ) {
      return ElMessage.warning('请完整填写仓库、货位、商品、批次和到期日');
    }
    deps.saving.value = true;
    try {
      await api.request(AdminEndpoints.warehouseBinsStockInbound, 'POST', {
        warehouseId: binInboundForm.warehouseId,
        binCode: binInboundForm.binCode,
        skuId: binInboundForm.skuId,
        batchNo: binInboundForm.batchNo.trim(),
        productionDate: binInboundForm.productionDate,
        expiryDate: binInboundForm.expiryDate,
        quantity: Number(binInboundForm.quantity) || 0
      });
      binInboundDialog.value = false;
      ElMessage.success('已入库到货位');
      deps.loadedTabs.value.delete('bins');
      await deps.loadTab('bins', true);
    } catch (e) {
      ElMessage.error(errorMessage(e, '入库失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  function onBinMoveSource() {
    const first = sourceBinSkus.value[0];
    binMoveForm.skuId = first?.skuId || '';
    binMoveForm.batchNo = '';
    binMoveForm.quantity = 1;
  }

  async function openBinMove() {
    Object.assign(binMoveForm, {
      fromBinId: allBins.value[0]?.binId ?? null,
      toBinId: null,
      skuId: '',
      batchNo: '',
      quantity: 1
    });
    binMoveDialog.value = true;
    onBinMoveSource();
  }

  async function saveBinMove() {
    if (
      binMoveForm.fromBinId == null ||
      binMoveForm.toBinId == null ||
      !binMoveForm.skuId ||
      !binMoveForm.batchNo.trim()
    ) {
      return ElMessage.warning('请完整填写源/目标货位、商品和批次');
    }
    deps.saving.value = true;
    try {
      await api.request(AdminEndpoints.warehouseBinsStockMove, 'POST', {
        fromBinId: binMoveForm.fromBinId,
        toBinId: binMoveForm.toBinId,
        skuId: binMoveForm.skuId,
        batchNo: binMoveForm.batchNo.trim(),
        quantity: Number(binMoveForm.quantity) || 0
      });
      binMoveDialog.value = false;
      ElMessage.success('移库完成');
      deps.loadedTabs.value.delete('bins');
      await deps.loadTab('bins', true);
    } catch (e) {
      ElMessage.error(errorMessage(e, '移库失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  return {
    binDialog,
    binInboundDialog,
    binMoveDialog,
    binForm,
    binInboundForm,
    binMoveForm,
    allBins,
    sourceBinSkus,
    sourceBinMaxQty,
    activeBinsFor,
    binLabel,
    openBinDialog,
    saveBin,
    onBinInboundWarehouse,
    openBinInbound,
    saveBinInbound,
    onBinMoveSource,
    openBinMove,
    saveBinMove
  };
}
