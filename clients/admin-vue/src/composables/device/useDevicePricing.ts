/**
 * 单柜定价（V350，CB-037）：设备详情页「单柜定价」tab 的数据与行内保存。
 *
 * 设计要点：
 * - 一行 = 该柜一个 SKU；覆盖价/启用开关/恢复基准全部**行内直改**（用户明确要求不走弹窗）。
 * - 乐观锁：每次 PUT 带 expectedVersion，后端 409 时提示刷新重试。
 * - 限价（min/max）由后端 SkuPriceBounds 统一校验（与商户端同源），前端只做**提示**：
 *   低于进货成本给黄色 warning（不阻断——用户拍板放行打折/清仓，低于成本知情即可）。
 */
import { computed, ref, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import { yuanTextZero as yuan } from '@/utils/display';

export type DevicePricingRow = {
  deviceId: string;
  deviceName: string;
  skuId: string;
  skuName: string;
  basePriceCents: number;
  overridePriceCents: number | null;
  overrideEnabled: boolean;
  effectivePriceCents: number;
  minPriceCents: number;
  maxPriceCents: number;
  sellableQty: number;
  imageUrl: string | null;
  purchaseCostCents: number | null;
  version: number;
  overrideUpdatedAt: string | null;
};

export type UseDevicePricingDeps = {
  deviceId: string;
  /** ops:pricing:edit；false=只读（看不到输入控件） */
  canEdit: Ref<boolean> | (() => boolean);
};

export function useDevicePricing(deps: UseDevicePricingDeps) {
  const rows = ref<DevicePricingRow[]>([]);
  const loading = ref(false);
  const hydrated = ref(false);
  /** 行级保存中标记（skuId → 'price' | 'toggle' | 'reset'），避免整表 loading 闪烁。 */
  const saving = ref<Record<string, string>>({});
  const canEdit = computed(() =>
    typeof deps.canEdit === 'function' ? deps.canEdit() : deps.canEdit.value
  );

  function markSaving(skuId: string, kind: string | null) {
    if (kind) saving.value = { ...saving.value, [skuId]: kind };
    else {
      const next = { ...saving.value };
      delete next[skuId];
      saving.value = next;
    }
  }

  async function load() {
    if (!deps.deviceId) return;
    loading.value = true;
    try {
      rows.value = await api.request<DevicePricingRow[]>(
        AdminEndpoints.devicePricing(deps.deviceId)
      );
      hydrated.value = true;
    } catch (e) {
      if (!hydrated.value) hydrated.value = true;
      ElMessage.error(errorMessage(e, '定价加载失败'));
    } finally {
      loading.value = false;
    }
  }

  /** Tab 切入时懒加载一次（与温控 ensureTempEnvLoaded 同模式）。 */
  function ensureLoaded() {
    if (!hydrated.value && !loading.value) void load();
  }

  /**
   * 行内改覆盖价（输入框失焦触发）。priceCents=null 语义交给 resetToBase，
   * 这里只处理「设一个数」；低于成本仅提醒不阻断（CB-037 拍板）。
   */
  async function savePrice(row: DevicePricingRow, priceCents: number | null) {
    if (!canEdit.value || priceCents == null || priceCents === row.overridePriceCents) return;
    warnIfBelowCost(row, priceCents);
    markSaving(row.skuId, 'price');
    try {
      const updated = await api.request<DevicePricingRow>(
        AdminEndpoints.devicePricingUpdate(deps.deviceId, row.skuId),
        'PUT',
        {
          priceCents,
          enabled: row.overridePriceCents == null ? true : row.overrideEnabled,
          expectedVersion: row.version
        }
      );
      replaceRow(updated);
      ElMessage.success(
        `已设 ${yuan(updated.effectivePriceCents)}（原 ${yuan(row.effectivePriceCents)}）`
      );
    } catch (e) {
      ElMessage.error(errorMessage(e, '改价失败'));
      await load(); // 409 乐观锁冲突等：以服务端状态为准回显
    } finally {
      markSaving(row.skuId, null);
    }
  }

  /** 行内开关：启用/停用覆盖价（停用=预设价保留但不生效，取价回落基准）。 */
  async function toggleEnabled(row: DevicePricingRow) {
    if (!canEdit.value || row.overridePriceCents == null) return;
    markSaving(row.skuId, 'toggle');
    try {
      const updated = await api.request<DevicePricingRow>(
        AdminEndpoints.devicePricingUpdate(deps.deviceId, row.skuId),
        'PUT',
        {
          priceCents: row.overridePriceCents,
          enabled: !row.overrideEnabled,
          expectedVersion: row.version
        }
      );
      replaceRow(updated);
      ElMessage.success(!row.overrideEnabled ? '覆盖价已启用' : '覆盖价已停用（保留预设价）');
    } catch (e) {
      ElMessage.error(errorMessage(e, '操作失败'));
      await load();
    } finally {
      markSaving(row.skuId, null);
    }
  }

  /** 恢复基准价：删除覆盖价行（预设价不保留——保留需求用「停用」表达，语义见 CB-037）。 */
  async function resetToBase(row: DevicePricingRow) {
    if (!canEdit.value || row.overridePriceCents == null) return;
    markSaving(row.skuId, 'reset');
    try {
      const updated = await api.request<DevicePricingRow>(
        AdminEndpoints.devicePricingUpdate(deps.deviceId, row.skuId),
        'PUT',
        { priceCents: null, expectedVersion: row.version }
      );
      replaceRow(updated);
      ElMessage.success(`已恢复基准价 ${yuan(row.basePriceCents)}`);
    } catch (e) {
      ElMessage.error(errorMessage(e, '恢复失败'));
      await load();
    } finally {
      markSaving(row.skuId, null);
    }
  }

  function replaceRow(updated: DevicePricingRow) {
    const idx = rows.value.findIndex((r) => r.skuId === updated.skuId);
    if (idx >= 0) rows.value.splice(idx, 1, updated);
  }

  return {
    rows,
    loading,
    hydrated,
    saving,
    canEdit,
    load,
    ensureLoaded,
    savePrice,
    toggleEnabled,
    resetToBase
  };
}

function warnIfBelowCost(row: DevicePricingRow, priceCents: number) {
  const cost = row.purchaseCostCents;
  if (cost != null && cost > 0 && priceCents < cost) {
    // 非阻断提醒（CB-037：清仓/引流打折放行，低于成本知情即可）
    ElMessage.warning(`低于进货成本 ${yuan(cost)}，请确认是清仓/引流定价`);
  }
}
