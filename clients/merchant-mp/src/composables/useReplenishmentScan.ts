import type { ComputedRef, Ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
import { merchantApi } from '@/utils/merchant-api';
import { scanCabinetDeviceId } from '@/utils/scan-cabinet';
import { promptText } from '@/utils/text-prompt';
import type { MerchantSkuPricing } from '@aicabinet/shared-types';

type Task = import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto;
type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;

type DeviceMeta = {
  deviceId?: string;
};

type AskConfirm = (opts: {
  title: string;
  content: string;
  confirmText?: string;
  cancelText?: string;
}) => Promise<boolean>;

/**
 * 补货扫柜 / 扫商品条码。
 */
export function useReplenishmentScan(opts: {
  devices: Ref<Record<string, unknown>[]>;
  skus: Ref<MerchantSkuPricing[]>;
  allTasks: Ref<Task[]>;
  selected: Ref<Task | null>;
  lines: Ref<Line[]>;
  linesConfirmed: Ref<boolean>;
  scanning: Ref<boolean>;
  filterDeviceId: Ref<string>;
  status: Ref<string>;
  canRequest: ComputedRef<boolean>;
  askConfirm: AskConfirm;
  openTask: (task: Task) => Promise<void>;
  adjustQty: (line: Line, delta: number) => void;
  ensureSkuCatalog?: () => Promise<void>;
}) {
  async function assertScannedDeviceAllowed(deviceId: string): Promise<boolean> {
    const id = String(deviceId || '')
      .trim()
      .toUpperCase();
    if (!id) return false;
    const localHit = opts.devices.value.some(
      (d) =>
        String((d as DeviceMeta).deviceId || '')
          .trim()
          .toUpperCase() === id
    );
    if (localHit) return true;
    try {
      await merchantApi.assertReplenishmentDeviceAccess(id);
      return true;
    } catch (e) {
      showError(e instanceof Error ? e.message : '柜机不在您的管辖范围', 3200);
      return false;
    }
  }

  async function verifyCabinetScan() {
    if (opts.scanning.value) return;
    opts.scanning.value = true;
    try {
      const id = await scanCabinetDeviceId();
      if (!id) return;
      if (!(await assertScannedDeviceAllowed(id))) return;
      const expected = String(opts.selected.value?.deviceId || '')
        .trim()
        .toUpperCase();
      const scanned = id.trim().toUpperCase();
      if (!expected) return;
      if (scanned !== expected) {
        await opts.askConfirm({
          title: '柜机不符',
          content: `扫到 ${scanned}，本任务柜机为 ${expected}。请确认是否找错柜。`,
          confirmText: '知道了',
          cancelText: '关闭'
        });
        return;
      }
      showSuccess('柜机核对一致');
    } finally {
      opts.scanning.value = false;
    }
  }

  function findActiveTaskForDevice(deviceKey: string): Task | undefined {
    return opts.allTasks.value.find(
      (t) =>
        String(t.deviceId || '')
          .trim()
          .toUpperCase() === deviceKey &&
        t.status !== 'COMPLETED' &&
        t.status !== 'CANCELLED'
    );
  }

  async function onScan() {
    if (opts.scanning.value) return;
    opts.scanning.value = true;
    try {
      const id = await scanCabinetDeviceId();
      if (!id) return;
      if (!(await assertScannedDeviceAllowed(id))) return;
      const key = id.trim().toUpperCase();
      opts.filterDeviceId.value = key;
      opts.status.value = '';
      const open = findActiveTaskForDevice(key);
      if (open) {
        await opts.openTask(open);
      } else {
        showError('该柜暂无任务，已筛选列表');
      }
    } finally {
      opts.scanning.value = false;
    }
  }

  async function readProductBarcode(): Promise<string | null> {
    try {
      const res = await new Promise<{ result?: string }>((resolve, reject) => {
        uni.scanCode({
          onlyFromCamera: false,
          scanType: ['barCode', 'qrCode'],
          success: (r) => resolve(r as { result?: string }),
          fail: reject
        });
      });
      return String(res.result || '').trim() || null;
    } catch (err) {
      const msg = String((err as { errMsg?: string })?.errMsg || '');
      if (/cancel|取消/i.test(msg)) return null;
      return (
        String(
          (await promptText({
            title: '输入商品条码',
            placeholder: '扫描商品包装条码',
            required: true,
            requiredMessage: '条码无效',
            maxLength: 64,
            singleLine: true,
            testId: 'product-barcode-prompt'
          })) || ''
        ).trim() || null
      );
    }
  }

  function findSkuByBarcode(code: string) {
    const key = code.trim().toUpperCase();
    return opts.skus.value.find(
      (s) =>
        String(s.barcode || '')
          .trim()
          .toUpperCase() === key ||
        String(s.skuId || '')
          .trim()
          .toUpperCase() === key
    );
  }

  function findMatchingTaskLine(skuId: string): Line | undefined {
    return opts.lines.value.find(
      (l) => !l.applied && String(l.skuId).toUpperCase() === String(skuId).toUpperCase()
    );
  }

  /** 扫商品条码自动匹配任务明细并 +1；浏览器无法调起扫码时手输条码 */
  async function scanProduct(line: Line) {
    if (
      !opts.canRequest.value ||
      opts.linesConfirmed.value ||
      line.applied ||
      opts.scanning.value
    ) {
      return;
    }
    opts.scanning.value = true;
    try {
      await opts.ensureSkuCatalog?.();
      const code = await readProductBarcode();
      if (!code) return;
      const sku = findSkuByBarcode(code);
      if (!sku?.skuId) {
        showError('未匹配到商品条码');
        return;
      }
      const target = findMatchingTaskLine(sku.skuId);
      if (!target) {
        showError('本次任务不含该商品');
        return;
      }
      opts.adjustQty(target, 1);
      showSuccess(`已扫 ${sku.skuName || target.skuId}`);
    } finally {
      opts.scanning.value = false;
    }
  }

  return {
    verifyCabinetScan,
    onScan,
    scanProduct
  };
}
