"use strict";
Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
const common_vendor = require("../common/vendor.js");
const utils_consumerApi = require("./consumer-api.js");
async function loadRuntimeDict() {
  if (!utils_consumerApi.getConsumerToken()) {
    common_vendor.clearDictOverrides();
    return;
  }
  try {
    const data = await utils_consumerApi.request("/api/v2/dicts/runtime", "GET");
    common_vendor.setDictOverrides(common_vendor.buildOverridesFromRuntime(data), {
      loaded: true
    });
  } catch {
  }
}
function resetRuntimeDict() {
  common_vendor.clearDictOverrides();
}
exports.loadRuntimeDict = loadRuntimeDict;
exports.resetRuntimeDict = resetRuntimeDict;
