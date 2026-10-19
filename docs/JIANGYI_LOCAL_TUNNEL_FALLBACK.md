# 将邑联调临时入口（反向隧道）——花生壳退役后的备用方案

> 用途：**只在需要秒级反复改代码、断点调试时**把真机流量引到开发机。
> 默认方案不是这篇——默认是「本地开发 → 部署到 `cabinet.easygovm.com` → 真机打正式域名」
> （已有服务器/证书/域名，花生壳本来只是"没有公网入口"的替代品）。
> 探测一律用 `pnpm jiangyi:day1-check`（只读），本文命令不写厂商云、不改 prod env。

## 1. 什么时候切隧道

| 场景 | 选择 |
|------|------|
| 改网关代码 → 看真机反应，一轮就够 | 正式域名 + 一次 deploy（默认） |
| 要在本地 IDE 打断点、逐请求看固件行为 | 隧道（本文） |
| 只想验协议本身（无需真机） | 都不用：`JiangyiDeviceSimulator` + `mvn -o -B verify -pl services/jiangyi-gateway` |

🔴 收工前**必须切回正式域名**：把柜子钉在隧道域名上过夜 = 隧道进程一死，那台柜子当天离线
（自愈只靠下次开机重拉 `domainConfig`，`getConfigStatusByIdentifier` 恒回 1 就是为此）。

## 2. 一次性准备（服务器侧，5 分钟）

前提：`easygovm.com` 已在本机 DNS 控制台管理（现网 `cabinet` A 记录 → 120.25.246.179 已在用）。

1. **加一条 A 记录**：`dev-cabinet` → `120.25.246.179`。
2. **反向隧道**（开发机 → 服务器，把本地网关 18084 送到服务器回环口）：

   ```bash
   ssh -N -o ServerAliveInterval=30 -o ExitOnForwardFailure=yes \
       -R 127.0.0.1:18084:127.0.0.1:18084 \
       -i C:/Users/cwx/.ssh/aicabinet-prod.pem root@120.25.246.179
   ```

   `-R` 绑在服务器**回环**上：公网只能经下面的 vhost 进来，不暴露裸端口。
3. **证书**（与 `cabinet` 同法，HTTP-01 走 80）：

   ```bash
   certbot --nginx -d dev-cabinet.easygovm.com --non-interactive --agree-tos -m ops@easygovm.com
   ```

4. **vhost**：照抄现网 `cabinet` 那份，只有两条 location 是设备面必需的
   （仓库内三份 `infra/gateway/nginx*.conf` 已有等价配置，这里是**入口 vhost**，仓库外文件）：

   ```nginx
   location /jiangyi/ {
       proxy_pass http://127.0.0.1:18084;
       proxy_set_header Host $host;
       proxy_set_header X-Forwarded-Proto $scheme;
       proxy_read_timeout 30s;
   }
   location /websocket/ {
       proxy_pass http://127.0.0.1:18084;
       proxy_http_version 1.1;
       proxy_set_header Upgrade $http_upgrade;
       proxy_set_header Connection upgrade;
       proxy_read_timeout 3600s;   # 设备 WS 长连接，短了会被切
   }
   ```

## 3. 切换（开发机侧，一行 env）

```bash
# 本地网关
JIANGYI_PUBLIC_BASE_URL=https://dev-cabinet.easygovm.com
```

设备**已经在打我方任一域名**时，这一步不需要厂商配合：`DomainConfigController` 会把
`getConfigDomainByIdentifier` 的 `data` 改成上面这个值，设备下次开机拉取即被钉过来。
（对照：`setDomain` 是**写厂商云**的共享状态操作，还要重下发 + 整机重启，能绕开就别用它。）

## 4. 验证与回切

```bash
pnpm jiangyi:day1-check --base=https://dev-cabinet.easygovm.com     # 三条路由门须全 401
# 带合法设备 token 再验自钉信封（token 走环境变量，勿写进命令行历史）
JIANGYI_DAY1_TOKEN=<设备JWT> pnpm jiangyi:day1-check --base=https://dev-cabinet.easygovm.com
```

回切 = 把开发机那行 env 改回正式域名并重启网关，然后**再跑一次上面第一条**确认
`getConfigDomainByIdentifier` 的 `data` host 已是 `cabinet.easygovm.com`，最后让设备重开一次机。

🔴 两条红线：
- 服务器 `infra/.env.production` 的 `JIANGYI_PUBLIC_BASE_URL` 永远保持正式域名，**不得**被本地值同步上去
  （本地实例与 prod 各用各的 `--env-file`，天然隔离：`scripts/deploy-prod.sh:111`）。
- 隧道期间 `docker logs` 里出现的 404 台账只反映**这台被钉过来的柜子**，别当成现网全量数据写进汇报。

## 5. 为什么不用别的

- **花生壳/cpolar 等穿透服务**：随机域名 + 连接数限制 + 对方服务一挂就整机失联，且证书不在自己手里。
- **局域网 `http://192.168.0.x:18084`**：`normalize()` 允许 `http://`
  （[DomainConfigController.java:121](../services/jiangyi-gateway/src/main/java/com/aicabinet/jiangyi/api/DomainConfigController.java)），
  但两道未验证：Android 9+ 默认禁 cleartext、WS 要退化成 `ws://`；**且必须先确认这台柜子走 WiFi 还是物联卡蜂窝**——
  走物联卡则 LAN 直连根本不成立。
- **直接改设备侧 hosts / DNS 劫持 lingshouapi**：拿不到对方域名的合法证书，且会波及该机全部云功能，只作应急试验。
