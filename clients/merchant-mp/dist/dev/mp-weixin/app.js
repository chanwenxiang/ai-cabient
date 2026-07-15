"use strict";
Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
const common_vendor = require("./common/vendor.js");
if (!Math) {
  "./pages/login/login.js";
  "./pages/home/home.js";
  "./pages/devices/devices.js";
  "./pages/device-detail/device-detail.js";
  "./pages/pricing/pricing.js";
  "./pages/replenishment/replenishment.js";
  "./pages/business/business.js";
  "./pages/alerts/alerts.js";
  "./pages/mine/mine.js";
  "./pages/settlements/settlements.js";
  "./pages/disputes/disputes.js";
}
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "App",
  setup(__props) {
    common_vendor.onLaunch(() => {
      if (!common_vendor.index.getStorageSync("merchant_token"))
        return;
    });
    return () => {
    };
  }
});
const App = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/App.vue"]]);
function createApp() {
  const app = common_vendor.createSSRApp(App);
  return { app };
}
createApp().app.mount("#app");
exports.createApp = createApp;
