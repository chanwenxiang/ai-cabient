# 工程踩坑总册（现象 → 根因 → 必须怎么做）

> 由规则 `record-lessons-learned` 维护。能归入领域 `.mdc` 的优先写领域表；此处收跨模块或尚未单独立规的条目。  
> 后台布局专表见：`.cursor/rules/admin-layout-anti-jitter.mdc`。

| # | 领域 | 现象 | 根因 | 必须怎么做 | 门禁/文件 |
|---|------|------|------|------------|-----------|
| 1 | admin 布局 | 操作列横滑跑走 | 为防抖关掉右侧 sticky | 右侧 `fixed-column--right` 必须 sticky；左侧可 static | `pnpm check:admin-anti-jitter` |
| 2 | admin 抽屉 | 拉窄松手自动变宽 | 先清 inline width，EP 闪回旧 `:size` | 松手先钉 `finalW` + `nextTick`；禁 pointerup 清空 width | 同上 |
| 3 | admin 抽屉 | 点纵向滚动条内容缩一下再弹；按钮闪折行 | Win overlay→经典条吃宽 + `overflow-y:auto` + `flex-wrap` | body 用 `overflow-y:scroll` + gutter；操作行 `nowrap` | 同上 |
| 4 | admin 表格 | 长文案悬停盖邻列 | `show-overflow-tooltip` 浮层挂表内，横滚/sticky 错位 | **全后台禁** `show-overflow-tooltip`；`installTableCellNativeTitle` + 可选手写 `:title` / `.cell-ellipsis`；门禁扫全部 `.vue` | 同上 |
| 5 | admin 审单 | 数量框挡住「删除」 | 数量列 132px < input-number ~150px | `.manual-line` 数量列 ≥150px 且 input `max-width:100%` | 同上 |
| 6 | 业务空态 | 识别存疑「订单/关联订单」显示暂无被当成丢数据 | 争议未落账前会话无 `order_id`，属阶段空 | 文案用「待落账」+ title 说明；结案后才有订单号 | Exception/Dispute 列表视图 |
| 7 | ops RBAC | 从超管角色去掉按钮权限后，UI/API 仍能操作 | `PermissionService`：持有 `ops:admin` 时运营域权限一律放行 | 测按钮权限必须用不含 `ops:admin` 的角色；超管场景只测「有权限」路径 | `PermissionService.hasPermission` |

## 追加模板

```md
| N | 领域 | 一句话现象 | 一句话根因（有证据） | 禁止/必须… | 脚本或路径 |
```
