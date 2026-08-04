"use strict";
Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
const common_vendor = require("./common/vendor.js");
const utils_consumerApi = require("./utils/consumer-api.js");
if (!Math) {
  "./pages/index/index.js";
  "./pages/orders/orders.js";
  "./pages/mine/mine.js";
  "./pages/verify/verify.js";
  "./pages/login/login.js";
  "./pages/result/result.js";
  "./pages/report/report.js";
  "./pages/recharge/recharge.js";
  "./pages/order-detail/order-detail.js";
  "./pages/coupons/coupons.js";
}
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "App",
  setup(__props) {
    common_vendor.onLaunch(async () => {
      await utils_consumerApi.ensureConsumerAuth();
    });
    return () => {
    };
  }
});
const App = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/App.vue"]]);
function createApp() {
  const app = common_vendor.createSSRApp(App);
  return { app };
}
createApp().app.mount("#app");
exports.createApp = createApp;
