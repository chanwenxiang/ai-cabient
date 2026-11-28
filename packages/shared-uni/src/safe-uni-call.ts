/**
 * C9：平台敏感 `uni.*` API 统一守卫。
 *
 * H5 无 makePhoneCall/scanCode 等实现（lessons #139 同族坑），直接调用会抛错；
 * 统一入口做能力检测 + 静默/降级处理，调用方拿布尔结果自行提示。
 */
type UniLike = {
  makePhoneCall?: (o: { phoneNumber: string; fail?: (e?: unknown) => void }) => void;
  setClipboardData?: (o: {
    data: string;
    success?: () => void;
    fail?: (e?: unknown) => void;
  }) => void;
  scanCode?: (o: {
    success?: (r: { result?: string }) => void;
    fail?: (e?: unknown) => void;
  }) => void;
  chooseImage?: (o: {
    count?: number;
    sourceType?: ('camera' | 'album')[];
    sizeType?: ('original' | 'compressed')[];
    success?: (r: { tempFilePaths?: string[]; tempFiles?: unknown[] }) => void;
    fail?: (e?: unknown) => void;
  }) => void;
};

function uniApi(): UniLike {
  return (typeof uni !== 'undefined' ? uni : {}) as UniLike;
}

/** 拨打电话；H5 等无实现环境返回 false，调用方提示「请拨打 xxx」降级。 */
export function safeMakePhoneCall(phoneNumber: string): boolean {
  const api = uniApi();
  if (typeof api.makePhoneCall !== 'function') {
    return false;
  }
  try {
    api.makePhoneCall({ phoneNumber });
    return true;
  } catch {
    return false;
  }
}

/** 复制文本；无实现/失败返回 false。 */
export function safeSetClipboardData(data: string, onCopied?: () => void): boolean {
  const api = uniApi();
  if (typeof api.setClipboardData !== 'function') {
    return false;
  }
  let ok = true;
  try {
    api.setClipboardData({
      data,
      success: () => onCopied?.(),
      fail: () => {
        ok = false;
      }
    });
  } catch {
    ok = false;
  }
  // H5 的实现通常是同步成功的 toast；这里以「API 存在」为成功基线，失败回调覆盖异常路径
  return ok;
}

export type PlatformCallStatus = 'success' | 'cancelled' | 'denied' | 'failed' | 'unsupported';

export interface ScanCodeResult {
  result: string | null;
  status: PlatformCallStatus;
}

function platformFailStatus(err?: unknown): PlatformCallStatus {
  const msg = String((err as { errMsg?: string })?.errMsg || '');
  if (/cancel/i.test(msg)) return 'cancelled';
  if (/auth deny|authorize|denied|permission/i.test(msg)) return 'denied';
  return 'failed';
}

/** 扫码并保留取消/拒权/失败终态，调用方必须对非成功结果给出反馈或明确降级。 */
export function safeScanCodeWithReason(): Promise<ScanCodeResult> {
  const api = uniApi();
  const scan = api.scanCode;
  if (typeof scan !== 'function') {
    return Promise.resolve({ result: null, status: 'unsupported' });
  }
  return new Promise((resolve) => {
    try {
      scan({
        success: (r) => {
          const result = String(r?.result || '').trim();
          resolve({ result: result || null, status: result ? 'success' : 'failed' });
        },
        fail: (err) => resolve({ result: null, status: platformFailStatus(err) })
      });
    } catch {
      resolve({ result: null, status: 'failed' });
    }
  });
}

/** 兼容旧调用：只取码文本；需要区分失败原因时用 safeScanCodeWithReason。 */
export function safeScanCode(): Promise<string | null> {
  return safeScanCodeWithReason().then((outcome) => outcome.result);
}

/** 选图；无实现/取消返回空数组，成功返回临时路径。 */
export function safeChooseImage(count = 1): Promise<string[]> {
  const api = uniApi();
  const choose = api.chooseImage;
  if (typeof choose !== 'function') {
    return Promise.resolve([]);
  }
  return new Promise((resolve) => {
    try {
      choose({
        count,
        success: (r) => resolve(r?.tempFilePaths ?? []),
        fail: () => resolve([])
      });
    } catch {
      resolve([]);
    }
  });
}

export interface ChooseImageResult {
  paths: string[];
  denied: boolean;
  status: PlatformCallStatus;
}

/**
 * 选图并区分成功、取消、拒权和失败。老基础库无法判定原因时保守归为 failed，
 * 由调用方提示重试，不能再把失败静默成空数组。
 */
export function safeChooseImageWithReason(
  count = 1,
  options?: { sourceType?: ('camera' | 'album')[]; sizeType?: ('original' | 'compressed')[] }
): Promise<ChooseImageResult> {
  const api = uniApi();
  const choose = api.chooseImage;
  if (typeof choose !== 'function') {
    return Promise.resolve({ paths: [], denied: false, status: 'unsupported' });
  }
  return new Promise((resolve) => {
    try {
      choose({
        count,
        sourceType: options?.sourceType,
        sizeType: options?.sizeType,
        success: (r) => {
          const paths = r?.tempFilePaths ?? [];
          resolve({ paths, denied: false, status: paths.length ? 'success' : 'failed' });
        },
        fail: (err) => {
          const status = platformFailStatus(err);
          resolve({ paths: [], denied: status === 'denied', status });
        }
      });
    } catch {
      resolve({ paths: [], denied: false, status: 'failed' });
    }
  });
}

export function chooseImageErrorMessage(result: ChooseImageResult, action = '选图'): string {
  if (result.status === 'denied') return `${action}失败，请允许相机或相册权限后重试`;
  if (result.status === 'unsupported') return '当前环境不支持选图，请在微信小程序中操作';
  if (result.status === 'cancelled') return `已取消${action}`;
  return `${action}失败，请检查相机或相册后重试`;
}

export function scanCodeErrorMessage(result: ScanCodeResult): string {
  if (result.status === 'denied') return '扫码失败，请允许相机权限后重试';
  if (result.status === 'unsupported') return '当前环境不支持扫码，请手动输入柜机编号';
  if (result.status === 'cancelled') return '已取消扫码';
  return '扫码失败，请重试或手动输入柜机编号';
}
