"use strict";
const common_vendor = require("../../common/vendor.js");
const utils_consumerApi = require("../../utils/consumer-api.js");
const _sfc_main = /* @__PURE__ */ common_vendor.defineComponent({
  __name: "help",
  setup(__props) {
    const supportPhoneDisplay = common_vendor.ref("400-888-0018");
    const supportPhoneDial = common_vendor.ref("4008880018");
    const openIdx = common_vendor.ref(0);
    const faqs = [
      {
        q: "怎么开门购物？",
        a: "扫描柜门二维码（或手动输入柜机编号），完成实名与免密/余额准备后即可开门。取完商品关上门，系统自动识别并扣款。"
      },
      {
        q: "如何扣款？会不会多扣？",
        a: "优先使用微信/支付宝免密；未开通时可使用账户余额兜底。关门后识别取走商品并结算；若有疑问可提交账单申诉申请退款。"
      },
      {
        q: "如何申请退款？",
        a: "进入「我的订单」→ 订单详情。若该柜机开启自助退款，可点「立即退款」；否则请点「申请退款/申诉」，运营核对录像通过后原路或退回余额。"
      },
      {
        q: "余额怎么充值？",
        a: "在「我的」或「账户充值」页选择微信/支付宝充值。正式环境仅展示真实支付；开发构建才可能出现模拟充值。"
      },
      {
        q: "柜机打不开或关不上怎么办？",
        a: "可先重试扫码；仍异常请使用「故障报修」提交柜机编号与问题描述，或拨打客服热线。"
      },
      {
        q: "优惠券怎么用？",
        a: "购物结算时系统会自动选用可用优惠券。具体规则以活动页说明为准。"
      }
    ];
    common_vendor.onShow(async () => {
      try {
        const cfg = await utils_consumerApi.consumerApi.consumerPublicConfig();
        const phone = String((cfg == null ? void 0 : cfg.servicePhone) || (cfg == null ? void 0 : cfg["consumer.service_phone"]) || "").trim();
        if (phone) {
          supportPhoneDisplay.value = phone;
          supportPhoneDial.value = phone.replace(/[^\d+]/g, "");
        }
      } catch {
      }
    });
    function toggle(idx) {
      openIdx.value = openIdx.value === idx ? null : idx;
    }
    function callSupport() {
      common_vendor.index.makePhoneCall({
        phoneNumber: supportPhoneDial.value,
        fail: () => common_vendor.index.showToast({ title: `请拨打 ${supportPhoneDisplay.value}`, icon: "none" })
      });
    }
    function goAnnouncements() {
      common_vendor.index.navigateTo({ url: "/pages/announcements/announcements" });
    }
    function goFeedback() {
      common_vendor.index.navigateTo({ url: "/pages/feedback/feedback" });
    }
    function goReport() {
      common_vendor.index.navigateTo({ url: "/pages/report/report" });
    }
    function goOrders() {
      common_vendor.index.switchTab({ url: "/pages/orders/orders" });
    }
    return (_ctx, _cache) => {
      return {
        a: common_vendor.t(supportPhoneDisplay.value),
        b: common_vendor.o(callSupport),
        c: common_vendor.o(goAnnouncements),
        d: common_vendor.o(goFeedback),
        e: common_vendor.o(goReport),
        f: common_vendor.f(faqs, (item, idx, i0) => {
          return common_vendor.e({
            a: common_vendor.t(item.q),
            b: common_vendor.t(openIdx.value === idx ? "−" : "+"),
            c: openIdx.value === idx
          }, openIdx.value === idx ? {
            d: common_vendor.t(item.a)
          } : {}, {
            e: item.q,
            f: common_vendor.o(($event) => toggle(idx), item.q)
          });
        }),
        g: common_vendor.o(goOrders)
      };
    };
  }
});
const MiniProgramPage = /* @__PURE__ */ common_vendor._export_sfc(_sfc_main, [["__scopeId", "data-v-febba6ee"], ["__file", "C:/Users/cwx/OneDrive/Desktop/demo/ai-cabinet/clients/consumer-mp/src/pages/help/help.vue"]]);
wx.createPage(MiniProgramPage);
