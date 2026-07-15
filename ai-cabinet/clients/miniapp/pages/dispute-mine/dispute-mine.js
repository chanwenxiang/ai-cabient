const api = require('../../utils/api');
const {
  showError,
  formatDateTime,
  formatRelativeTime,
  disputeStatusLabel,
  formatLineItems,
  formatLineItem
} = require('../../utils/common');

Page({
  data: {
    tickets: [],
    loading: false,
    refreshing: false,
    openCount: 0
  },

  onShow() {
    if (!api.getToken()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    this.loadTickets(true);
  },

  onPullDownRefresh() {
    this.loadTickets(true, true).finally(() => wx.stopPullDownRefresh());
  },

  onRefresh() {
    this.loadTickets(true, true);
  },

  toggleTicket(e) {
    const id = e.currentTarget.dataset.id;
    const tickets = this.data.tickets.map((t) =>
      t.ticketId === id ? { ...t, expanded: !t.expanded } : t
    );
    this.setData({ tickets });
  },

  goOrders() {
    wx.navigateTo({ url: '/pages/orders/orders' });
  },

  async loadTickets(force, manual) {
    if (!force && (this.data.loading || this.data.refreshing)) return;
    const seq = (this._loadSeq = (this._loadSeq || 0) + 1);
    this.setData({ loading: manual ? false : true, refreshing: !!manual });
    try {
      const raw = await api.listMyDisputes();
      if (seq !== this._loadSeq) return;
      const now = Date.now();
      const tickets = (raw || []).map((t) => {
        const isOpen = t.status === 'OPEN';
        const createdMs = t.createdAt ? new Date(t.createdAt).getTime() : 0;
        const waitHours = createdMs ? (now - createdMs) / 3600000 : 0;
        const resolutionSummary = formatLineItems(t.resolutionItems);
        const suggestedSummary = formatLineItems(t.suggestedItems);
        const mapLines = (lines) => (lines || []).map((line) => ({
          ...line,
          displayText: formatLineItem(line)
        }));
        return {
          ...t,
          expanded: false,
          statusLabel: disputeStatusLabel(t.status),
          statusClass: isOpen ? 'open' : 'resolved',
          createdAtText: formatDateTime(t.createdAt),
          resolvedAtText: formatDateTime(t.resolvedAt),
          waitText: isOpen ? formatRelativeTime(t.createdAt) : '',
          overdue: isOpen && waitHours >= 24,
          resolutionSummary,
          suggestedSummary,
          suggestedItems: mapLines(t.suggestedItems),
          resolutionItems: mapLines(t.resolutionItems),
          hasResolution: !!(t.resolutionItems && t.resolutionItems.length),
          hasSuggested: !!(t.suggestedItems && t.suggestedItems.length)
        };
      });
      this.setData({
        tickets,
        openCount: tickets.filter((t) => t.status === 'OPEN').length
      });
      if (manual) {
        wx.showToast({ title: '已刷新', icon: 'none', duration: 1200 });
      }
    } catch (e) {
      if (seq === this._loadSeq) showError('加载申诉进度失败', e);
    } finally {
      if (seq === this._loadSeq) {
        this.setData({ loading: false, refreshing: false });
      }
    }
  }
});
