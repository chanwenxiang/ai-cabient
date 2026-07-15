/** 跨模块共享运行时（app.js 注入，ops-modules.js 消费） */
export const adminRuntime = {
  api: null,
  getCurrentPage: () => 'dashboard',
  fmtTime: (iso) => iso || '-',
  fmtMoney: (cents) => String(cents),
  closeModal: () => {},
  opsLoaders: {}
};
