# 识别入驻 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`SKU_VISION_FULL_BROWSER_UAT.md`](../../../uat/SKU_VISION_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

列表与 API 对齐；Chip/Tab/关键词/状态下拉正确；入驻配置、编辑、批量下架均取消；识别测试弹层关闭。结束视口 **1366×768**。

## 数据对照

| 筛 | UI | API |
|----|-----|-----|
| 默认在售 | 共 6 | `status=ACTIVE` total=6 |
| Chip 草稿 | 共 6 | `enrollment=DRAFT` total=6 |
| 关键词「可乐」 | 共 **1** | `q=可乐` total=1 |
| 无匹配 | 共 **0** | `q=zzz…` total=0 |

样例：100063 可口可乐 330ml · ¥3.50 / ¥1.90 · 草稿 · 未进白名单 · 端侧质量门禁 · 扣款 92% · 检测 50%

## 本轮缺陷

无 FAIL。无需改码。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 列表请求参数名为 **`q`**（非 `keyword`）；路由深链用 `?keyword=` 同步到输入后再以 `q` 查。 |
| 2 | 「识别测试」为 **图标按钮**（`title`/warning 色），无可见文案；自动化须点 `.action-icon-btn.is-warning`。 |
| 3 | Chip「全部」文案恰为「全部」；筛选后重置优先点「重置」或 `goto` 清 query，勿依赖 Chip 文案正则卡死。 |
| 4 | 「推进到映射中 / 转生产」会写库；本轮只验更多菜单可见，不点确认。 |
| 5 | 「预览识别」需上传图；本轮只开弹层后「关闭」。 |
| 6 | 本机演示数据 6 条均在「草稿」；映射中/已测试/生产 Chip 计数为 2/3/4 序号标签，非库存数。 |

## 证据

`vis-01`…`vis-13` · `vis-11-test` · `vis-ux-*` · `vis-99-end`
|
