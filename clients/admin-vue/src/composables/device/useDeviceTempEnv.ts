import { computed, ref, type ComputedRef, type Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage, isUserDismiss } from '@/utils/error-message';
import { displayLabel } from '@aicabinet/shared-dict';
import type { DeviceEnvReading, DeviceTempPlan } from '@aicabinet/shared-types';

export type TempPlanEntryDraft = { time: string; target: number };

export type UseDeviceTempEnvDeps = {
  deviceId: string;
  /** 与柜门指令共用 loading key（SET_TEMP） */
  cmdLoading: Ref<string>;
  loadDetail: () => Promise<void>;
  canEditTempPlan?: ComputedRef<boolean> | Ref<boolean>;
};

/**
 * 设备详情「温控与环境」：分时计划 / 环境读数 / 即时设温。
 * 从 DeviceDetailView 抽出（debt-tracker D14）。
 */
export function useDeviceTempEnv(deps: UseDeviceTempEnvDeps) {
  const tempPlanEnabled = ref(false);
  const tempPlanEntries = ref<TempPlanEntryDraft[]>([]);
  const tempPlanSaving = ref(false);
  const envRows = ref<DeviceEnvReading[]>([]);
  const tempDraft = ref<number | undefined>(undefined);

  const canEdit = computed(() => deps.canEditTempPlan?.value ?? true);

  function toHHMM(minute: number) {
    return `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`;
  }

  function fromHHMM(time: string) {
    const [h, m] = time.split(':').map(Number);
    return h * 60 + m;
  }

  function addTempPlanEntry() {
    tempPlanEntries.value.push({ time: '09:00', target: 5 });
  }

  async function loadTempPlan() {
    try {
      const dto = await api.request<DeviceTempPlan>(
        AdminEndpoints.deviceTempPlan(deps.deviceId),
        'GET'
      );
      tempPlanEnabled.value = !!dto?.enabled;
      tempPlanEntries.value = (dto?.entries || []).map((e) => ({
        time: toHHMM(e.startMinute),
        target: e.targetTempC
      }));
    } catch {
      // 无排程或未授权时保持空态
    }
  }

  async function saveTempPlan() {
    if (!canEdit.value) return;
    tempPlanSaving.value = true;
    try {
      await api.request(AdminEndpoints.deviceTempPlan(deps.deviceId), 'PUT', {
        enabled: tempPlanEnabled.value,
        entries: tempPlanEntries.value.map((e) => ({
          startMinute: fromHHMM(e.time),
          targetTempC: e.target
        }))
      });
      ElMessage.success('温控计划已保存并应用');
      await loadTempPlan();
    } catch (e) {
      ElMessage.error(errorMessage(e, '保存失败'));
    } finally {
      tempPlanSaving.value = false;
    }
  }

  async function applyTempPlanNow() {
    if (!canEdit.value) return;
    tempPlanSaving.value = true;
    try {
      await api.request(AdminEndpoints.deviceTempPlanApply(deps.deviceId), 'POST');
      ElMessage.success('已按当前时段下发目标温度');
    } catch (e) {
      ElMessage.error(errorMessage(e, '下发失败'));
    } finally {
      tempPlanSaving.value = false;
    }
  }

  async function loadEnvReadings() {
    try {
      envRows.value =
        (await api.request<DeviceEnvReading[]>(
          AdminEndpoints.deviceEnvReadings(deps.deviceId),
          'GET'
        )) || [];
    } catch {
      envRows.value = [];
    }
  }

  function envTypeLabel(type: string) {
    return displayLabel('device_env_type', type, '未知');
  }

  function envUnit(type: string) {
    return ({ HUMIDITY: '%', VOLTAGE: 'V', POWER: 'W' } as Record<string, string>)[type] || '';
  }

  function syncTempDraftFromMetrics(targetTempC?: number | null) {
    tempDraft.value = targetTempC == null ? undefined : targetTempC;
  }

  async function setTargetTemp() {
    if (!canEdit.value) return;
    if (tempDraft.value == null || Number.isNaN(tempDraft.value)) {
      ElMessage.warning('请填写目标温度');
      return;
    }
    try {
      const { value: reason } = await ElMessageBox.prompt(
        `确认将目标温度设为 ${tempDraft.value}°C 并下发柜机？`,
        '设置目标温度',
        {
          inputValue: '运营设温',
          inputValidator: (v) => !!String(v || '').trim() || '必须填写原因',
          confirmButtonText: '确认下发'
        }
      );
      deps.cmdLoading.value = 'SET_TEMP';
      const result = await api.request<{ message?: string }>(
        AdminEndpoints.deviceCommands(deps.deviceId),
        'POST',
        { command: 'SET_TEMP', reason, targetTempC: tempDraft.value }
      );
      ElMessage.success(result.message || '温度已下发');
      await deps.loadDetail();
    } catch (e: unknown) {
      if (!isUserDismiss(e)) {
        ElMessage.error(errorMessage(e, '设温失败'));
      }
    } finally {
      deps.cmdLoading.value = '';
    }
  }

  /** Tab 切入温控时懒加载计划与环境读数 */
  function ensureTempEnvLoaded() {
    void loadTempPlan();
    void loadEnvReadings();
  }

  return {
    tempPlanEnabled,
    tempPlanEntries,
    tempPlanSaving,
    envRows,
    tempDraft,
    addTempPlanEntry,
    loadTempPlan,
    saveTempPlan,
    applyTempPlanNow,
    loadEnvReadings,
    envTypeLabel,
    envUnit,
    syncTempDraftFromMetrics,
    setTargetTemp,
    ensureTempEnvLoaded
  };
}
