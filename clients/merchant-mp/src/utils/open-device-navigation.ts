/**
 * 柜机地图导航：跳转前二次确认，避免误触直接离开小程序/H5。
 */

export type DeviceNavTarget = {
  latitude?: number | null;
  longitude?: number | null;
  name?: string | null;
  address?: string | null;
  deviceId?: string | null;
};

const HOST_ID = 'merchant-nav-confirm';

function isBrowserH5(): boolean {
  return typeof globalThis !== 'undefined' && typeof document !== 'undefined';
}

function navLabel(target: DeviceNavTarget): string {
  return String(target.name || target.deviceId || '柜机').trim() || '柜机';
}

function buildAmapUrl(longitude: number, latitude: number, label: string): string {
  return `https://uri.amap.com/marker?position=${longitude},${latitude}&name=${encodeURIComponent(label)}`;
}

function launchMap(longitude: number, latitude: number, name: string, address: string): void {
  if (isBrowserH5()) {
    globalThis.open(buildAmapUrl(longitude, latitude, name || address), '_blank');
    return;
  }
  uni.openLocation({
    latitude: Number(latitude),
    longitude: Number(longitude),
    name,
    address
  });
}

let activeH5Finish: ((ok: boolean) => void) | null = null;

function confirmNative(title: string, content: string): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title,
      content,
      confirmText: '去导航',
      cancelText: '取消',
      success(res) {
        resolve(!!res.confirm);
      },
      fail() {
        resolve(false);
      }
    });
  });
}

function confirmH5(
  title: string,
  content: string,
  testId = 'nav-confirm-dialog'
): Promise<boolean> {
  return new Promise((resolve) => {
    if (activeH5Finish) {
      const prev = activeH5Finish;
      activeH5Finish = null;
      prev(false);
    }
    const existing = document.getElementById(HOST_ID);
    if (existing) existing.remove();

    const host = document.createElement('div');
    host.id = HOST_ID;
    host.dataset.testid = testId;
    host.setAttribute('role', 'dialog');
    host.setAttribute('aria-modal', 'true');
    host.setAttribute('aria-label', title);

    const styleEl = document.createElement('style');
    styleEl.textContent = `
        #${HOST_ID} {
          position: fixed;
          inset: 0;
          z-index: 10060;
          display: flex;
          align-items: center;
          justify-content: center;
          padding: max(24px, env(safe-area-inset-top)) 20px max(24px, env(safe-area-inset-bottom));
          box-sizing: border-box;
          background: rgba(15, 23, 42, 0.55);
          overscroll-behavior: contain;
          font-family: var(--app-font, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans SC", sans-serif);
        }
        #${HOST_ID} .mnc-card {
          width: 100%;
          max-width: 320px;
          padding: 22px 20px 16px;
          border-radius: 16px;
          background: #fff;
          box-shadow: 0 18px 40px rgba(15, 23, 42, 0.2);
          box-sizing: border-box;
        }
        #${HOST_ID} .mnc-title {
          display: block;
          font-size: 17px;
          font-weight: 650;
          color: #0f172a;
          text-align: center;
        }
        #${HOST_ID} .mnc-body {
          display: block;
          margin-top: 10px;
          font-size: 14px;
          line-height: 1.5;
          color: #475569;
          text-align: center;
          white-space: pre-wrap;
        }
        #${HOST_ID} .mnc-actions {
          display: flex;
          gap: 10px;
          margin-top: 18px;
        }
        #${HOST_ID} .mnc-btn {
          flex: 1;
          margin: 0;
          padding: 11px 12px;
          border: none;
          border-radius: 12px;
          font-size: 15px;
          font-weight: 600;
          font-family: inherit;
          cursor: pointer;
          line-height: 1.2;
        }
        #${HOST_ID} .mnc-btn.cancel { color: #334155; background: #f1f5f9; }
        #${HOST_ID} .mnc-btn.ok {
          color: #fff;
          background: linear-gradient(135deg, #0f766e, #14b8a6);
        }
        #${HOST_ID} .mnc-btn:active { opacity: 0.88; }
    `;
    const card = document.createElement('div');
    card.className = 'mnc-card';
    const titleEl = document.createElement('span');
    titleEl.className = 'mnc-title';
    titleEl.textContent = title;
    const bodyEl = document.createElement('span');
    bodyEl.className = 'mnc-body';
    bodyEl.textContent = content;
    const actions = document.createElement('div');
    actions.className = 'mnc-actions';
    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'mnc-btn cancel';
    cancelBtn.dataset.testid = 'nav-confirm-cancel';
    cancelBtn.textContent = '取消';
    const okBtn = document.createElement('button');
    okBtn.type = 'button';
    okBtn.className = 'mnc-btn ok';
    okBtn.dataset.testid = 'nav-confirm-ok';
    okBtn.textContent = '去导航';
    actions.append(cancelBtn, okBtn);
    card.append(titleEl, bodyEl, actions);
    host.append(styleEl, card);

    const finish = (ok: boolean) => {
      if (activeH5Finish !== finish) return;
      activeH5Finish = null;
      document.removeEventListener('keydown', onKeyDown, true);
      host.remove();
      resolve(ok);
    };
    activeH5Finish = finish;

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        finish(false);
      }
    };

    host.addEventListener('click', (e) => {
      if (e.target === host) finish(false);
    });
    cancelBtn.addEventListener('click', () => finish(false));
    okBtn.addEventListener('click', () => finish(true));
    document.addEventListener('keydown', onKeyDown, true);
    document.body.appendChild(host);
  });
}

async function confirmNavigate(label: string, address?: string): Promise<boolean> {
  const title = '打开地图导航';
  const addr = String(address || '').trim();
  const content = addr
    ? `将离开当前页面，在高德地图中导航至「${label}」\n${addr}`
    : `将离开当前页面，在高德地图中导航至「${label}」`;
  if (isBrowserH5()) {
    return confirmH5(title, content);
  }
  return confirmNative(title, content);
}

/** 校验坐标 → 二次确认 → 打开高德（H5）或系统地图（小程序）。 */
export async function confirmOpenDeviceNavigation(target: DeviceNavTarget): Promise<void> {
  const lat = Number(target.latitude);
  const lng = Number(target.longitude);
  if (
    !Number.isFinite(lat) ||
    !Number.isFinite(lng) ||
    lat < -90 ||
    lat > 90 ||
    lng < -180 ||
    lng > 180
  ) {
    uni.showToast({ title: '坐标无效', icon: 'none' });
    return;
  }
  const label = navLabel(target);
  const ok = await confirmNavigate(label, target.address || undefined);
  if (!ok) return;
  launchMap(lng, lat, label, String(target.address || '').trim());
}
