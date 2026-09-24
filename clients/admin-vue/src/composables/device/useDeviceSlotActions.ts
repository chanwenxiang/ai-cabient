import { computed, reactive, ref, type ComputedRef, type Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import type { DeviceSlot, UpsertDeviceSlotRequest } from '@aicabinet/shared-types';

export type UseDeviceSlotActionsDeps = {
  deviceId: string;
  slots: Ref<DeviceSlot[]>;
  canEditSlots: ComputedRef<boolean> | Ref<boolean>;
  canStocktake: ComputedRef<boolean> | Ref<boolean>;
  loadDetail: () => Promise<void>;
};

/**
 * 设备详情货道陈列：套模板 / 刷新 / 编辑弹窗 / 盘点与保存。
 * 从 DeviceDetailView 抽出（debt-tracker D16）。
 *
 * 保存路径须与 2026-09-24 修复一致：实盘改动过则一并提交，禁止「toast 已保存、实盘未落库」。
 */
export function useDeviceSlotActions(deps: UseDeviceSlotActionsDeps) {
  const applying = ref(false);
  const slotsRefreshing = ref(false);
  const saving = ref(false);
  const stocktaking = ref(false);
  const editorVisible = ref(false);

  const editForm = reactive({
    slotCode: '',
    assignedSkuId: '' as string | undefined,
    parLevel: 0,
    minLevel: 0,
    maxLevel: 0,
    enabled: true,
    bookQty: 0,
    physicalQty: 0,
    /**
     * 打开弹窗那一刻的实盘快照。
     * 有了快照才能区分「用户真的改了实盘」与「只是原样带出来」，避免每次保存都多发一次盘点请求。
     */
    originPhysicalQty: null as number | null,
    qtyDiff: 0,
    hasDiscrepancy: false,
    adjustBookQty: false
  });

  /** 用户是否改过实盘数量（决定「保存配置」要不要连带提交盘点）。 */
  const physicalQtyChanged = computed(
    () => editForm.originPhysicalQty !== null && editForm.physicalQty !== editForm.originPhysicalQty
  );

  async function applyTemplate() {
    applying.value = true;
    try {
      const n = await api.request<number>(
        AdminEndpoints.deviceSlotsApplyTemplate(deps.deviceId),
        'POST'
      );
      // n=0 说明货道已与模板一致（接口幂等）。原先统一提示「新增 0 个货道」，读起来像失败。
      ElMessage.success(n > 0 ? `已套用模板，新增 ${n} 个货道` : '货道已与模板一致，无需新增');
      await deps.loadDetail();
    } catch (e) {
      ElMessage.error(errorMessage(e, '套用失败'));
    } finally {
      applying.value = false;
    }
  }

  /**
   * 「刷新货道」：`loadDetail()` 一次会发多条请求；
   * 原先直接 `@click="loadDetail"` 且无 loading ⇒ 点完界面毫无变化，容易被当成「按钮没用」。
   */
  async function refreshSlots() {
    slotsRefreshing.value = true;
    try {
      await deps.loadDetail();
    } catch (e) {
      ElMessage.error(errorMessage(e, '刷新失败'));
    } finally {
      slotsRefreshing.value = false;
    }
  }

  function openEditor(slot: DeviceSlot) {
    if (!deps.canEditSlots.value) return;
    editForm.slotCode = slot.slotCode;
    editForm.assignedSkuId = slot.assignedSkuId || '';
    editForm.parLevel = slot.parLevel;
    editForm.minLevel = slot.minLevel;
    editForm.maxLevel = slot.maxLevel;
    editForm.enabled = slot.enabled;
    editForm.bookQty = slot.bookQty ?? 0;
    editForm.physicalQty =
      slot.lastPhysicalQty == null ? (slot.bookQty ?? 0) : Number(slot.lastPhysicalQty);
    editForm.originPhysicalQty = editForm.physicalQty;
    editForm.qtyDiff = slot.qtyDiff ?? 0;
    editForm.hasDiscrepancy = !!slot.hasDiscrepancy;
    editForm.adjustBookQty = false;
    editorVisible.value = true;
  }

  /** 盘点提交：实盘数量落库；`adjustBookQty=true` 时同时按实盘回写批次账面。 */
  async function submitPhysicalQty(adjustBookQty: boolean) {
    const updated = await api.request<DeviceSlot>(
      AdminEndpoints.deviceSlotsStocktake(deps.deviceId),
      'POST',
      {
        slotCode: editForm.slotCode,
        physicalQty: editForm.physicalQty,
        adjustBookQty
      }
    );
    editForm.bookQty = updated.bookQty ?? editForm.physicalQty;
    editForm.qtyDiff = updated.qtyDiff ?? 0;
    editForm.hasDiscrepancy = !!updated.hasDiscrepancy;
    editForm.originPhysicalQty = editForm.physicalQty;
    editForm.adjustBookQty = false;
  }

  /** 调账前的二次确认（改批次库存是不可逆重操作）；返回 false 表示用户放弃。 */
  async function confirmAdjustBookQty(): Promise<boolean> {
    try {
      await ElMessageBox.confirm(
        `确认将货道 ${editForm.slotCode} 账面按实盘 ${editForm.physicalQty} 回写？\n将调整该货道绑定 SKU 的批次库存。`,
        '按实盘调账面',
        { type: 'warning', confirmButtonText: '确认调账' }
      );
      return true;
    } catch {
      return false;
    }
  }

  async function runStocktake(adjustBookQty: boolean) {
    if (editForm.physicalQty == null || editForm.physicalQty < 0) {
      ElMessage.warning('请填写实盘数量');
      return;
    }
    if (adjustBookQty && !editForm.assignedSkuId) {
      ElMessage.warning('货道未绑定商品，无法调账面');
      return;
    }
    if (adjustBookQty && !(await confirmAdjustBookQty())) return;
    stocktaking.value = true;
    try {
      await submitPhysicalQty(adjustBookQty);
      ElMessage.success(adjustBookQty ? '已按实盘调账面' : '已记录实盘数量');
      await deps.loadDetail();
    } catch (e) {
      ElMessage.error(errorMessage(e, '盘点失败'));
    } finally {
      stocktaking.value = false;
    }
  }

  async function stocktakeSlot() {
    await runStocktake(false);
  }

  async function stocktakeAndAdjust() {
    await runStocktake(true);
  }

  /**
   * 「保存配置」= 保存这个弹窗表单的**全部**改动。
   * 实盘改动过就一并提交；勾了「调账面」则先确认再提交。
   */
  async function saveSlot() {
    // 现场盘点字段在无 `ops:replenishment:edit` 时整块不渲染；此处保留防御：
    // 万一「改过实盘但无权限」，必须说出来且不许假装成功。
    const withStocktake = physicalQtyChanged.value;
    const stocktakeSaved = withStocktake && deps.canStocktake.value;
    if (withStocktake && !deps.canStocktake.value) {
      ElMessage.warning('无盘点权限：仅保存货道配置，实盘数量未保存');
    }
    if (stocktakeSaved && editForm.adjustBookQty) {
      if (!(await confirmAdjustBookQty())) return;
    }
    saving.value = true;
    const body: UpsertDeviceSlotRequest[] = [
      {
        slotCode: editForm.slotCode,
        assignedSkuId: editForm.assignedSkuId || '',
        parLevel: editForm.parLevel,
        minLevel: editForm.minLevel,
        maxLevel: editForm.maxLevel,
        enabled: editForm.enabled
      }
    ];
    try {
      deps.slots.value = await api.request<DeviceSlot[]>(
        AdminEndpoints.deviceSlots(deps.deviceId),
        'PUT',
        body
      );
      if (stocktakeSaved) {
        await submitPhysicalQty(editForm.adjustBookQty);
      }
      editorVisible.value = false;
      ElMessage.success(stocktakeSaved ? '已保存（含实盘数量）' : '已保存');
      await deps.loadDetail();
    } catch (e) {
      ElMessage.error(errorMessage(e, '保存失败'));
    } finally {
      saving.value = false;
    }
  }

  return {
    applying,
    slotsRefreshing,
    saving,
    stocktaking,
    editorVisible,
    editForm,
    physicalQtyChanged,
    applyTemplate,
    refreshSlots,
    openEditor,
    stocktakeSlot,
    stocktakeAndAdjust,
    saveSlot
  };
}
