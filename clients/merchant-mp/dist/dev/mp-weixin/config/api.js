"use strict";
const envBase = "http://192.168.0.124".replace(/\/$/, "");
const isH5DevBrowser = typeof window !== "undefined" && typeof navigator !== "undefined" && !/miniProgram|miniprogram/i.test(navigator.userAgent);
const API_BASE_URL = (isH5DevBrowser ? "" : envBase || "http://localhost:8080").replace(
  /\/$/,
  ""
);
exports.API_BASE_URL = API_BASE_URL;
