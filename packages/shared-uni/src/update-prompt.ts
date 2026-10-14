/**
 * 小程序版本更新提示（consumer-mp / merchant-mp 共用）。
 *
 * 此前两端都没有接入 uni.getUpdateManager：发新版后老用户停留在旧包，
 * 接口一旦变更旧包静默出错。接入后：
 * - 新包下载完成 → 弹「发现新版本」，确认后 applyUpdate 重启进新包；
 * - 下载失败 → 提示检查网络（下次冷启动微信自然换新包）；
 * - 非微信小程序平台（H5 等）无 getUpdateManager，运行时探测后静默跳过。
 *
 * 不做用户开关：这是运维兜底能力，不属个人偏好；设置里的自定义项见 haptic。
 */
interface MpUpdateManager {
  onUpdateReady(cb: () => void): void;
  onUpdateFailed(cb: () => void): void;
  applyUpdate(): void;
}

function getUpdateManager(): MpUpdateManager | null {
  const u = (globalThis as Record<string, unknown>).uni as {
    getUpdateManager?: () => MpUpdateManager;
  } | null;
  if (u && typeof u.getUpdateManager === 'function') {
    return u.getUpdateManager();
  }
  return null;
}

export function installUpdatePrompt(): void {
  const mgr = getUpdateManager();
  if (!mgr) return;
  mgr.onUpdateReady(() => {
    uni.showModal({
      title: '发现新版本',
      content: '新版本已就绪，是否立即重启应用？',
      confirmText: '立即重启',
      cancelText: '稍后',
      success: (res) => {
        if (res.confirm) mgr.applyUpdate();
      }
    });
  });
  mgr.onUpdateFailed(() => {
    uni.showModal({
      title: '更新失败',
      content: '新版本下载失败，请检查网络后重新进入小程序',
      showCancel: false
    });
  });
}
