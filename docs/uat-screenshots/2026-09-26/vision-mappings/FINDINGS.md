# 识别映射 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`VISION_MAPPINGS_FULL_BROWSER_UAT.md`](../../../uat/VISION_MAPPINGS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

YOLO 列表与 API 对齐；关键词 `q` 诚实；深链 `keyword` 回显；新增/编辑/删除均取消。阿里云区空态诚实。结束视口 **1366×768**。

## 数据对照

| 筛 | UI | API |
|----|-----|-----|
| 默认 | 共 4 | `/yolo` total=4 |
| 关键词「可乐」 | 共 **2** | `q=可乐` total=2（bottle、cup → 可口可乐） |
| 无匹配 | 共 **0** | total=0 |
| 阿里云 | 空态 | `aliyun=[]` |

样例：`bottle` → 可口可乐 330ml · 草稿 · 未进白名单 · 端侧质量门禁 · 50%

## 本轮缺陷

无 FAIL。无需改码。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 列表请求参数 **`q`**；路由深链用 `?keyword=`，页面同步到输入后再以 `q` 查询。 |
| 2 | 行编辑/删除为 **图标按钮**（primary / danger）。 |
| 3 | 页内两个「新增映射」：表头 YOLO、阿里云卡各一；自动化须限定 `.aliyun-card`。 |
| 4 | 阿里云编辑/删除本机无数据 → **SKIP**（非 FAIL）；新增弹层已覆盖取消。 |
| 5 | 页下半为阿里云整卡；窄视口抽检无整页横滚（表区可内滚属预期）。 |

## 证据

`map-01`…`map-13` · `map-ux-*` · `map-99-end`
|
