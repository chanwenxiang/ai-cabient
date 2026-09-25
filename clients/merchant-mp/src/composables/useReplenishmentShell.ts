import { computed, type ComputedRef, type Ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
import { dictOptions } from '@aicabinet/shared-dict';
import { emptyDisplay, formatDateTimeShort } from '@aicabinet/shared-uni/format';
import { isMerchantLoggedIn } from '@/utils/merchant-api';
import { seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import { setSkipCheckInLocation } from '@/utils/checkin-location-pref';
import { isPullOffType } from '@/composables/useReplenishmentDisplay';
import type { MerchantMe, MerchantDeviceInfo } from '@aicabinet/shared-types';

type Task = import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto;
type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;

type DeviceMeta = Pick<
  MerchantDeviceInfo,
  'deviceId' | 'deviceName' | 'address' | 'routeCode' | 'latitude' | 'longitude'
>;

/**
 * 补货页壳：Hero/空态文案、设备导航、列表加载与步骤态。
 * 履约/扫码仍由 Fulfillment / Scan composable 负责。
 */
export function useReplenishmentShell(opts: {
  me: Ref<MerchantMe | null>;
  preferredId: Ref<string>;
  filterDeviceId: Ref<string>;
  status: Ref<string>;
  selected: Ref<Task | null>;
  lines: Ref<Line[]>;
  linesConfirmed: Ref<boolean>;
  doorOpened: Ref<boolean>;
  skipLocationCheck: Ref<boolean>;
  focusTaskId: Ref<number | null>;
  canSkipLocation: boolean;
  canReplenish: ComputedRef<boolean>;
  devices: Ref<MerchantDeviceInfo[]>;
  efficiency: Ref<{ completionRatePercent?: number } | null | undefined>;
  emptyHintForDeviceFilter: () => string;
  emptyHintForStatusFilter: () => string;
  clearListDeviceFilter: () => void;
  clearDeepLinkQuery: () => void;
  fetchList: (args: {
    ensureMe: (seq: number) => Promise<boolean>;
    // C24：传 getter 延迟求值——调用瞬间 me 可能尚未 ensureMe（冷启动 → hasPerm=false 的旧快照）
    canReplenish: () => boolean;
  }) => Promise<{ aborted?: boolean } | null | undefined>;
  isLatestLoad: (seq: number) => boolean;
  refreshMe: () => Promise<unknown>;
  resolveDeepLinkOpenTask: () => unknown;
  handleDeepLinkAfterLoad: (open: Task | undefined, wantedTaskId: number | null) => Promise<void>;
}) {
  const heroSubtitle = computed(() => '扫码到柜 → 签到 → 开门 → 核对履约');
  const efficiencyRateText = computed(() =>
    opts.efficiency.value ? `${opts.efficiency.value.completionRatePercent}%` : '暂无'
  );

  const detailIsPullOff = computed(() => {
    if (!opts.lines.value.length) {
      const notes = String(opts.selected.value?.notes || '');
      return /from-expiry|PULL_OFF|下架/i.test(notes);
    }
    return opts.lines.value.every((l) => isPullOffType(l.lineType));
  });

  const statusOptions = computed(() => [
    { value: '', label: '全部' },
    ...dictOptions('replenishment_task_status').filter((item) =>
      ['PENDING', 'IN_PROGRESS', 'COMPLETED'].includes(item.value)
    )
  ]);

  const emptyHint = computed(() => {
    if (opts.filterDeviceId.value) return opts.emptyHintForDeviceFilter();
    return opts.emptyHintForStatusFilter();
  });

  function usePreferredDevice() {
    const id = opts.preferredId.value;
    if (!id) return;
    opts.filterDeviceId.value = id.trim().toUpperCase();
    opts.status.value = '';
    void load();
  }

  function goRequest() {
    const q = opts.filterDeviceId.value
      ? `?deviceId=${encodeURIComponent(opts.filterDeviceId.value)}`
      : '';
    uni.navigateTo({ url: `/pages/request/request${q}` });
  }

  function goRequestForDevice(deviceId: string) {
    uni.navigateTo({
      url: `/pages/request/request?deviceId=${encodeURIComponent(deviceId)}`
    });
  }

  function deviceMeta(id?: string): DeviceMeta | undefined {
    if (!id) return undefined;
    return opts.devices.value.find((item) => item.deviceId === id);
  }

  function deviceName(id?: string, snapshot?: string) {
    if (snapshot) return snapshot;
    const d = deviceMeta(id);
    return d?.deviceName || emptyDisplay(id, 'device');
  }

  function deviceAddressLine(id?: string): string {
    const m = deviceMeta(id);
    if (!m) return '';
    const parts = [m.address, m.routeCode ? `线路 ${m.routeCode}` : ''].filter(Boolean);
    return parts.join(' · ');
  }

  function toggleSkipLocation() {
    if (!opts.canSkipLocation) return;
    opts.skipLocationCheck.value = !opts.skipLocationCheck.value;
    setSkipCheckInLocation(opts.skipLocationCheck.value);
  }

  function copyDeviceId(id?: string) {
    const code = String(id || opts.selected.value?.deviceId || '').trim();
    if (!code) return;
    uni.setClipboardData({
      data: code,
      success: () => showSuccess('已复制柜机编号')
    });
  }

  function navigateToDevice(id?: string) {
    const m = deviceMeta(id || opts.selected.value?.deviceId);
    if (!m?.latitude || !m?.longitude) {
      showError('暂无坐标，请按地址或编号找柜');
      return;
    }
    const name = encodeURIComponent(m.deviceName || m.deviceId || '柜机');
    // #ifdef H5
    if (typeof window !== 'undefined') {
      window.open(
        `https://uri.amap.com/marker?position=${m.longitude},${m.latitude}&name=${name}`,
        '_blank'
      );
      return;
    }
    // #endif
    uni.openLocation({
      latitude: Number(m.latitude),
      longitude: Number(m.longitude),
      name: m.deviceName || m.deviceId || '柜机',
      address: m.address || ''
    });
  }

  function formatTime(value?: string) {
    return formatDateTimeShort(value, '暂无');
  }

  function formatDateOnly(value?: string) {
    if (!value) return '';
    const raw = String(value).trim();
    if (/^\d{4}-\d{2}-\d{2}/.test(raw)) return raw.slice(0, 10);
    return formatDateTimeShort(raw, raw).slice(0, 10);
  }

  function routeLabel(task: { routeId?: number; routeName?: string }) {
    if (task.routeName && String(task.routeName).trim()) {
      return String(task.routeName).trim();
    }
    return task.routeId != null ? `线路 #${task.routeId}` : '';
  }

  async function ensureReplenishmentMe(seq: number): Promise<boolean> {
    try {
      await opts.refreshMe();
    } catch {
      if (!isMerchantLoggedIn()) return false;
      seedMerchantMeDisplayCache(opts.me);
    }
    if (!opts.isLatestLoad(seq)) return false;
    if (!opts.me.value) {
      seedMerchantMeDisplayCache(opts.me);
    }
    return true;
  }

  async function load() {
    const result = await opts.fetchList({
      ensureMe: ensureReplenishmentMe,
      // C24：延迟求值，fetchList 在 ensureMe 完成后才读取，避免冷启动权限快照恒为 false
      canReplenish: () => opts.canReplenish.value
    });
    if (!result || result.aborted) return;
    const wantedTaskId = opts.focusTaskId.value;
    const open = opts.resolveDeepLinkOpenTask();
    await opts.handleDeepLinkAfterLoad(open, wantedTaskId);
  }

  function clearDeviceFilter() {
    opts.clearListDeviceFilter();
    opts.clearDeepLinkQuery();
  }

  function currentStep(): number {
    if (!opts.selected.value) return 1;
    if (opts.selected.value.status === 'COMPLETED') return 5;
    if (opts.linesConfirmed.value) return 4;
    if (opts.doorOpened.value) return 3;
    if (opts.selected.value.checkInAt) return 2;
    return 1;
  }

  function assignSlot(line: Line, opt: { slotCode: string; room: number }) {
    if (!opt.slotCode) return;
    if (opt.room <= 0) {
      showError('该货道已满');
      return;
    }
    line.slotId = opt.slotCode;
    if ((Number(line.quantity) || 0) > opt.room) {
      line.quantity = opt.room;
    }
    opts.linesConfirmed.value = false;
  }

  return {
    heroSubtitle,
    efficiencyRateText,
    detailIsPullOff,
    statusOptions,
    emptyHint,
    usePreferredDevice,
    goRequest,
    goRequestForDevice,
    deviceName,
    deviceAddressLine,
    toggleSkipLocation,
    copyDeviceId,
    navigateToDevice,
    formatTime,
    formatDateOnly,
    routeLabel,
    load,
    clearDeviceFilter,
    currentStep,
    assignSlot
  };
}
