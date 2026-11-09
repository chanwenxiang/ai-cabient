# B 类浏览器 UAT 回补（2026-11-09，admin 部分）

范围：仅 `http://127.0.0.1:18080/admin/` 后台浏览器验收，覆盖营销/仓储/审批/系统 4 组。
工具：真实 Chromium（Playwright，1440×900，headless）驱动真实页面；登录态由 `/api/v2/auth/admin-password-login` 取得 JWT 后注入 `sessionStorage.admin_token`（与 `clients/admin-vue/src/api/auth-storage.ts` 一致）。In-app 浏览器截图surface 不可见（visibilityState=hidden），故截图改由 Playwright 落盘；同页 in-app 快照（/coupons）结构与内容一致。

## 本轮结果

| 模块 | PASS/FAIL | 截图 | 备注 |
|---|---|---|---|
| 营销 / 会员 / 卡券 / 积分 | PASS | marketing/01-coupons-list.png、02-member-levels.png、03-points-redeem.png、04-coupons-empty.png | 优惠券 6 条、会员等级 4 条、积分兑换 6 条均正常渲染；关键词无匹配查询得空态「暂无优惠券」（04） |
| 仓储 / 补货 / 盘点 | PASS | warehouse/01-replenishment-list.png、02-stock-stocktake.png、03-monthly-close.png、04-stock-monthly-tab.png | 补货调度 20 条路线，履约记录 tab 空态「暂无履约记录」；库存与盘点 tab 齐全（盘点单 1 条 PD20261028-001）；月结单台账 3 条；「月结」tab 10 行 |
| 审批 / 通知 / 工单 / 报修 | PASS | approval/01-approvals.png、02-notifications.png、03-repair-tickets.png | 审批流配置 6 条；消息记录 20 条；维修工单 0 条，空态「暂无维修工单」 |
| 系统 / 角色 / 字典 / 组织 | PASS | system/01-roles-list.png、02-role-edit-dialog.png、03-dicts-list.png、04-departments.png、05-org-sites.png | 角色 10 条；点「编辑」弹「编辑角色」弹窗，截图后点「取消」弹窗关闭、行数不变（未提交，不写库）；字典类型 111 条；部门 5 条；组织与点位空态「暂无数据」 |

## 已有截图（前一轮已采，本轮未重跑）

- overview/：00-login-form.png、00-login-filled.png、01-dashboard-after-login.png、02-dashboard-refreshed.png、03-big-screen.png、04-analytics.png
- trade/：01-orders-list.png、02-orders-export.png、03-reconciliation.png、04-sales-reports.png
- devices/：01-devices-list.png、02-device-detail-top.png、03-device-jiangyi-card.png、04-vision-mappings.png、07-menu-probe.png、08-jiangyi-products-direct.png、step-error.png

## 缺陷 / 观察（均未阻断）

1. 优惠券列表：1440px 下「说明」列被右侧固定操作列遮挡（文本截断），页面自身也提示宽度 ≥1280 仍显拥挤 —— 列宽/固定列需微调。
2. 库存与盘点：盘点单 `PD20261028-001`「模式」列显示「未知」，疑为模式枚举未映射（展示问题，非报错）。

## 复验：「将邑商品」页权限修复后可用（2026-11-09 15:5x）

首轮 devices/08-jiangyi-products-direct.png 显示「无权访问」（菜单挂了 F 类型权限码 `ops:sku:edit`）；
peer 修复为 `ops:sku:list`（`5f01deb8`）+ 产物重打（`2f3f4b24`）并部署后，用真实 Chromium 复验：
URL 停留在 `/admin/jiangyi-products`、无「无权访问」、标题/条码表单/建档表单/8 张采集提示齐全，
侧栏「设备商品 → 将邑商品」可见。截图：`devices/09-jiangyi-products-after-perm-fix.png`。

## 边界

本轮仅覆盖 admin 浏览器部分；不含小程序三端（消费者/商户/补货员）与四闸门（format/lint/单测/构建）验收。全部页面无 console error、无失败请求。
