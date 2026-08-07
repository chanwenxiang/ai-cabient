"use strict";
const common_vendor = require("../common/vendor.js");
function setAlertsTabBadge(count) {
  try {
    if (count > 0) {
      common_vendor.index.setTabBarBadge({
        index: 2,
        text: count > 99 ? "99+" : String(count)
      });
    } else {
      common_vendor.index.removeTabBarBadge({ index: 2 });
    }
  } catch {
  }
}
exports.setAlertsTabBadge = setAlertsTabBadge;
