"use strict";
const common_vendor = require("../common/vendor.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "empty-state",
  props: {
    title: {},
    hint: { default: "" },
    icon: { default: "" },
    compact: { type: Boolean, default: false }
  },
  setup(__props) {
    return (_ctx, _cache) => {
      return common_vendor.e({
        a: _ctx.icon
      }, _ctx.icon ? {
        b: common_vendor.t(_ctx.icon)
      } : {}, {
        c: common_vendor.t(_ctx.title),
        d: _ctx.hint
      }, _ctx.hint ? {
        e: common_vendor.t(_ctx.hint)
      } : {}, {
        f: _ctx.$slots.default
      }, _ctx.$slots.default ? {} : {}, {
        g: _ctx.compact ? 1 : ""
      });
    };
  }
});
const Component = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-1644c0f1"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/merchant-mp/src/components/empty-state.vue"]]);
wx.createComponent(Component);
