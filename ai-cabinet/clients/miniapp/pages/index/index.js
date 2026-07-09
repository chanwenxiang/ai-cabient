const api = require('../../utils/api');
const { parseCabinetScan, parseLaunchOptions } = require('../../utils/qrcode');
const { sessionStateLabel, sessionStateHint, sessionStateTone, showError, formatError } = require('../../utils/common');

const MIN_BALANCE_CENTS = 500;

const CATEGORY_META = {
  '饮料': { icon: '🥤', bg: '#e6f4ff' },
  '零食': { icon: '🍿', bg: '#fff7e6' },
  '乳品': { icon: '🥛', bg: '#f9f0ff' },
  '方便食品': { icon: '🍜', bg: '#fff1f0' },
  '其他': { icon: '📦', bg: '#f5f5f5' }
};

Page({
  data: {
    deviceId: 'CAB-001',
    deviceName: '',
    deviceOnline: null,
    deviceAvailable: true,
    deviceStatusText: '',
    deviceStatusSub: '',
    deviceStatusChip: '',
    deviceStatusClass: '',
    products: [],
    productGroups: [],
    productsLoading: false,
    cartItems: [],
    cartCount: 0,
    cartTotalYuan: '0.00',
    cartSheetOpen: false,
    sessionId: '',
    state: '',
    stateLabel: '',
    stateHint: '',
    stateTone: 'idle',
    balance: '',
    balanceLow: false,
    balanceRefreshing: false,
    opening: false,
    polling: false,
    pollError: '',
    scanHint: ''
  },

  onLoad(options) {
    this._launchHandled = false;
    const launch = parseLaunchOptions(options || {});
    if (launch.deviceId) {
      this.setData({ deviceId: launch.deviceId });
      this._pendingAutoOpen = !!launch.autoOpen;
    }
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadBalance();
    if (this.data.deviceId) {
      this.refreshDeviceStatus(this.data.deviceId);
      this.loadProducts(this.data.deviceId);
    }
    this.startDeviceStatusPolling();
    this.restoreActiveSession();
    if (!this.data.polling && !this.data.opening) {
      this.resetSessionIfDone();
    }
    if (this._pendingAutoOpen && this.data.deviceId && !this._launchHandled) {
      this._launchHandled = true;
      this._pendingAutoOpen = false;
      setTimeout(() => this.beginOpenFlow(this.data.deviceId, '扫码进入'), 300);
    }
  },

  onHide() {
    this.stopDeviceStatusPolling();
  },

  onUnload() {
    this.stopPolling();
    this.stopDeviceStatusPolling();
  },

  onPullDownRefresh() {
    this.refreshAll(true).finally(() => wx.stopPullDownRefresh());
  },

  onRefreshBalance() {
    if (this.data.balanceRefreshing) return;
    this.refreshAll(true);
  },

  async refreshAll(showToast) {
    this.setData({ balanceRefreshing: true });
    try {
      await Promise.all([
        this.loadBalance(),
        this.refreshDeviceStatus(this.data.deviceId),
        this.loadProducts(this.data.deviceId),
        this.data.sessionId
          ? api.getSession(this.data.sessionId).then((s) => this.setData(this.sessionView(s))).catch(() => {})
          : Promise.resolve()
      ]);
      if (showToast) {
        wx.showToast({ title: '已刷新', icon: 'none', duration: 1200 });
      }
    } finally {
      this.setData({ balanceRefreshing: false });
    }
  },

  startDeviceStatusPolling() {
    this.stopDeviceStatusPolling();
    this._devicePollTimer = setInterval(() => {
      if (!this.data.opening && !this.data.polling && this.data.deviceId) {
        this.refreshDeviceStatus(this.data.deviceId);
      }
    }, 30000);
  },

  stopDeviceStatusPolling() {
    if (this._devicePollTimer) {
      clearInterval(this._devicePollTimer);
      this._devicePollTimer = null;
    }
  },

  async loadBalance() {
    try {
      const acc = await api.getAccount();
      const balanceCents = acc.balanceCents || 0;
      this.setData({
        balance: (balanceCents / 100).toFixed(2),
        balanceLow: !acc.operator && balanceCents < MIN_BALANCE_CENTS
      });
      return acc;
    } catch (e) {
      /* 首页余额非关键路径 */
      return null;
    }
  },

  async ensureCanOpenDoor() {
    let acc;
    try {
      acc = await api.getAccount();
    } catch (e) {
      showError('无法获取账户信息', e);
      return false;
    }
    const balanceCents = acc.balanceCents || 0;
    this.setData({
      balance: (balanceCents / 100).toFixed(2),
      balanceLow: !acc.operator && balanceCents < MIN_BALANCE_CENTS
    });
    if (acc.operator) return true;
    if (!acc.verified) {
      wx.showModal({
        title: '需要实名认证',
        content: '开门购物前需完成实名认证。',
        confirmText: '去认证',
        cancelText: '取消',
        success: (res) => {
          if (res.confirm) wx.navigateTo({ url: '/pages/verify/verify' });
        }
      });
      return false;
    }
    if (balanceCents < MIN_BALANCE_CENTS) {
      wx.showModal({
        title: '余额不足',
        content: '开门购物需账户余额至少 ¥' + (MIN_BALANCE_CENTS / 100).toFixed(2) + '，请先充值。',
        confirmText: '去充值',
        cancelText: '取消',
        success: (res) => {
          if (res.confirm) wx.navigateTo({ url: '/pages/recharge/recharge' });
        }
      });
      return false;
    }
    return true;
  },

  async restoreActiveSession() {
    const savedId = wx.getStorageSync('active_session_id');
    if (!savedId || this.data.polling || this.data.opening) return;
    try {
      const session = await api.getSession(savedId);
      const terminal = ['COMPLETED', 'FAILED', 'CANCELLED'];
      if (terminal.includes(session.state)) {
        wx.removeStorageSync('active_session_id');
        return;
      }
      this.setData(this.sessionView(session));
      if (session.state === 'DISPUTED') return;
      this.startPolling(savedId);
    } catch (e) {
      wx.removeStorageSync('active_session_id');
    }
  },

  retryPoll() {
    if (!this.data.sessionId) return;
    this.setData({ pollError: '' });
    this.startPolling(this.data.sessionId);
  },

  onScan() {
    if (this.data.opening || this.data.polling) return;
    wx.scanCode({
      scanType: ['qrCode', 'barCode'],
      onlyFromCamera: false,
      success: (res) => {
        this.handleScanResult(res.result || '', true);
      },
      fail: () => wx.showToast({ title: '扫码取消', icon: 'none' })
    });
  },

  async handleScanResult(raw, autoOpen) {
    const parsed = parseCabinetScan(raw);
    if (parsed.alipayOnly) {
      wx.showModal({
        title: '请使用支付宝扫码',
        content: '该码为支付宝小程序专用入口。请打开支付宝，扫描柜机上的同一二维码开门购物。',
        confirmText: '知道了',
        showCancel: false
      });
      return;
    }
    if (!parsed.deviceId) {
      wx.showModal({
        title: '无法识别柜机',
        content: '未从二维码中解析到设备编号。请扫描柜机二维码，或手动输入设备编号（如 CAB-001）。',
        confirmText: '知道了',
        showCancel: false
      });
      return;
    }

    const channelLabel = { WECHAT: '微信', ALIPAY: '支付宝', URL: '链接', PLAIN: '设备码' }[parsed.channel] || '二维码';
    this.setData({
      deviceId: parsed.deviceId,
      scanHint: `已识别${channelLabel}：${parsed.deviceId}`
    });
    await this.refreshDeviceStatus(parsed.deviceId);
    await this.loadProducts(parsed.deviceId);

    if (autoOpen || parsed.autoOpen) {
      await this.beginOpenFlow(parsed.deviceId, '扫码开门');
      return;
    }

    wx.showModal({
      title: '已识别柜机',
      content: `${this.data.deviceName || parsed.deviceId}\n确认在此柜机购物并开门？`,
      confirmText: '开门',
      cancelText: '取消',
      success: (r) => {
        if (r.confirm) this.beginOpenFlow(parsed.deviceId, '扫码开门');
      }
    });
  },

  async beginOpenFlow(deviceId, sourceLabel) {
    if (this.data.opening || this.data.polling) return;
    const id = (deviceId || this.data.deviceId || '').trim();
    if (!id) {
      wx.showToast({ title: '请输入设备编号', icon: 'none' });
      return;
    }
    this.setData({ deviceId: id, scanHint: sourceLabel ? `${sourceLabel} · ${id}` : '' });
    return this.onScanOpen();
  },

  cartStorageKey(deviceId) {
    return 'cart_' + (deviceId || '').trim();
  },

  loadCartFromStorage(deviceId) {
    try {
      return wx.getStorageSync(this.cartStorageKey(deviceId)) || {};
    } catch (e) {
      return {};
    }
  },

  saveCartToStorage(deviceId, cart) {
    try {
      wx.setStorageSync(this.cartStorageKey(deviceId), cart || {});
    } catch (e) { /* ignore */ }
  },

  formatPriceYuan(cents) {
    return ((cents || 0) / 100).toFixed(2);
  },

  categoryMeta(category) {
    const cat = (category && String(category).trim()) || '其他';
    return CATEGORY_META[cat] || { icon: '📦', bg: '#f5f5f5', label: cat };
  },

  enrichProduct(p) {
    const meta = this.categoryMeta(p.category);
    const cat = (p.category && String(p.category).trim()) || '其他';
    return {
      ...p,
      categoryLabel: cat,
      categoryIcon: meta.icon,
      placeholderBg: meta.bg,
      initial: (p.skuName || p.skuId || '?').charAt(0)
    };
  },

  buildProductGroups(products) {
    const map = {};
    (products || []).forEach((p) => {
      const cat = p.categoryLabel || '其他';
      if (!map[cat]) {
        map[cat] = { category: cat, icon: p.categoryIcon || '📦', items: [] };
      }
      map[cat].items.push(p);
    });
    return Object.values(map);
  },

  applyCartView(products, cart) {
    const cartMap = cart || {};
    let count = 0;
    let totalCents = 0;
    const cartItems = [];
    const merged = (products || []).map((p) => {
      const base = this.enrichProduct(p);
      const qty = cartMap[p.skuId] || 0;
      if (qty > 0) {
        count += qty;
        totalCents += p.priceCents * qty;
        cartItems.push({
          skuId: p.skuId,
          skuName: p.skuName,
          priceCents: p.priceCents,
          priceYuan: this.formatPriceYuan(p.priceCents),
          qty
        });
      }
      return { ...base, priceYuan: this.formatPriceYuan(p.priceCents), cartQty: qty };
    });
    return {
      products: merged,
      productGroups: this.buildProductGroups(merged),
      cartItems,
      cartCount: count,
      cartTotalCents: totalCents,
      cartTotalYuan: this.formatPriceYuan(totalCents)
    };
  },

  saveCartEstimate(sessionId) {
    if (!sessionId) return;
    const items = this.data.cartItems || [];
    if (!items.length) return;
    try {
      wx.setStorageSync('cart_estimate_' + sessionId, {
        totalCents: this.data.cartTotalCents || 0,
        totalYuan: this.data.cartTotalYuan || '0.00',
        items,
        deviceId: this.data.deviceId,
        savedAt: Date.now()
      });
    } catch (e) { /* ignore */ }
  },

  productBase(p) {
    return {
      skuId: p.skuId,
      skuName: p.skuName,
      priceCents: p.priceCents,
      quantity: p.quantity,
      imageUrl: p.imageUrl,
      category: p.category,
      description: p.description
    };
  },

  async loadProducts(deviceId) {
    const id = (deviceId || this.data.deviceId || '').trim();
    if (!id || id.length < 3) {
      this.setData({ products: [], productGroups: [], cartItems: [], cartCount: 0, cartTotalYuan: '0.00', cartTotalCents: 0 });
      return;
    }
    this.setData({ productsLoading: true });
    try {
      const list = await api.listDeviceProducts(id);
      const cart = this.loadCartFromStorage(id);
      this.setData({ productsLoading: false, ...this.applyCartView(list, cart) });
    } catch (e) {
      this.setData({ productsLoading: false, products: [], productGroups: [], cartItems: [], cartCount: 0, cartTotalYuan: '0.00', cartTotalCents: 0 });
    }
  },

  onAddToCart(e) {
    const skuId = e.currentTarget.dataset.id;
    this.changeCartQty(skuId, 1);
  },

  onCartDelta(e) {
    const skuId = e.currentTarget.dataset.id;
    const delta = parseInt(e.currentTarget.dataset.delta, 10) || 0;
    this.changeCartQty(skuId, delta);
  },

  changeCartQty(skuId, delta) {
    const product = (this.data.products || []).find((p) => p.skuId === skuId);
    if (!product) return;
    const cart = this.loadCartFromStorage(this.data.deviceId);
    const next = (cart[skuId] || 0) + delta;
    if (next <= 0) {
      delete cart[skuId];
    } else if (next > product.quantity) {
      wx.showToast({ title: '超过库存', icon: 'none' });
      return;
    } else {
      cart[skuId] = next;
    }
    this.saveCartToStorage(this.data.deviceId, cart);
    const bases = (this.data.products || []).map((p) => this.productBase(p));
    this.setData(this.applyCartView(bases, cart));
  },

  clearCart() {
    this.saveCartToStorage(this.data.deviceId, {});
    const bases = (this.data.products || []).map((p) => this.productBase(p));
    this.setData({
      ...this.applyCartView(bases, {}),
      cartSheetOpen: false
    });
  },

  toggleCartSheet() {
    if (this.data.cartCount <= 0) return;
    this.setData({ cartSheetOpen: !this.data.cartSheetOpen });
  },

  onCheckoutOpen() {
    this.setData({ cartSheetOpen: false });
    this.onScanOpen();
  },

  onDeviceInput(e) {
    const deviceId = e.detail.value.trim();
    this.setData({ deviceId, deviceOnline: null, deviceStatusText: '', deviceStatusSub: '', deviceStatusChip: '', deviceStatusClass: '', deviceName: '', products: [] });
    if (deviceId.length >= 3) {
      this.refreshDeviceStatus(deviceId);
      this.loadProducts(deviceId);
    }
  },

  deviceStatusView(status) {
    const online = !!status.online;
    const available = status.available !== false;
    const name = status.deviceName || status.deviceId || '';
    let text = '';
    let sub = '';
    let chip = '';
    let cls = '';
    if (!online) {
      chip = '离线';
      text = `${name} 当前离线`;
      sub = '设备未联网，开门可能失败，请更换柜机或联系运营';
      cls = 'warn';
    } else if (!available) {
      cls = 'busy';
      if (status.busyReason === 'REPLENISHMENT') {
        chip = '补货中';
        text = `${name} 正在补货`;
        sub = '运营人员补货中，请稍后再试或换一台柜机';
      } else if (status.activeSessionState) {
        chip = sessionStateLabel(status.activeSessionState);
        text = `${name} ${sessionStateLabel(status.activeSessionState)}`;
        sub = '其他用户正在使用，请等待完成或稍后再试';
      } else {
        chip = '占用';
        text = `${name} 使用中`;
        sub = '柜机被占用，请稍后再试';
      }
    } else {
      chip = '空闲';
      text = `${name} 在线可购物`;
      sub = '选好商品后点击开门取货，关门后自动结算';
      cls = 'ok';
    }
    return {
      deviceName: status.deviceName || '',
      deviceOnline: online,
      deviceAvailable: available,
      deviceStatusText: text,
      deviceStatusSub: sub,
      deviceStatusChip: chip,
      deviceStatusClass: cls
    };
  },

  async refreshDeviceStatus(deviceId) {
    const id = (deviceId || this.data.deviceId).trim();
    if (!id) return;
    try {
      const status = await api.getDeviceStatus(id);
      this.setData(this.deviceStatusView(status));
    } catch (e) {
      this.setData({
        deviceOnline: null,
        deviceAvailable: true,
        deviceStatusText: '无法获取设备状态',
        deviceStatusSub: '请检查网络或设备编号是否正确',
        deviceStatusChip: '未知',
        deviceStatusClass: 'unknown',
        deviceName: ''
      });
    }
  },

  async onScanOpen() {
    if (this.data.opening || this.data.polling) return;
    const deviceId = this.data.deviceId.trim();
    if (!deviceId) {
      wx.showToast({ title: '请输入设备编号', icon: 'none' });
      return;
    }
    if (!(await this.ensureCanOpenDoor())) return;
    this.setData({ opening: true });
    try {
      const status = await api.getDeviceStatus(deviceId);
      this.setData(this.deviceStatusView(status));
      if (!status.online) {
        this.setData({ opening: false });
        const proceed = await new Promise((resolve) => {
          wx.showModal({
            title: '设备离线',
            content: `${status.deviceName || deviceId} 未联网，继续开门可能失败。建议更换设备或稍后再试。`,
            confirmText: '仍要开门',
            cancelText: '取消',
            success: (res) => resolve(!!res.confirm)
          });
        });
        if (!proceed) return;
        this.setData({ opening: true });
      } else if (!status.available) {
        this.setData({ opening: false });
        const busyHint = status.busyReason === 'REPLENISHMENT'
          ? '该柜机正在补货，请稍后再试或换一台柜机。'
          : (status.activeSessionState
            ? `该柜机当前${sessionStateLabel(status.activeSessionState)}，请稍后再试。`
            : '该柜机正在使用中，请稍后再试。');
        wx.showModal({
          title: '设备暂不可用',
          content: busyHint,
          showCancel: false
        });
        return;
      }
      wx.showLoading({ title: '开门中...' });
      const session = await api.createSession(deviceId);
      wx.hideLoading();
      this.saveCartEstimate(session.sessionId);
      this.setData({ ...this.sessionView(session), opening: false });
      wx.showToast({ title: '指令已下发', icon: 'success' });
      this.startPolling(session.sessionId);
    } catch (e) {
      wx.hideLoading();
      this.setData({ opening: false });
      const msg = formatError(e);
      if (/余额|balance/i.test(msg)) {
        wx.showModal({
          title: '开门失败',
          content: msg,
          confirmText: '去充值',
          cancelText: '关闭',
          success: (res) => {
            if (res.confirm) wx.navigateTo({ url: '/pages/recharge/recharge' });
          }
        });
      } else if (/实名|verified/i.test(msg)) {
        wx.showModal({
          title: '需要实名认证',
          content: msg,
          confirmText: '去认证',
          cancelText: '关闭',
          success: (res) => {
            if (res.confirm) wx.navigateTo({ url: '/pages/verify/verify' });
          }
        });
      } else {
        showError('开门失败', e);
      }
    }
  },

  stopPolling() {
    if (this._pollTimer) {
      clearInterval(this._pollTimer);
      this._pollTimer = null;
    }
    this.setData({ polling: false });
  },

  startPolling(sessionId) {
    this.stopPolling();
    wx.setStorageSync('active_session_id', sessionId);
    this._pollStartedAt = Date.now();
    this.setData({ polling: true, pollError: '', opening: false });
    this._pollFailCount = 0;
    this._pollTimer = setInterval(async () => {
      try {
        const session = await api.getSession(sessionId);
        this._pollFailCount = 0;
        this.setData(this.sessionView(session));
        if (session.state === 'OPENING' || session.state === 'CREATED') {
          if (Date.now() - this._pollStartedAt > 45000) {
            this.handleOpeningTimeout();
            return;
          }
        }
        if (session.state === 'COMPLETED') {
          this.stopPolling();
          await this.resolveSessionEnd(sessionId, session);
        } else if (session.state === 'DISPUTED') {
          this.stopPolling();
          wx.showModal({
            title: '订单待审核',
            content: '识别结果需人工确认，运营审核通过后将自动扣款。您可在「我的 → 申诉进度」查看处理状态。',
            confirmText: '查看进度',
            cancelText: '知道了',
            success: (res) => {
              if (res.confirm) wx.navigateTo({ url: '/pages/dispute-mine/dispute-mine' });
              this.resetSessionIfDone();
            }
          });
        } else if (session.state === 'FAILED' || session.state === 'CANCELLED') {
          this.stopPolling();
          this.loadBalance();
          if (session.state === 'FAILED') {
            const reason = session.failReason || sessionStateHint('FAILED');
            if (/余额|balance|不足/i.test(reason)) {
              this.showBalanceInsufficientModal('当前余额不足，请先充值。');
              return;
            }
          }
          this.resetSessionIfDone();
          wx.showModal({
            title: session.state === 'CANCELLED' ? '开门已取消' : '购物未完成',
            content: session.failReason || sessionStateHint(session.state) || sessionStateLabel(session.state),
            confirmText: '知道了',
            showCancel: false
          });
        }
      } catch (e) {
        this._pollFailCount = (this._pollFailCount || 0) + 1;
        if (this._pollFailCount >= 2) {
          this.setData({ pollError: '连接中断，请检查网络后重试' });
        }
      }
    }, 2000);
  },

  handleOpeningTimeout() {
    this.stopPolling();
    wx.removeStorageSync('active_session_id');
    this.setData({
      sessionId: '',
      state: '',
      stateLabel: '',
      stateHint: '',
      stateTone: 'idle',
      opening: false,
      pollError: ''
    });
    wx.showModal({
      title: '开门超时',
      content: '柜门长时间未响应，请检查设备是否在线，或稍后重试。如仍无法开门，请联系现场运营人员。',
      confirmText: '知道了',
      showCancel: false
    });
  },

  abandonSession() {
    this.stopPolling();
    wx.removeStorageSync('active_session_id');
    this.setData({
      sessionId: '',
      state: '',
      stateLabel: '',
      stateHint: '',
      stateTone: 'idle',
      opening: false,
      pollError: ''
    });
    wx.showToast({ title: '已取消，请重新开门', icon: 'none' });
  },

  resetSessionIfDone() {
    const terminal = ['COMPLETED', 'FAILED', 'CANCELLED', 'DISPUTED'];
    if (this.data.sessionId && terminal.includes(this.data.state)) {
      wx.removeStorageSync('active_session_id');
      this.setData({
        sessionId: '',
        state: '',
        stateLabel: '',
        stateHint: '',
        stateTone: 'idle',
        opening: false,
        pollError: ''
      });
    }
  },

  sessionView(session) {
    const state = session.state;
    return {
      sessionId: session.sessionId,
      state,
      stateLabel: sessionStateLabel(state),
      stateHint: session.failReason || sessionStateHint(state),
      stateTone: sessionStateTone(state),
      failReason: session.failReason || ''
    };
  },

  showBalanceInsufficientModal(content) {
    wx.showModal({
      title: '余额不足',
      content: content || '当前余额不足，请先充值后再购物。',
      confirmText: '去充值',
      cancelText: '关闭',
      success: (res) => {
        this.resetSessionIfDone();
        this.loadBalance();
        if (res.confirm) wx.navigateTo({ url: '/pages/recharge/recharge' });
      }
    });
  },

  readBalanceCents() {
    const yuan = parseFloat(this.data.balance);
    return Number.isFinite(yuan) ? Math.round(yuan * 100) : 0;
  },

  async fetchBalanceCents() {
    try {
      const acc = await api.getAccount();
      const cents = acc.balanceCents || 0;
      this.setData({
        balance: (cents / 100).toFixed(2),
        balanceLow: !acc.operator && cents < MIN_BALANCE_CENTS
      });
      return cents;
    } catch (e) {
      return this.readBalanceCents();
    }
  },

  async resolveSessionEnd(sessionId, session) {
    const balanceCents = await this.fetchBalanceCents();

    let latest = session;
    try {
      latest = await api.getSession(sessionId);
      this.setData(this.sessionView(latest));
    } catch (e) {
      /* use snapshot */
    }

    if (latest.orderId) {
      this.clearCart();
      wx.navigateTo({ url: `/pages/result/result?sessionId=${sessionId}` });
      return;
    }

    try {
      await api.getOrder(sessionId);
      this.clearCart();
      wx.navigateTo({ url: `/pages/result/result?sessionId=${sessionId}` });
      return;
    } catch (e) {
      /* no bill yet */
    }

    const failReason = latest.failReason || '';
    if (latest.state === 'FAILED' || /余额|balance|不足/i.test(failReason)) {
      this.showBalanceInsufficientModal(
        /余额|不足/i.test(failReason) ? '当前余额不足，请先充值。' : failReason
      );
      return;
    }

    if (latest.state === 'DISPUTED') {
      wx.showModal({
        title: '账单审核中',
        content: '已取货，商品识别需人工确认。审核通过后将按实际商品扣款；多扣退还、少扣补收。可在「我的 → 申诉进度」查看。',
        confirmText: '查看进度',
        cancelText: '知道了',
        success: (res) => {
          if (res.confirm) wx.navigateTo({ url: '/pages/dispute-mine/dispute-mine' });
          this.resetSessionIfDone();
        }
      });
      return;
    }

    if (balanceCents < MIN_BALANCE_CENTS) {
      this.showBalanceInsufficientModal('当前余额不足，请先充值后再购物。');
      return;
    }

    let account;
    try {
      account = await api.getAccount();
    } catch (e) {
      account = null;
    }

    const hint = account && account.operator
      ? '未识别到您取走的商品。运营账号用于补货，不会产生消费账单；如需测试购物请使用消费者账号。'
      : '未识别到您取走的商品。如确实取货，请稍后在「我的争议」提交说明，或联系客服处理。';

    wx.showModal({
      title: '未识别到商品',
      content: hint,
      confirmText: '知道了',
      showCancel: false,
      success: () => {
        this.resetSessionIfDone();
        this.loadBalance();
      }
    });
  }
});
