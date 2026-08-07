"use strict";
const common_vendor = require("../common/vendor.js");
const utils_consumerApi = require("./consumer-api.js");
const config_api = require("../config/api.js");
async function pickAndUploadEvidence(current, maxCount = 5) {
  const remain = maxCount - current.length;
  if (remain <= 0) {
    common_vendor.index.showToast({ title: `最多 ${maxCount} 张`, icon: "none" });
    return current;
  }
  const paths = await new Promise((resolve) => {
    common_vendor.index.chooseImage({
      count: remain,
      sizeType: ["compressed"],
      sourceType: ["album", "camera"],
      success: (res) => resolve(res.tempFilePaths || []),
      fail: () => resolve([])
    });
  });
  if (!paths.length) return current;
  const next = [...current];
  for (const path of paths) {
    const placeholder = { localPath: path, uploading: true };
    next.push(placeholder);
    try {
      const uploaded = await utils_consumerApi.consumerApi.uploadDisputeEvidence(path);
      placeholder.fileId = uploaded.fileId;
      placeholder.url = absoluteEvidenceUrl(uploaded.url);
      placeholder.uploading = false;
    } catch (e) {
      placeholder.uploading = false;
      next.pop();
      common_vendor.index.showToast({
        title: e instanceof Error ? e.message : "图片上传失败",
        icon: "none"
      });
    }
  }
  return next;
}
function evidenceFileIds(items) {
  return items.map((i) => i.fileId).filter((id) => typeof id === "number" && id > 0);
}
function absoluteEvidenceUrl(url) {
  if (!url) return "";
  if (url.startsWith("http://") || url.startsWith("https://")) return url;
  const base = config_api.API_BASE_URL.replace(/\/$/, "");
  return url.startsWith("/") ? base + url : `${base}/${url}`;
}
function previewEvidenceSrc(item) {
  return item.localPath || absoluteEvidenceUrl(item.url);
}
const evidenceLocalCache = /* @__PURE__ */ new Map();
function fetchEvidenceLocalPath(url) {
  const abs = absoluteEvidenceUrl(url);
  if (!abs) return Promise.resolve("");
  const cached = evidenceLocalCache.get(abs);
  if (cached) return Promise.resolve(cached);
  const token = utils_consumerApi.getConsumerToken();
  return new Promise((resolve) => {
    common_vendor.index.downloadFile({
      url: abs,
      header: token ? { Authorization: "Bearer " + token } : {},
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300 && res.tempFilePath) {
          evidenceLocalCache.set(abs, res.tempFilePath);
          resolve(res.tempFilePath);
          return;
        }
        resolve(token ? withAccessToken(abs, token) : abs);
      },
      fail: () => resolve(token ? withAccessToken(abs, token) : abs)
    });
  });
}
function withAccessToken(url, token) {
  return `${url}${url.includes("?") ? "&" : "?"}access_token=${encodeURIComponent(token)}`;
}
function removeEvidenceAt(items, index) {
  return items.filter((_, i) => i !== index);
}
exports.evidenceFileIds = evidenceFileIds;
exports.fetchEvidenceLocalPath = fetchEvidenceLocalPath;
exports.pickAndUploadEvidence = pickAndUploadEvidence;
exports.previewEvidenceSrc = previewEvidenceSrc;
exports.removeEvidenceAt = removeEvidenceAt;
