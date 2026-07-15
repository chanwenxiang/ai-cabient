# 运营后台（Admin）

Vue 无关的 vanilla JS 运营后台，使用 **Vite** 构建，产物输出到 `services/trade-service/src/main/resources/static/admin/`。

## 要求

- Node.js **16+**（推荐 18+）

## 开发

```powershell
cd clients/admin
npm install
npm run dev
```

浏览器打开 http://localhost:5173 ，API 通过 Vite 代理到 `http://localhost:8080`（可在 `.env` 中改 `VITE_DEV_PROXY`）。

## 生产构建

```powershell
cd clients/admin
npm run build
```

或在 **trade-service 根目录** 通过 Maven 自动构建（`generate-resources` 阶段）：

```powershell
cd ai-cabinet
mvn -pl services/trade-service package
```

跳过运营后台构建（加快纯后端迭代）：

```powershell
mvn -pl services/trade-service package -Dskip.admin.build=true
# 或
mvn -pl services/trade-service package -Pskip-admin-ui
```

构建后重启 trade-service，访问：

http://localhost:8080/admin/index.html

产物带 content-hash 文件名，便于浏览器缓存刷新。

## 环境变量

| 变量 | 说明 |
|------|------|
| `VITE_DEV_PROXY` | 本地 dev 代理目标，默认 `http://localhost:8080` |
| `VITE_API_BASE` | 生产 API 根地址；留空则使用 `window.location.origin` |

## 源码结构

```
clients/admin/
  index.html          # 页面骨架
  src/
    main.js           # 入口
    admin-common.js   # 工具、XSS、loading、toast
    permissions.js    # RBAC
    app.js            # 核心业务页
    ops-modules.js    # SLA/OTA/风控/对账/补货/RBAC
    styles.css
```

修改 UI 后请在本目录执行 `npm run build`，再重启或刷新 Spring Boot 静态资源。
