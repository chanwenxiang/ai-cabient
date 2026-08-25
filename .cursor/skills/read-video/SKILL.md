---
name: read-video
description: >-
  Read and analyze local video / screen recordings (mp4/mov/webm) for UI bugs,
  page transitions, and flow walkthroughs. Use when the user attaches or @-mentions
  a video file, asks to watch/analyze a recording, or describes issues only visible
  in motion (page slide, flicker, jump). Prefer media-context MCP when enabled;
  otherwise extract frames with the bundled OpenCV script and Read the images.
---

# Read Video（本地录屏 / UI 视频）

Cursor 的 `Read` 不能直接打开 mp4。本 skill 把视频变成可看的帧序列，再继续改代码。

## 优先顺序

1. **MCP `media-context`**（已写入 `~/.cursor/mcp.json`）  
   - 工具：`analyze_media`、`check_media_deps`  
   - UI 录屏推荐：`mode=filmstrip` 或 `mode=frames`，`detail=high`，`format=png`  
   - 需用户启用/重载 MCP 后才可用

2. **Skill `video-understand`**（`~/.cursor/skills/video-understand`）  
   - 有 `GEMINI_API_KEY` / `OPENROUTER_API_KEY` 时走完整视频理解  
   - 否则可走 ffmpeg 抽帧（需本机 ffmpeg）

3. **本 skill 本地抽帧（无 API、立即可用）**

```powershell
python "$HOME/.cursor/skills/read-video/scripts/extract-frames.py" `
  -i "<video-path>" `
  -o "<out-dir>" `
  --max-frames 12 `
  --scene
```

然后对输出的 `frame_*.jpg` 依次 `Read`（图片），按时间顺序描述切换/动效/文案。

## 分析要点（UI 录屏）

- 页面是否 **整页滑入/滑出**（navigateTo 动画）还是 **Tab 瞬间切换**（switchTab 无动画）
- 顶栏、返回键、底栏 Tab 是否在切换中闪动或错位
- 用户指出的问题帧：前后对比说清「从哪到哪」

## 禁止

- 不要声称「已看完视频」却只读了路径、没读任何帧图
- 不要用 Playwright / 第三方浏览器 MCP 代替看录屏
