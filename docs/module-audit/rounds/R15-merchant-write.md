# R15 merchant-mp 写面实测 — 轮报告

> 镜头：微信开发者工具 automator（9421）登录态下逐页 reLaunch/switchTab + 截图 + 元素渲染检查。
> 执行：2026-11-03，锚 `33b65060`。登录：用户手动点按（13800138001/MCH-DEFAULT；automator 元素交互协议
> 在 devtools 2.02.2608080 上有兼容 bug——input/tap 回包处理崩（`stopPropagation is not a function`），
> setData/页面级协议可用——已由用户手动完成登录）。

## 战果

- **34/34 页全部 OK**（4 tab + 30 分包；login 页未入清单，其「已登录守卫放行」行为已在 consumer 端
  R14 同构验证）：页面真实渲染、数据真实加载（alerts 580 / orders 435 / settlements 371 / disputes 354
  个元素 = 带真实数据的渲染，API 链路通、本地 API 地址修复生效）。
- 六批样式提交（2026-11-02）后的 35 页在真机环境整批复验通过——与轨 E 逐 diff 静态结论互相印证。
- 资金写链（改价/上架/补货/提现）已由 R12 写面四查 + R17 矩阵 E2E 在服务层覆盖；本轮页面层确认全部
  表单页可达可渲染。

## 未挣

- 页面级真实「点写按钮」（改价提交/盘点提交/提现发起）——automator 元素交互协议兼容问题（同 R14 留痕），
  留待 automator 升级或人工点验。
