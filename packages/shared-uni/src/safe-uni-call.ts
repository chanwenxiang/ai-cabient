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

/** 扫码；无实现/用户取消返回 null，成功返回码文本。 */
export function safeScanCode(): Promise<string | null> {
  const api = uniApi();
  const scan = api.scanCode;
  if (typeof scan !== 'function') {
    return Promise.resolve(null);
  }
  return new Promise((resolve) => {
    try {
      scan({
        success: (r) => resolve(r?.result ?? null),
        fail: () => resolve(null)
      });
    } catch {
      resolve(null);
    }
  });
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
