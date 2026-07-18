import { parseCabinetScan } from '@aicabinet/shared-uni/qrcode';

/** 扫柜门二维码，返回柜机编号；失败时 toast 并返回空串 */
export function scanCabinetDeviceId(): Promise<string> {
  return new Promise((resolve) => {
    uni.scanCode({
      onlyFromCamera: false,
      scanType: ['qrCode', 'barCode'],
      success(res) {
        const parsed = parseCabinetScan(res.result || '');
        const id = parsed.deviceId || '';
        if (!id) {
          uni.showToast({ title: '未识别到柜机编号', icon: 'none' });
          resolve('');
          return;
        }
        resolve(id);
      },
      fail(err) {
        const msg = err?.errMsg || '';
        if (!/cancel|取消/i.test(msg)) {
          uni.showToast({ title: '扫码失败，请重试', icon: 'none' });
        }
        resolve('');
      }
    });
  });
}
