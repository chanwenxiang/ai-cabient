"use strict";
Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
const common_vendor = require("../common/vendor.js");
const utils_merchantApi = require("./merchant-api.js");
async function loadRuntimeDict() {
  if (!utils_merchantApi.getToken()) {
    common_vendor.clearDictOverrides();
    return;
  }
  try {
    const data = await utils_merchantApi.request("/api/v2/dicts/runtime", "GET");
    common_vendor.setDictOverrides(common_vendor.buildOverridesFromRuntime(data), {
      loaded: true
    });
  } catch {
  }
}
exports.loadRuntimeDict = loadRuntimeDict;
