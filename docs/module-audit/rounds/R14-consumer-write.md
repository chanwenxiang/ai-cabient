# R14 consumer-mp 写面实测 — 轮报告

> 镜头：微信开发者工具 automator（9420）真机环境逐页 reLaunch/switchTab + 截图 + 元素渲染检查。
> 执行：2026-11-03 上午，锚 `33b65060`。

## 过程中的环境修复（两处，均为本地配置非业务代码）

1. **ERR_CONNECTION_TIMED_OUT 根因**：`.env.development` 的 `VITE_API_BASE_URL=http://192.168.0.199`
   是旧局域网 IP（本机现 192.168.0.26，.199 不可达；localhost:80 正常 200）→ 两端 .env.development
   改 `http://localhost`（本地 gitignore 文件）+ dev 模式重编译（`build:mp-weixin` 是 release 模式读
   .env.production，dev 目录必须走 dev 脚本——过程中一次 kill watch 写坏 static 目录，完整重跑修复）。
2. automator 元素交互（input/tap 的元素协议）在 devtools 2.02.2608080 上有兼容问题——但页面级协议
   （reLaunch/switchTab/currentPage/data/$$/screenshot）全部可用，R14 遍历未受影响。

## 战果

- **23/23 页全部遍历完成**（7 主包 + 16 分包），22 页 OK：页面真实渲染、数据真实加载
  （orders 127 / mine 183 / messages 181 个元素=带数据的渲染，证明 API 链路通）。
- 唯一 ⚠️ `pages/login/login`：navigate 后自动跳回 index——**已有微信会话时登录页守卫按设计放行**，
  非缺陷（与 R9「wx 会话生效」结论一致）。
- 资金写链（充值/退款/补扣）已由 R17 矩阵 E2E 在服务层全覆盖；本轮页面层确认表单页可达可渲染。

### 未挣

- 页面级真实「点写按钮」（领券/选券/提交）——automator 元素交互协议兼容问题（见上），且资金写已有
  E2E 腿；留待 automator 升级或人工点验。
