---
name: zhipu-ui-acceptance
description: >-
  Use Zhipu (智谱) vision models to review UI screenshots during acceptance /
  验收 / 冒烟 / 联调. Trigger when the user asks for 智谱识图、验收看图、截图评审,
  or when improving mini-program / admin UI and a second-opinion visual review is needed.
---

# 智谱辅助 UI 验收识图

在 Cursor 内置浏览器验收界面时，用智谱视觉模型对截图做第二意见评审（布局过高、底栏突兀、对比度、间距等）。

## 前置条件

1. 用户已在 [智谱开放平台](https://bigmodel.cn) 创建 API Key
2. 当前 shell 已设置环境变量（**不要写入仓库、不要提交**）：

```powershell
$env:ZHIPU_API_KEY = "你的key"
# 可选：默认 glm-4v-flash（免费）；更强可用 glm-4.5v / glm-4v-plus
$env:ZHIPU_VISION_MODEL = "glm-4v-flash"
```

3. 脚本：`scripts/zhipu-vision-review.mjs`

## 何时使用

满足任一条件即应调用（不要只靠文字猜 UI）：

- 用户明确要求「智谱识图 / 验收看图 / 用智谱看截图」
- 小程序 / H5 / 管理后台样式验收，且已有或可拍截图
- Agent 自己看图后仍不确定布局问题，需要独立视觉模型复核

## 标准流程

```
browser 打开页面 → browser_take_screenshot 保存图
→ node scripts/zhipu-vision-review.mjs <截图路径> [可选补充问题]
→ 结合智谱结论 + 自己的 DOM/截图判断 → 改代码 → 再截图复验
```

### 1. 截图

优先用 Cursor `cursor-ide-browser`：

- `browser_navigate` → `browser_lock` → 操作 → `browser_take_screenshot`
- 记下返回的截图本地路径（或复制到仓库 `.tmp/` 下再传给脚本）

小程序：优先 H5 预览验收（`consumer-mp` / `merchant-mp` 的 `dev:h5`）；无法覆盖的原生能力需说明限制。

### 2. 调用智谱

在项目根目录执行（PowerShell）：

```powershell
node scripts/zhipu-vision-review.mjs "C:\path\to\screenshot.png"
```

带具体验收点：

```powershell
node scripts/zhipu-vision-review.mjs ".tmp\consumer-home.png" "重点看：是否一屏、底栏是否抢眼、扫码钮是否过大"
```

### 3. 如何用结论

- **采纳**：间距过大、需滚动、对比差、白块底栏突兀等可复现问题 → 改代码
- **交叉验证**：智谱说「空白」但 DOM 有内容时，再截一次或量 bounding box（可能是渲染未完成 / 手机框裁切）
- **不要**：把智谱意见当唯一真相；业务文案对错仍以产品与代码为准

### 4. 向用户汇报

简短说明：

1. 看了哪一页、截图路径
2. 智谱要点（3 条内）
3. 你实际改了什么 / 为何不改

## 禁止

- 把 `ZHIPU_API_KEY` 写进代码、文档示例真值、git commit
- 用智谱识图替代内置浏览器真实点击验收
- 未设 Key 时假装已调用成功；应提示用户设置 `$env:ZHIPU_API_KEY`

## 故障排查

| 现象 | 处理 |
|------|------|
| Missing ZHIPU_API_KEY | 请用户在当前终端设置环境变量后重试 |
| HTTP 401 | Key 无效或过期 |
| 图片过大 | 压缩/裁剪到 &lt;8MB |
| 模型报错 | 换 `ZHIPU_VISION_MODEL=glm-4v-flash` 或查阅 bigmodel 控制台额度 |
