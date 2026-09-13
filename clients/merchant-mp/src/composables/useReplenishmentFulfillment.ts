import type { ComputedRef, Ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
import { merchantApi } from '@/utils/merchant-api';

type Task = import('@aicabinet/shared-types').OpenApiReplenishmentTaskDto;
type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;

type AskConfirm = (opts: {
  title: string;
  content: string;
  confirmText?: string;
  cancelText?: string;
  rememberLabel?: string;
  rememberDefault?: boolean;
}) => Promise<boolean>;

/** H5 浏览器常挂起权限弹窗；小程序偶发超时 — 超时后走无定位签到 */
export function getLocationWithTimeout(timeoutMs = 5000): Promise<UniApp.GetLocationSuccess> {
  return new Promise((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      reject(new Error('定位超时'));
    }, timeoutMs);
    uni.getLocation({
      type: 'gcj02',
      success(res) {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(res);
      },
      fail(err) {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        reject(
          err instanceof Error
            ? err
            : new Error(String((err as { errMsg?: string })?.errMsg || '定位失败'))
        );
      }
    });
  });
}

export function isDistanceCheckError(msg: string): boolean {
  return msg.includes('签到位置') || msg.includes('超出') || msg.includes('米');
}

/**
 * 补货详情履约：签到 → 开门 → 核对清单 → 完成。
 * 列表/深链/证据上传仍由页面编排。
 */
