const api = require('../../utils/api');
const { showError } = require('../../utils/common');

const LINE_TYPES = [
  { value: 'RESTOCK', label: '上架 RESTOCK' },
  { value: 'PULL_OFF', label: '下架 PULL_OFF' }
];

function todayStr() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function addDaysStr(days) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function slotLabel(slot) {
  const sku = slot.assignedSkuId ? ` · ${slot.assignedSkuId}` : '';
  return `${slot.slotCode}${sku}`;
}

function slotsForSku(allSlots, skuId) {
  const enabled = (allSlots || []).filter((s) => s.enabled !== false);
  if (!skuId) return enabled;
  const matched = enabled.filter((s) => s.assignedSkuId === skuId);
  return matched.length ? matched : enabled;
}

function pickSlotForSku(allSlots, skuId, preferred) {
  const candidates = slotsForSku(allSlots, skuId);
  if (!candidates.length) return { slotIndex: -1, slotId: '' };
  const pref = (preferred || '').trim().toUpperCase();
  let idx = pref ? candidates.findIndex((s) => s.slotCode === pref) : -1;
  if (idx < 0) idx = 0;
  return { slotIndex: idx, slotId: candidates[idx].slotCode };
}

function defaultLine(skus, allSlots) {
  const skuId = skus[0]?.skuId || '';
  const slotPick = pickSlotForSku(allSlots, skuId, 'A1');
  return {
    lineType: 'RESTOCK',
    typeIndex: 0,
    skuIndex: 0,
    skuId,
    quantity: 1,
    batchNo: '',
    productionDate: todayStr(),
    expiryDate: addDaysStr(30),
    slotIndex: slotPick.slotIndex,
    slotId: slotPick.slotId
  };
}

function lineFromDto(dto, skus, allSlots) {
  const typeIndex = dto.lineType === 'PULL_OFF' ? 1 : 0;
  const skuIndex = Math.max(0, skus.findIndex((s) => s.skuId === dto.skuId));
  const skuId = skus[skuIndex]?.skuId || dto.skuId;
  const slotPick = pickSlotForSku(allSlots, skuId, dto.slotId);
  return {
    lineType: dto.lineType || 'RESTOCK',
    typeIndex,
    skuIndex,
    skuId,
    quantity: dto.quantity || 1,
    batchNo: dto.batchNo || '',
    productionDate: dto.productionDate || todayStr(),
    expiryDate: dto.expiryDate || addDaysStr(30),
    slotIndex: slotPick.slotIndex,
    slotId: slotPick.slotId
  };
}

function lineSlotOptions(allSlots, skuId) {
  return slotsForSku(allSlots, skuId).map((s) => ({
    slotCode: s.slotCode,
    label: slotLabel(s)
  }));
}

