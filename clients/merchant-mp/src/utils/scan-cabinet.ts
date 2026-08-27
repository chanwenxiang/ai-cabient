import { normalizeDeviceId, parseCabinetScan } from '@aicabinet/shared-uni/qrcode';
import { promptText } from '@/utils/text-prompt';

function isBrowserH5(): boolean {
  return typeof globalThis !== 'undefined' && typeof document !== 'undefined';
}

function resolveDeviceId(raw: string): string {
  const trimmed = String(raw || '').trim();
  if (!trimmed) return '';
  const parsed = parseCabinetScan(trimmed);
  return parsed.deviceId || normalizeDeviceId(trimmed);
}

/** H5 / 扫码失败时手输柜机编号 */
async function promptManualDeviceId(hint?: string): Promise<string> {
  const value = await promptText({
    title: '输入柜机编号',
    hint: isBrowserH5() ? '浏览器无法调起扫码，请输入柜门上的编号' : undefined,
    placeholder: '例如 CAB-001',
    defaultValue: hint || '',
    required: true,
    requiredMessage: '柜机编号无效',
    maxLength: 32,
    singleLine: true,
    testId: 'device-id-prompt'
  });
  if (value == null) return '';
  const id = resolveDeviceId(value);
  if (!id) {
    uni.showToast({ title: '柜机编号无效', icon: 'none' });
    return '';
  }
  return id;
}

/** 扫柜门二维码，返回柜机编号；失败时 toast 并返回空串；H5 可手输 */
export function scanCabinetDeviceId(): Promise<string> {
  return new Promise((resolve) => {
    uni.scanCode({
      onlyFromCamera: false,
      scanType: ['qrCode', 'barCode'],
      success(res) {
        const parsed = parseCabinetScan(res.result || '');
        const id = parsed.deviceId || '';
        if (!id) {
          if (isBrowserH5()) {
            void promptManualDeviceId().then(resolve);
            return;
          }
          uni.showToast({ title: '未识别到柜机编号', icon: 'none' });
          resolve('');
          return;
        }
        resolve(id);
      },
      fail(err) {
        const msg = err?.errMsg || '';
        if (/cancel|取消/i.test(msg)) {
          resolve('');
          return;
        }
        if (isBrowserH5()) {
          void promptManualDeviceId().then(resolve);
          return;
        }
        uni.showToast({ title: '扫码失败，请重试', icon: 'none' });
        resolve('');
      }
    });
  });
}
