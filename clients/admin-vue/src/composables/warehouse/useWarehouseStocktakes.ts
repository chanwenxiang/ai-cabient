import { reactive, ref, type ComputedRef, type Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
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
      ElMessage.error(errorMessage(e, '加载失败，请重试'));
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

  /**
   * 把弹窗里录入的实盘数量真正落库。
   * R2-A6：完成/完成并过账此前只 POST，不带行数据 —— 运营改了实盘没点「保存实盘」就点完成，
   * 后端按**上次保存值**过账（或首次录入直接 400 all lines must be counted）。
   * 现在两个完成动作都先过这道闸。
   */
  async function persistStocktakeLines(): Promise<boolean> {
    const id = stocktakeDetail.value.stocktakeId;
    const lines: WarehouseStocktakeRow[] = stocktakeDetail.value.lines || [];
    const pending = lines.filter((l) => l.countedQty != null);
    if (!pending.length) return true;
    try {
      await Promise.all(
        pending.map((l) =>
          api.request(AdminEndpoints.warehouseStocktakeLine(id, l.lineId), 'PUT', {
            countedQty: l.countedQty,
            notes: l.notes
          })
        )
      );
      return true;
    } catch (e) {
      ElMessage.error(errorMessage(e, '实盘数量保存失败，请检查后再完成盘点'));
      return false;
    }
  }

  /** 完成类动作的前置闸：缺行实盘数量时给出中文提示（后端此处的 400 文案是英文）。 */
  function assertAllLinesCounted(): boolean {
    const lines: WarehouseStocktakeRow[] = stocktakeDetail.value.lines || [];
    const missing = lines.filter((l) => l.countedQty == null).length;
    if (missing > 0) {
      ElMessage.warning(`还有 ${missing} 行未录入实盘数量，录完再完成盘点`);
      return false;
    }
    return true;
  }

  /**
   * R2-A12：写成功后的刷新失败不能算写失败 —— 否则先弹「已完成」再弹「完成失败」两条相反提示，
   * 运营重试还会撞 409。刷新单独兜住。
   */
  async function reloadStocktakeDetailSafely() {
    try {
      await reloadStocktakeDetail();
    } catch (e) {
      ElMessage.warning(errorMessage(e, '列表刷新失败，请点刷新重试'));
    }
  }

  async function saveStocktakeLines() {
    deps.saving.value = true;
    try {
      if (!(await persistStocktakeLines())) return;
      ElMessage.success('实盘数据已保存');
      await reloadStocktakeDetail();
    } finally {
      deps.saving.value = false;
    }
  }

  /**
   * 盘点写动作二次确认（2026-11-10 审计 §7#6）：完成/过账/复盘/取消都动库存台账，
   * 此前按钮直发请求无确认。用户点「取消」返回 false，不当错误处理。
   */
  async function confirmStocktakeWrite(content: string, title: string): Promise<boolean> {
    return ElMessageBox.confirm(content, title, {
      type: 'warning',
      confirmButtonText: '确认',
      cancelButtonText: '取消'
    })
      .then(() => true)
      .catch(() => false);
  }

  async function completeStocktakeAction() {
    const id = stocktakeDetail.value.stocktakeId;
    if (
      !(await confirmStocktakeWrite(
        '确认完成该盘点单？只完成、不过账；差异可稍后复盘调整。',
        '完成盘点'
      ))
    )
      return;
    if (!assertAllLinesCounted()) return;
    deps.saving.value = true;
    try {
      if (!(await persistStocktakeLines())) return;
      await api.request(AdminEndpoints.warehouseStocktakeComplete(id), 'POST');
    } catch (e) {
      ElMessage.error(errorMessage(e, '完成失败'));
      return;
    } finally {
      deps.saving.value = false;
    }
    ElMessage.success('盘点已完成');
    await reloadStocktakeDetailSafely();
  }

  /** P2-6：完成并过账（全行实盘校验 + 差异全量过账，一个动作） */
  async function completeAndAdjustStocktakeAction() {
    const id = stocktakeDetail.value.stocktakeId;
    const diffLines = Number(stocktakeDetail.value.diffLineCount ?? 0);
    if (
      !(await confirmStocktakeWrite(
        `确认完成并过账？差异行将按实盘数量全量调整入库${
          diffLines > 0 ? `（当前 ${diffLines} 行差异）` : ''
        }。`,
        '完成并过账'
      ))
    )
      return;
    if (!assertAllLinesCounted()) return;
    deps.saving.value = true;
    try {
      if (!(await persistStocktakeLines())) return;
      await api.request(AdminEndpoints.warehouseStocktakeCompleteAndAdjust(id), 'POST');
    } catch (e) {
      ElMessage.error(errorMessage(e, '完成并过账失败'));
      return;
    } finally {
      deps.saving.value = false;
    }
    ElMessage.success('盘点已完成，差异已过账');
    await reloadStocktakeDetailSafely();
  }

  async function adjustStocktakeAction() {
    const id = stocktakeDetail.value.stocktakeId;
    const diffLines = Number(stocktakeDetail.value.diffLineCount ?? 0);
    if (
      !(await confirmStocktakeWrite(
        `确认复盘调整？将按实盘差异再次调整入库${
          diffLines > 0 ? `（当前 ${diffLines} 行差异）` : ''
        }。`,
        '复盘调整'
      ))
    )
      return;
    deps.saving.value = true;
    try {
      await api.request(AdminEndpoints.warehouseStocktakeAdjust(id), 'POST', {});
    } catch (e) {
      ElMessage.error(errorMessage(e, '调整失败'));
      return;
    } finally {
      deps.saving.value = false;
    }
    ElMessage.success('差异已调整入库');
    await reloadStocktakeDetailSafely();
  }

  async function cancelStocktakeAction() {
    const id = stocktakeDetail.value.stocktakeId;
    if (!(await confirmStocktakeWrite('确认取消该盘点单？', '取消盘点'))) return;
    deps.saving.value = true;
    try {
      await api.request(AdminEndpoints.warehouseStocktakeCancel(id), 'POST');
    } catch (e) {
      ElMessage.error(errorMessage(e, '取消失败'));
      return;
    } finally {
      deps.saving.value = false;
    }
    ElMessage.success('盘点单已取消');
    await reloadStocktakeDetailSafely();
  }

  return {
    stocktakeDialog,
    stocktakeDetailDialog,
    stocktakeForm,
    stocktakeDetail,
    openStocktakeCreate,
    saveStocktake,
    openStocktakeDetail,
    saveStocktakeLines,
    completeStocktakeAction,
    completeAndAdjustStocktakeAction,
    adjustStocktakeAction,
    cancelStocktakeAction
  };
}
