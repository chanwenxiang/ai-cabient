# 字典管理 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`DICTS_FULL_BROWSER_UAT.md`](../../../uat/DICTS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0 · SKIP 1（导入）**

左侧字典类型约 **89**；搜索无命中「暂无字典类型」。选中「广告素材类型」右侧共 **3**（图片/视频/H5）。类型新增/编辑（编码 disabled）/删除确认→**取消**。字典项新增（含保存并继续）/编辑（值 disabled）/删除→**取消**。导出与下载模板 CSV 成功；导入按钮可见、OS 文件框未测。窄视口 900 无横滚；结束 **1366×768 DPR=1**。未提交任何写操作。

## 数据对照

| 面 | UI |
|----|-----|
| 类型数 | ~89 |
| 广告素材类型 | 图片 IMAGE · 视频 VIDEO · H5 · 共 3 |
| 深链 | `?type=ad_asset_type` 可选中 |

## 本轮缺陷

无 FAIL / FINDING。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 左右分栏可拖宽（`admin_dict_types_width`）；窄屏仍无页面级横滚。 |
| 2 | 删除类型文案明示「将同时删除其下 N 个字典项」——本轮取消未真删。 |
| 3 | 导入走 CrudTable OS 文件选择器 → SKIP（与它页一致）。 |
| 4 | 页头「刷新」联动类型 + 字典项 + 运行时字典；CrudTable 内建刷新关闭。 |

## 证据

`dt-01`…`dt-12` · `dt-ux-*` · `dt-99-end`
|
