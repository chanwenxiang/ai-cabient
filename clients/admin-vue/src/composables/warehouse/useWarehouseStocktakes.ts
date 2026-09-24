import { reactive, ref, type ComputedRef, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api, authFetch } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import type { AdminDynamicRow } from '@/types/admin-dynamic-row';

/** 仓储动态行（D15：禁止散落 Record<string, any>） */
export type WarehouseStocktakeRow = AdminDynamicRow;

export type UseWarehouseStocktakesDeps = {
  saving: Ref<boolean>;
  loadedTabs: Ref<Set<string>>;
  loadTab: (name: string, force?: boolean) => Promise<void>;
  activeWarehouses: ComputedRef<WarehouseStocktakeRow[]>;
  loadWarehousesSoft: () => Promise<void>;
};

/**
 * 盘点写路径：新建 / 详情实盘 / 拍照识别 / 完成 / 取消 / 复盘调整。
 * 列表加载与筛选项仍留在 WarehouseView。
 */
export function useWarehouseStocktakes(deps: UseWarehouseStocktakesDeps) {
  const stocktakeDialog = ref(false);
  const stocktakeDetailDialog = ref(false);
  const scanningPhoto = ref(false);
  const stocktakeForm = reactive<WarehouseStocktakeRow>({
    warehouseId: '',
    mode: 'OPEN',
    notes: ''
  });
  const stocktakeDetail = ref<WarehouseStocktakeRow>({});

  async function openStocktakeCreate() {
    Object.assign(stocktakeForm, { warehouseId: '', mode: 'OPEN', notes: '' });
    stocktakeDialog.value = true;
    try {
      await deps.loadWarehousesSoft();
      stocktakeForm.warehouseId = deps.activeWarehouses.value[0]?.warehouseId || '';
    } catch {
      /* 保留空值由用户选择 */
    }
  }

  async function saveStocktake() {
    if (!stocktakeForm.warehouseId) return ElMessage.warning('请选择仓库');
    deps.saving.value = true;
    try {
      await api.request(AdminEndpoints.warehouseStocktakesCreate, 'POST', {
        warehouseId: stocktakeForm.warehouseId,
        mode: stocktakeForm.mode,
        notes: stocktakeForm.notes
      });
      stocktakeDialog.value = false;
      ElMessage.success('盘点单已创建');
      deps.loadedTabs.value.delete('stocktakes');
      await deps.loadTab('stocktakes', true);
    } catch (e) {
      ElMessage.error(errorMessage(e, '创建失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  async function openStocktakeDetail(row: WarehouseStocktakeRow) {
    try {
      stocktakeDetail.value = await api.request<WarehouseStocktakeRow>(
        AdminEndpoints.warehouseStocktake(row.stocktakeId),
        'GET'
      );
      stocktakeDetailDialog.value = true;
    } catch (e) {
      ElMessage.error(errorMessage(e, '加载失败'));
    }
  }

  async function onStocktakePhoto(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (file.size > 8 * 1024 * 1024) {
      ElMessage.warning('图片不能超过 8MB');
      return;
    }
    scanningPhoto.value = true;
    try {
      const base =
        (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || globalThis.location.origin;
      const form = new FormData();
      form.append('file', file);
      const res = await authFetch(
        `${base}${AdminEndpoints.warehouseStocktakeScanPhoto(stocktakeDetail.value.stocktakeId)}`,
        {
          method: 'POST',
          body: form
        }
      );
      const json = await res.json().catch(() => ({}));
      if (!res.ok || json.code !== 0) {
        throw new Error(json.message || `识别失败 (${res.status})`);
      }
      stocktakeDetail.value = json.data;
      ElMessage.success('识别完成，已自动填入实盘数，请核对后保存');
      deps.loadedTabs.value.delete('stocktakes');
      await deps.loadTab('stocktakes', true);
    } catch (e) {
      ElMessage.error(errorMessage(e, '识别失败'));
    } finally {
      scanningPhoto.value = false;
    }
  }

  async function reloadStocktakeDetail() {
    const id = stocktakeDetail.value.stocktakeId;
    if (!id) return;
    stocktakeDetail.value = await api.request<WarehouseStocktakeRow>(
      AdminEndpoints.warehouseStocktake(id),
      'GET'
    );
    deps.loadedTabs.value.delete('stocktakes');
    await deps.loadTab('stocktakes', true);
  }

  async function saveStocktakeLines() {
    const id = stocktakeDetail.value.stocktakeId;
    const lines: WarehouseStocktakeRow[] = stocktakeDetail.value.lines || [];
    deps.saving.value = true;
    try {
      await Promise.all(
        lines
          .filter((l) => l.countedQty != null)
          .map((l) =>
            api.request(AdminEndpoints.warehouseStocktakeLine(id, l.lineId), 'PUT', {
              countedQty: l.countedQty,
              notes: l.notes
            })
          )
      );
      ElMessage.success('实盘数据已保存');
      await reloadStocktakeDetail();
    } catch (e) {
      ElMessage.error(errorMessage(e, '保存失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  async function completeStocktakeAction() {
    const id = stocktakeDetail.value.stocktakeId;
    deps.saving.value = true;
    try {
      await api.request(AdminEndpoints.warehouseStocktakeComplete(id), 'POST');
      ElMessage.success('盘点已完成');
      await reloadStocktakeDetail();
    } catch (e) {
      ElMessage.error(errorMessage(e, '完成失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  async function adjustStocktakeAction() {
    const id = stocktakeDetail.value.stocktakeId;
    deps.saving.value = true;
    try {
      await api.request(AdminEndpoints.warehouseStocktakeAdjust(id), 'POST', {});
      ElMessage.success('差异已调整入库');
      await reloadStocktakeDetail();
    } catch (e) {
      ElMessage.error(errorMessage(e, '调整失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  async function cancelStocktakeAction() {
    const id = stocktakeDetail.value.stocktakeId;
    deps.saving.value = true;
    try {
      await api.request(AdminEndpoints.warehouseStocktakeCancel(id), 'POST');
      ElMessage.success('盘点单已取消');
      await reloadStocktakeDetail();
    } catch (e) {
      ElMessage.error(errorMessage(e, '取消失败'));
    } finally {
      deps.saving.value = false;
    }
  }

  return {
    stocktakeDialog,
    stocktakeDetailDialog,
    scanningPhoto,
    stocktakeForm,
    stocktakeDetail,
    openStocktakeCreate,
    saveStocktake,
    openStocktakeDetail,
    onStocktakePhoto,
    saveStocktakeLines,
    completeStocktakeAction,
    adjustStocktakeAction,
    cancelStocktakeAction
  };
}