export function useReplenishmentFulfillment(opts: {
  selected: Ref<Task | null>;
  lines: Ref<Line[]>;
  linesConfirmed: Ref<boolean>;
  evidenceItems: Ref<{ localPath: string; fileId?: number }[]>;
  submitting: Ref<boolean>;
  doorOpened: Ref<boolean>;
  openSessionId: Ref<string>;
  skipLocationCheck: Ref<boolean>;
  lineSummaryMap: Ref<Record<number, string>>;
  allTasks: Ref<Task[]>;
  canRequest: ComputedRef<boolean>;
  canSkipLocation: boolean;
  requireReplenishmentEvidence: ComputedRef<boolean>;
  requireReplenishmentDoor: ComputedRef<boolean>;
  requireReplenishmentCheckInLocation: ComputedRef<boolean>;
  checkInMaxDistanceM: ComputedRef<number>;
  detailIsPullOff: ComputedRef<boolean>;
  askConfirm: AskConfirm;
  getSkipCheckInLocation: () => boolean;
  persistDoorState: (taskId: number, sessionId: string) => void;
  clearDoorState: (taskId?: number) => void;
  syncTaskInList: (task: Task) => void;
  reloadList: () => Promise<void>;
  addEvidence: () => void | Promise<void>;
  isPullOffType: (type?: string) => boolean;
  formatLineSummary: (rows: Line[]) => string;
  slotHeadroom: (line: Line) => number;
}) {
  async function obtainCheckInLocation(): Promise<{
    body: Record<string, number>;
    locationOk: boolean;
  } | null> {
    if (!opts.requireReplenishmentCheckInLocation.value) {
      return { body: {}, locationOk: false };
    }
    if (opts.canSkipLocation && (opts.skipLocationCheck.value || opts.getSkipCheckInLocation())) {
      opts.skipLocationCheck.value = true;
      return { body: {}, locationOk: false };
    }
    try {
      const location = await getLocationWithTimeout(5000);
      return {
        body: { latitude: location.latitude, longitude: location.longitude },
        locationOk: true
      };
    } catch {
      const cont = await opts.askConfirm({
        title: '定位失败',
        content:
          opts.checkInMaxDistanceM.value > 0
            ? `无法获取当前位置。请开启定位权限后重试；柜机已配置坐标时须在约 ${opts.checkInMaxDistanceM.value} 米内签到。`
            : '无法获取当前位置。请开启定位权限后重试；本柜签到需带定位。',
        confirmText: '重试',
        cancelText: '取消'
      });
      if (!cont) return null;
      try {
        const location = await getLocationWithTimeout(8000);
        return {
          body: { latitude: location.latitude, longitude: location.longitude },
          locationOk: true
        };
      } catch {
        showError('仍无法定位，请到柜前开启 GPS 后重试');
        return null;
      }
    }
  }

  function requireSelectedTaskId(): number | null {
    const taskId = opts.selected.value?.taskId;
    return typeof taskId === 'number' && Number.isFinite(taskId) ? taskId : null;
  }

  async function submitCheckIn(body: Record<string, number>, locationOk: boolean) {
    const taskId = requireSelectedTaskId();
    if (taskId == null) return;
    opts.selected.value = (await merchantApi.checkInReplenishmentTask(taskId, body)) as Task;
    opts.syncTaskInList(opts.selected.value);
    const skipTitle = !opts.requireReplenishmentCheckInLocation.value
      ? '已签到（未校验定位）'
      : '签到成功';
    if (locationOk) showSuccess('签到成功');
    else showError(skipTitle);
  }

  async function handleCheckInDistanceFailure(msg: string) {
    showError(msg || '距离过远，请到柜前再签到', 3600);
  }

  async function checkIn() {
    if (!opts.selected.value || opts.submitting.value) return;
    if (!opts.canRequest.value) {
      showError('无补货操作权限');
      return;
    }
    opts.submitting.value = true;
    const location = await obtainCheckInLocation();
    if (!location) {
      opts.submitting.value = false;
      return;
    }
    try {
      await submitCheckIn(location.body, location.locationOk);
    } catch (error) {
      const msg = error instanceof Error ? error.message : '签到失败';
      if (location.locationOk && isDistanceCheckError(msg)) {
        await handleCheckInDistanceFailure(msg);
      } else {
        showError(msg, 3600);
      }
    } finally {
      opts.submitting.value = false;
    }
  }

  function openDoorConfirmTitle(): string {
    if (opts.doorOpened.value) return '再次开门';
    if (opts.detailIsPullOff.value) return '下架开门';
    return '补货开门';
  }

  async function applyOpenDoorSession(taskId: number, session: { sessionId?: string }) {
    if (!opts.selected.value) return;
    opts.doorOpened.value = true;
    opts.openSessionId.value = session.sessionId || '';
    if (session.sessionId) opts.persistDoorState(taskId, session.sessionId);
    opts.selected.value = {
      ...opts.selected.value,
      status: opts.selected.value.status === 'PENDING' ? 'IN_PROGRESS' : opts.selected.value.status
    };
    showSuccess('开门指令已下发');
    await opts.reloadList();
    const fresh = opts.allTasks.value.find((t) => t.taskId === taskId);
    if (fresh) opts.selected.value = { ...fresh };
  }

  async function openDoor() {
    const taskId = requireSelectedTaskId();
    if (taskId == null || opts.submitting.value) return;
    if (!opts.canRequest.value) {
      showError('无补货操作权限');
      return;
    }
    if (!opts.selected.value?.checkInAt) {
      showError('请先现场签到');
      return;
    }
    const ok = await opts.askConfirm({
      title: openDoorConfirmTitle(),
      content: '将下发开门指令，本次为补货会话，不会按购物扣款。请确认人在柜前。',
      confirmText: '开门',
      cancelText: '取消'
    });
    if (!ok) return;
    opts.submitting.value = true;
    try {
      const session = await merchantApi.openReplenishmentDoor(taskId);
      await applyOpenDoorSession(taskId, session);
    } catch (error) {
      const msg = error instanceof Error ? error.message : '开门失败';
      showError(msg, 3200);
    } finally {
      opts.submitting.value = false;
    }
  }

  function canAdjustLineQty(line: Line): boolean {
    if (!opts.canRequest.value) return false;
    if (opts.linesConfirmed.value || line.applied || opts.selected.value?.status === 'COMPLETED') {
      return false;
    }
    return true;
  }

  function increaseLineQty(line: Line, delta: number) {
    const cur = Number(line.quantity) || 0;
    if (opts.isPullOffType(line.lineType)) {
      line.quantity = cur + delta;
      return;
    }
    const room = opts.slotHeadroom(line);
    if (cur >= room) {
      showError(room <= 0 ? '货道已满，无法再加' : `最多再补 ${room}`);
      return;
    }
    line.quantity = Math.min(room, cur + delta);
  }

  function decreaseLineQty(line: Line, delta: number) {
    const cur = Number(line.quantity) || 0;
    line.quantity = Math.max(0, cur + delta);
  }

  function adjustQty(line: Line, delta: number) {
    if (!canAdjustLineQty(line)) return;
    if (delta > 0) increaseLineQty(line, delta);
    else decreaseLineQty(line, delta);
  }

  function clampLinesToCapacity() {
    let changed = false;
    for (const line of opts.lines.value) {
      if (line.applied || opts.isPullOffType(line.lineType)) continue;
      const room = opts.slotHeadroom(line);
      const qty = Number(line.quantity) || 0;
      if (qty > room) {
        line.quantity = room;
        changed = true;
      }
    }
    return changed;
  }

  function buildConfirmLinePayload(line: Line) {
    return {
      skuId: line.skuId,
      quantity: line.quantity,
      lineType: line.lineType || 'RESTOCK',
      batchNo: line.batchNo,
      productionDate: line.productionDate,
      expiryDate: line.expiryDate,
      slotId: line.slotId
    };
  }

  async function ensureLinesWithinCapacity(): Promise<boolean> {
    const over = opts.lines.value.filter(
      (l) =>
        !l.applied &&
        !opts.isPullOffType(l.lineType) &&
        (Number(l.quantity) || 0) > opts.slotHeadroom(l)
    );
    if (!over.length) return true;
    const overSummary = over
      .map((l) => `${l.slotId || '?'} 最多再补 ${opts.slotHeadroom(l)}`)
      .join('；');
    const ok = await opts.askConfirm({
      title: '货道容量不足',
      content: `${overSummary}。是否自动调低数量后继续？`,
      confirmText: '自动调低',
      cancelText: '手动改'
    });
    if (!ok) return false;
    clampLinesToCapacity();
    return true;
  }

  function validatePositiveLines(): Line[] | null {
    const positive = opts.lines.value.filter((l) => (Number(l.quantity) || 0) > 0);
    if (!positive.length) {
      showError('调低后无有效数量，请换货道或取消该行');
      return null;
    }
    const unassigned = positive.filter(
      (l) => !opts.isPullOffType(l.lineType) && !String(l.slotId || '').trim()
    );
    if (unassigned.length) {
      showError('请先为待分配行选择货道');
      return null;
    }
    return positive;
  }

  async function handleConfirmLinesFailure(msg: string) {
    if (!msg.includes('容量不足')) {
      showError(msg, 3600);
      return;
    }
    const auto = await opts.askConfirm({
      title: '确认失败',
      content: `${msg}\n\n是否按货道余量自动调低？`,
      confirmText: '自动调低',
      cancelText: '知道了'
    });
    if (auto) clampLinesToCapacity();
  }

  async function confirmLines() {
    const taskId = requireSelectedTaskId();
    if (taskId == null || opts.submitting.value) return;
    if (!opts.canRequest.value) {
      showError('无补货操作权限');
      return;
    }
    if (!(await ensureLinesWithinCapacity())) return;
    const positive = validatePositiveLines();
    if (!positive) return;
    opts.submitting.value = true;
    try {
      opts.lines.value = (await merchantApi.confirmReplenishmentLines(
        taskId,
        positive.map(buildConfirmLinePayload)
      )) as Line[];
      opts.linesConfirmed.value = true;
      opts.lineSummaryMap.value = {
        ...opts.lineSummaryMap.value,
        [taskId]: opts.formatLineSummary(opts.lines.value)
      };
      showSuccess('清单已确认');
    } catch (error) {
      const msg = error instanceof Error ? error.message : '确认失败';
      await handleConfirmLinesFailure(msg);
    } finally {
      opts.submitting.value = false;
    }
  }

  function pullOffCopy(restockText: string, pullOffText: string): string {
    return opts.detailIsPullOff.value ? pullOffText : restockText;
  }

  async function confirmDoorOpenedIfNeeded(): Promise<boolean> {
    if (!opts.requireReplenishmentDoor.value) return true;
    if (opts.doorOpened.value || opts.openSessionId.value) return true;
    await opts.askConfirm({
      title: '尚未开门',
      content: pullOffCopy(
        '请先下发补货开门，到柜完成后再确认任务。',
        '请先下发下架开门，到柜完成后再确认任务。'
      ),
      confirmText: '去开门',
      cancelText: '关闭'
    });
    return false;
  }

  async function confirmEvidenceIfNeeded(): Promise<boolean> {
    if (!opts.requireReplenishmentEvidence.value) return true;
    if (opts.evidenceItems.value.length > 0) return true;
    const goPhoto = await opts.askConfirm({
      title: '缺少现场凭证',
      content: pullOffCopy(
        '请至少上传 1 张补货现场照片，便于后台抽检。',
        '请至少上传 1 张下架现场照片，便于后台抽检。'
      ),
      confirmText: '去拍照',
      cancelText: '关闭'
    });
    if (goPhoto) {
      if (opts.selected.value?.checkInAt) await opts.addEvidence();
      else showError('请先签到再拍照');
    }
    return false;
  }

  async function confirmCompleteAction(): Promise<boolean> {
    return opts.askConfirm({
      title: pullOffCopy('确认全部上架', '确认全部下架'),
      content: pullOffCopy(
        '完成后将更新柜机库存并签收在途商品，请确认商品、批次和货道无误。',
        '完成后将扣减柜机库存，请确认下架商品、批次和数量无误。'
      ),
      confirmText: '确认完成',
      cancelText: '取消'
    });
  }

  async function finalizeCompletedTask(taskId: number) {
    opts.selected.value = (await merchantApi.completeReplenishmentTask(taskId)) as Task;
    opts.lines.value = opts.lines.value.map((line) => ({ ...line, applied: true }));
    opts.clearDoorState(taskId);
    showSuccess(opts.detailIsPullOff.value ? '下架完成' : '补货完成');
    await opts.reloadList();
    const fresh = opts.allTasks.value.find((t) => t.taskId === taskId);
    if (fresh) opts.selected.value = { ...fresh };
  }

  async function completeTask() {
    const taskId = requireSelectedTaskId();
    if (taskId == null || opts.submitting.value) return;
    if (!opts.canRequest.value) {
      showError('无补货操作权限');
      return;
    }
    if (!opts.linesConfirmed.value) {
      showError('请先确认商品与数量');
      return;
    }
    if (!(await confirmDoorOpenedIfNeeded())) return;
    if (!(await confirmEvidenceIfNeeded())) return;
    if (!(await confirmCompleteAction())) return;
    opts.submitting.value = true;
    try {
      await finalizeCompletedTask(taskId);
    } catch (error) {
      showError(error instanceof Error ? error.message : '完成失败');
    } finally {
      opts.submitting.value = false;
    }
  }

  return {
    checkIn,
    openDoor,
    canAdjustLineQty,
    adjustQty,
    confirmLines,
    completeTask
  };
}
