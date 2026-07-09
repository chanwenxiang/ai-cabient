const api = require('../../utils/api');
const { showError, sessionStateLabel, formatError } = require('../../utils/common');

Page({
  data: {
    deviceId: 'CAB-001',
    opening: false,
    tasks: [],
    inTransit: [],
    loadingTasks: false,
    restockSessionId: '',
    restockStateLabel: '',
    recognizing: false,
    recognitionHint: ''
  },

  onUnload() {
    this._stopRestockPoll();
  },

  _stopRestockPoll() {
    if (this._restockTimer) {
      clearInterval(this._restockTimer);
      this._restockTimer = null;
    }
  },

  _startRestockPoll(sessionId) {
    this._stopRestockPoll();
    this.setData({ restockSessionId: sessionId, restockStateLabel: '购物中，请补货后关门' });
    this._restockTimer = setInterval(async () => {
      try {
        const s = await api.getSession(sessionId);
        const label = sessionStateLabel(s.state);
        this.setData({ restockStateLabel: label });
        if (s.state === 'COMPLETED' || s.state === 'CANCELLED' || s.state === 'FAILED') {
          this._stopRestockPoll();
          wx.showToast({ title: s.state === 'COMPLETED' ? '补货已完成' : '会话已结束', icon: 'success' });
          this.setData({ restockSessionId: '', restockStateLabel: '' });
        }
      } catch (e) {
        /* 轮询失败时静默，用户可手动刷新 */
      }
    }, 3000);
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadTasks();
  },

  onDeviceInput(e) { this.setData({ deviceId: e.detail.value.trim() }); },

  async loadTasks() {
    if (this.data.loadingTasks) return;
    this.setData({ loadingTasks: true });
    try {
      const tasks = await api.listReplenishmentTasks();
      const deviceIds = [...new Set((tasks || []).map((t) => t.deviceId).filter(Boolean))];
      let inTransit = [];
      for (const dev of deviceIds) {
        try {
          const rows = await api.listWarehouseInTransit(dev);
          inTransit = inTransit.concat((rows || []).map((r) => ({ ...r, deviceId: r.deviceId || dev })));
        } catch (_) { /* ignore */ }
      }
      this.setData({
        inTransit,
        tasks: (tasks || []).map(t => ({
          ...t,
          checkedIn: !!t.checkInAt,
          statusLabel: t.status === 'COMPLETED' ? '已完成'
            : t.status === 'IN_PROGRESS' ? '进行中'
            : t.status === 'CANCELLED' ? '已取消' : '待补货'
        }))
      });
    } catch (e) {
      this.setData({ tasks: [] });
    } finally {
      this.setData({ loadingTasks: false });
    }
  },

  async onCompleteTask(e) {
    const taskId = e.currentTarget.dataset.id;
    if (!taskId) return;
    wx.showModal({
      title: '直接完成',
      content: '未录入明细时不会更新批次库存。建议先「录入明细」。仍要直接完成？',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          wx.showLoading({ title: '提交中' });
          await api.completeReplenishmentTask(taskId);
          wx.hideLoading();
          wx.showToast({ title: '已完成', icon: 'success' });
          this.loadTasks();
        } catch (err) {
          wx.hideLoading();
          showError('操作失败', err);
        }
      }
    });
  },

  onEditTask(e) {
    const { id, device, status, checkedIn } = e.currentTarget.dataset;
    if (!id) return;
    wx.navigateTo({
      url: `/pages/replenish-task/replenish-task?taskId=${id}&deviceId=${encodeURIComponent(device || '')}&status=${status || 'PENDING'}&checkedIn=${checkedIn ? '1' : '0'}`
    });
  },

  async onCheckInTask(e) {
    const taskId = parseInt(e.currentTarget.dataset.id, 10);
    if (!taskId) return;
    wx.getLocation({
      type: 'gcj02',
      success: async (loc) => {
        try {
          wx.showLoading({ title: '签到中' });
          await api.checkInReplenishmentTask(taskId, loc.latitude, loc.longitude);
          wx.hideLoading();
          wx.showToast({ title: '签到成功', icon: 'success' });
          this.loadTasks();
        } catch (err) {
          wx.hideLoading();
          showError('签到失败', err);
        }
      },
      fail: async () => {
        try {
          wx.showLoading({ title: '签到中' });
          await api.checkInReplenishmentTask(taskId, 31.2304, 121.4737);
          wx.hideLoading();
          wx.showToast({ title: '演示签到 OK', icon: 'none' });
          this.loadTasks();
        } catch (err) {
          wx.hideLoading();
          showError('签到失败', err);
        }
      }
    });
  },

  async onOpenTaskDoor(e) {
    if (this.data.opening) return;
    const taskId = parseInt(e.currentTarget.dataset.id, 10);
    const deviceId = (e.currentTarget.dataset.device || this.data.deviceId || '').trim();
    const checkedIn = e.currentTarget.dataset.checkedIn === '1' || e.currentTarget.dataset.checkedIn === true;
    if (!taskId || !deviceId) {
      wx.showToast({ title: '任务信息无效', icon: 'none' });
      return;
    }
    if (!checkedIn) {
      wx.showModal({
        title: '请先签到',
        content: '补货开门前须到店 GPS 签到。可点「录入明细」进入任务页签到。',
        showCancel: false
      });
      return;
    }
    this.setData({ opening: true });
    try {
      wx.showLoading({ title: '开门中' });
      const session = await api.opsOpenDoor(deviceId, taskId);
      wx.hideLoading();
      wx.showModal({
        title: '补货门已开',
        content: `任务 #${taskId}\n会话 ${session.sessionId}\n补货完成后请关门`,
        showCancel: false
      });
      this._startRestockPoll(session.sessionId);
      this.loadTasks();
    } catch (err) {
      wx.hideLoading();
      showError('开门失败', err);
    } finally {
      this.setData({ opening: false });
    }
  },

  async onOpsOpen() {
    wx.showToast({ title: '请从任务卡片点击「开门补货」', icon: 'none' });
  },

  onRecognitionTest() {
    if (this.data.recognizing) return;
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const file = res.tempFiles && res.tempFiles[0];
        if (!file || !file.tempFilePath) {
          wx.showToast({ title: '未选择图片', icon: 'none' });
          return;
        }
        this.runRecognitionPreview(file.tempFilePath);
      },
      fail: () => wx.showToast({ title: '已取消', icon: 'none' })
    });
  },

  async runRecognitionPreview(filePath) {
    this.setData({ recognizing: true, recognitionHint: '' });
    wx.showLoading({ title: '识别中...' });
    try {
      const preview = await api.uploadOpsRecognitionPreview(filePath);
      wx.hideLoading();
      this.setData({ recognizing: false, recognitionHint: preview.hint || '' });
      wx.showModal({
        title: '识别结果',
        content: this.formatRecognitionPreview(preview),
        confirmText: '知道了',
        showCancel: false
      });
    } catch (e) {
      wx.hideLoading();
      this.setData({ recognizing: false });
      const msg = formatError(e);
      if (/403|运营|operator/i.test(msg)) {
        wx.showModal({
          title: '需要运营账号',
          content: '识别测试仅运营账号可用。',
          showCancel: false
        });
        return;
      }
      showError('识别失败', e);
    }
  },

  formatRecognitionPreview(preview) {
    if (!preview) return '无识别结果';
    const lines = [];
    if (preview.items && preview.items.length) {
      preview.items.forEach((i) => {
        lines.push(`${i.skuName || i.skuId} x${i.quantity}（${Math.round((i.confidence || 0) * 100)}%）`);
      });
    }
    if (preview.detectedClasses && preview.detectedClasses.length && !lines.length) {
      lines.push('检测到：' + preview.detectedClasses.join('、'));
    }
    if (preview.modelVersion) {
      lines.push('模型：' + preview.modelVersion);
    }
    if (preview.hint) {
      lines.push(preview.hint);
    }
    return lines.join('\n') || '未识别到商品';
  }
});
