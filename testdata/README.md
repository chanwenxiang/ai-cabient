# 测试素材（图片 / 录像）

| 文件 | 用途 |
|------|------|
| `bottle.jpg` / `soda.jpg` / `cola.png` / `apple.jpg` / `zidane.jpg` / `bus.jpg` | 商品建档、识别映射回归的备用图（模拟器已不再读取本目录：旧 `AICABINET_SIM_VIDEO_FILE` 上传链随 DC-27(b) 批6 删除） |
| `shelf-full.jpg` / `shelf-sparse.jpg` | 满柜 / 空柜对照图 |
| `vending-interior-clear.png` / `vending-interior-frost.png` | 柜内清晰 / 起雾对照图（低置信度场景） |
| `marketside-coconut-water-1l.png` / `oriental-leaf-jasmine-335ml.png` | 真实商品图（SKU 建档演示） |
| `retail-ref.jpg` | 商品参照图 |
| `sample-shopping.mp4` | **有产物契约**：浏览器可解码的演示录像，由 `node scripts/generate-demo-shopping-video.mjs` 生成 |
| `out.mp4` | 临时产物，不对外承诺可播（`check-demo-video-format.mjs` EXEMPT 列表内） |

归类纪律：`testdata/**/*.mp4` 每个文件都必须在 `scripts/check-demo-video-format.mjs` 里显式归类（契约或 EXEMPT），
未归类直接红。新增/删除素材须同批改该门禁，否则 `pnpm check:audit-gates` 失败。

## 识别口径

云端识别引擎（`vision-service/`）已于 2026-10-11 退役，本目录不再提供「上传给云识别」的联调流程：
识别结果只认端侧上报 `POST /internal/v1/vision/edge-results`，端侧对接见
[VISION_QUECTEL_INTEGRATION.md](../docs/VISION_QUECTEL_INTEGRATION.md)；本地跑购物链路用
`.\scripts\verify-local.ps1` 或 `.\scripts\e2e-shopping.ps1`（模拟器出图 + 端侧结果上报）。