Page({
  data: {
    taskId: null,
    deviceId: '',
    statusLabel: '',
    skus: [],
    allSlots: [],
    lineTypes: LINE_TYPES,
    lines: [],
    loading: true,
    saving: false,
    completing: false,
    checkingIn: false,
    checkedIn: false,
    checkInTime: ''
  },

  onLoad(options) {
    this._taskId = parseInt(options.taskId, 10);
    this._deviceId = options.deviceId || '';
    this._status = options.status || 'PENDING';
    if (!this._taskId) {
      wx.showToast({ title: '任务无效', icon: 'none' });
      setTimeout(() => wx.navigateBack(), 800);
      return;
    }
    this.setData({
      taskId: this._taskId,
      deviceId: this._deviceId,
      statusLabel: this.statusLabel(this._status),
      checkedIn: options.checkedIn === '1'
    });
    this.loadAll();
  },

  onPullDownRefresh() {
    this.loadAll().finally(() => wx.stopPullDownRefresh());
  },

  statusLabel(status) {
    if (status === 'COMPLETED') return '已完成';
    if (status === 'IN_PROGRESS') return '进行中';
    if (status === 'CANCELLED') return '已取消';
    return '待补货';
  },

  decorateLines(lines, allSlots) {
    return lines.map((l) => ({
      ...l,
      slotOptions: lineSlotOptions(allSlots, l.skuId),
      slotLabel: (() => {
        const opts = lineSlotOptions(allSlots, l.skuId);
        if (l.slotIndex >= 0 && opts[l.slotIndex]) return opts[l.slotIndex].label;
        return l.slotId || '选择货道';
      })()
    }));
  },

  async loadAll() {
    this.setData({ loading: true });
    try {
      const slotPromise = this._deviceId
        ? api.listDeviceSlots(this._deviceId).catch(() => [])
        : Promise.resolve([]);
      const [skuList, existing, allSlots] = await Promise.all([
        api.listOpsSkus(),
        api.listReplenishmentTaskLines(this._taskId).catch(() => []),
        slotPromise
      ]);
      const skus = (skuList || [])
        .filter((s) => s.status === 'ACTIVE')
        .map((s) => ({ skuId: s.skuId, label: `${s.skuName} (${s.skuId})` }));
      if (!skus.length) {
        wx.showModal({
          title: '暂无商品',
          content: '请先在管理后台维护 SKU 目录',
          showCancel: false,
          success: () => wx.navigateBack()
        });
        return;
      }
      const rawLines = (existing && existing.length)
        ? existing.map((l) => lineFromDto(l, skus, allSlots))
        : [defaultLine(skus, allSlots)];
      this.setData({
        skus,
        allSlots: allSlots || [],
        lines: this.decorateLines(rawLines, allSlots),
        loading: false
      });
    } catch (e) {
      this.setData({ loading: false });
      showError('加载失败', e);
    }
  },

  onAddLine() {
    const raw = this.data.lines.map(({ slotOptions, slotLabel, ...rest }) => rest);
    raw.push(defaultLine(this.data.skus, this.data.allSlots));
    this.setData({ lines: this.decorateLines(raw, this.data.allSlots) });
  },

  onRemoveLine(e) {
    const idx = parseInt(e.currentTarget.dataset.idx, 10);
    const raw = this.data.lines
      .filter((_, i) => i !== idx)
      .map(({ slotOptions, slotLabel, ...rest }) => rest);
    const next = raw.length ? raw : [defaultLine(this.data.skus, this.data.allSlots)];
    this.setData({ lines: this.decorateLines(next, this.data.allSlots) });
  },

  onLineTypeChange(e) {
    const idx = parseInt(e.currentTarget.dataset.idx, 10);
    const typeIndex = parseInt(e.detail.value, 10);
    const raw = this.data.lines.map(({ slotOptions, slotLabel, ...rest }) => rest);
    raw[idx] = { ...raw[idx], typeIndex, lineType: LINE_TYPES[typeIndex].value };
    this.setData({ lines: this.decorateLines(raw, this.data.allSlots) });
  },

  onSkuChange(e) {
    const idx = parseInt(e.currentTarget.dataset.idx, 10);
    const skuIndex = parseInt(e.detail.value, 10);
    const sku = this.data.skus[skuIndex];
    const raw = this.data.lines.map(({ slotOptions, slotLabel, ...rest }) => rest);
    const slotPick = pickSlotForSku(this.data.allSlots, sku.skuId, raw[idx].slotId);
    raw[idx] = { ...raw[idx], skuIndex, skuId: sku.skuId, ...slotPick };
    this.setData({ lines: this.decorateLines(raw, this.data.allSlots) });
  },

  onSlotChange(e) {
    const idx = parseInt(e.currentTarget.dataset.idx, 10);
    const slotIndex = parseInt(e.detail.value, 10);
    const opts = lineSlotOptions(this.data.allSlots, this.data.lines[idx].skuId);
    const slot = opts[slotIndex];
    const raw = this.data.lines.map(({ slotOptions, slotLabel, ...rest }) => rest);
    raw[idx] = {
      ...raw[idx],
      slotIndex,
      slotId: slot ? slot.slotCode : ''
    };
    this.setData({ lines: this.decorateLines(raw, this.data.allSlots) });
  },

  onLineInput(e) {
    const idx = parseInt(e.currentTarget.dataset.idx, 10);
    const field = e.currentTarget.dataset.field;
    const raw = this.data.lines.map(({ slotOptions, slotLabel, ...rest }) => rest);
    raw[idx] = { ...raw[idx], [field]: e.detail.value };
    this.setData({ lines: this.decorateLines(raw, this.data.allSlots) });
  },

  onProdDateChange(e) {
    const idx = parseInt(e.currentTarget.dataset.idx, 10);
    const raw = this.data.lines.map(({ slotOptions, slotLabel, ...rest }) => rest);
    raw[idx] = { ...raw[idx], productionDate: e.detail.value };
    this.setData({ lines: this.decorateLines(raw, this.data.allSlots) });
  },

  onExpiryDateChange(e) {
    const idx = parseInt(e.currentTarget.dataset.idx, 10);
    const raw = this.data.lines.map(({ slotOptions, slotLabel, ...rest }) => rest);
    raw[idx] = { ...raw[idx], expiryDate: e.detail.value };
    this.setData({ lines: this.decorateLines(raw, this.data.allSlots) });
  },

  validateLines() {
    for (let i = 0; i < this.data.lines.length; i++) {
      const l = this.data.lines[i];
      if (l.lineType === 'RESTOCK' && !(l.slotId || '').trim()) {
        return `第 ${i + 1} 行上架须选择货道`;
      }
      const qty = parseInt(l.quantity, 10);
      if (!qty || qty <= 0) {
        return `第 ${i + 1} 行数量须大于 0`;
      }
    }
    return null;
  },

  buildPayload() {
    return this.data.lines.map((l) => {
      const sku = this.data.skus[l.skuIndex] || this.data.skus[0];
      const body = {
        lineType: l.lineType,
        skuId: sku.skuId,
        quantity: parseInt(l.quantity, 10) || 1,
        batchNo: (l.batchNo || '').trim() || null,
        slotId: (l.slotId || '').trim() || null
      };
      if (l.lineType === 'RESTOCK') {
        body.productionDate = l.productionDate || null;
        body.expiryDate = l.expiryDate || null;
      }
      return body;
    });
  },

  async onSaveLines() {
    if (this.data.saving) return;
    const err = this.validateLines();
    if (err) {
      wx.showToast({ title: err, icon: 'none' });
      return;
    }
    this.setData({ saving: true });
    try {
      await api.submitReplenishmentLines(this._taskId, this.buildPayload());
      wx.showToast({ title: '已保存', icon: 'success' });
      this.setData({ statusLabel: '进行中' });
    } catch (e) {
      showError('保存失败', e);
    } finally {
      this.setData({ saving: false });
    }
  },

  onCheckIn() {
    if (this.data.checkingIn || this.data.checkedIn) return;
    this.setData({ checkingIn: true });
    wx.getLocation({
      type: 'gcj02',
      success: async (loc) => {
        try {
          await api.checkInReplenishmentTask(this._taskId, loc.latitude, loc.longitude);
          const t = new Date();
          const time = `${t.getHours()}:${String(t.getMinutes()).padStart(2, '0')}`;
          this.setData({ checkedIn: true, checkInTime: time, statusLabel: '进行中' });
          wx.showToast({ title: '签到成功', icon: 'success' });
        } catch (e) {
          showError('签到失败', e);
        } finally {
          this.setData({ checkingIn: false });
        }
      },
      fail: async () => {
        try {
          await api.checkInReplenishmentTask(this._taskId, 31.2304, 121.4737);
          this.setData({ checkedIn: true, checkInTime: '演示', statusLabel: '进行中' });
          wx.showToast({ title: '演示签到 OK', icon: 'none' });
        } catch (e) {
          showError('签到失败', e);
        } finally {
          this.setData({ checkingIn: false });
        }
      }
    });
  },

  async onCompleteTask() {
    if (this.data.completing) return;
    const err = this.validateLines();
    if (err) {
      wx.showToast({ title: err, icon: 'none' });
      return;
    }
    const ok = await new Promise((resolve) => {
      wx.showModal({
        title: '完成任务',
        content: '将应用补货明细并更新批次库存，确认完成？',
        success: (r) => resolve(!!r.confirm)
      });
    });
    if (!ok) return;
    this.setData({ completing: true });
    try {
      wx.showLoading({ title: '提交中' });
      await api.submitReplenishmentLines(this._taskId, this.buildPayload());
      await api.completeReplenishmentTask(this._taskId);
      wx.hideLoading();
      wx.showToast({ title: '补货完成', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 600);
    } catch (e) {
      wx.hideLoading();
      showError('完成失败', e);
    } finally {
      this.setData({ completing: false });
    }
  }
});
