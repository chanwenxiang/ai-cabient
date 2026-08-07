"use strict";
function eventInputValue(e) {
  var _a, _b;
  const ev = e;
  const raw = ((_a = ev == null ? void 0 : ev.detail) == null ? void 0 : _a.value) ?? ((_b = ev == null ? void 0 : ev.target) == null ? void 0 : _b.value) ?? "";
  return String(raw ?? "");
}
function readDomFieldValue(kind = "input") {
  return "";
}
function readDomPassword() {
  return readDomFieldValue("password");
}
function readDomTextarea() {
  return readDomFieldValue("textarea");
}
exports.eventInputValue = eventInputValue;
exports.readDomFieldValue = readDomFieldValue;
exports.readDomPassword = readDomPassword;
exports.readDomTextarea = readDomTextarea;
