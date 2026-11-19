# 微信 / 支付宝「普通链接二维码」校验文件目录

本目录持久挂载到 gateway 容器的 `/usr/share/nginx/html/o`（只读），
由 `nginx.conf` / `nginx-full.conf` 里的静态 location 直出。

## 为什么要挂载

微信公众平台配置该规则时，要求校验文件**放在二维码规则的前缀目录下**可通过 https 200 访问：

- 规则：`https://cabinet.easygovm.com/o/`
- 校验文件：`https://cabinet.easygovm.com/o/<文件名>.txt`

2026-11-19 之前该文件是用 `docker cp` 塞进容器的，容器一 `--force-recreate` 就丢，
校验恒 404（MA-362 实测）。挂载后文件跟着仓库走，重建不丢。

## 怎么用

1. 微信公众平台（小程序 AppID `wx6dacefb3671de630`）→ 开发管理 → 开发设置 →
   「扫普通链接二维码打开小程序」→ 启用 → 添加规则：

   | 字段 | 值 |
   |------|-----|
   | 二维码规则 | `https://cabinet.easygovm.com/o/` |
   | 前缀占用 | 不占用 |
   | 小程序功能页面 | `pages/index/index` |
   | 测试范围 | 先「体验版」，验证通过后改「线上版本」 |
   | 测试链接 | `https://cabinet.easygovm.com/o/<真实柜机号>` |

2. 点「下载校验文件」，得到形如 `AbCdEfGhIj.txt` 的文件，**原样放到本目录**（不要改名）。
3. 重建 gateway 让挂载生效：

   ```bash
   cd infra && docker compose -p ai-cabinet \
     -f docker-compose.full.yml -f docker-compose.win-ports.yml \
     up -d --no-deps --force-recreate gateway
   ```

   生产照常走 `docker-compose.yml` + `.env.production` 的那套发布流程。
4. 自检（应返回 200 + text/plain，而不是 302/404）：

   ```bash
   curl -s -o /dev/null -w '%{http_code} %{content_type}\n' \
     https://cabinet.easygovm.com/o/AbCdEfGhIj.txt
   ```

5. 回公众平台点「校验」，通过后保存规则。

## 支付宝「关联普通链接二维码」

支付宝这条**不叫**「扫普通链接二维码打开小程序」（那是微信的叫法），入口：
`b.alipay.com` → 工作台 → 小程序 → 选小程序 → 左侧「小程序码」→「关联普通链接二维码」tab。

| 字段 | 值 |
|------|-----|
| 协议规则 / 大小写 | `https` / `小写` |
| 二维码地址 | `cabinet.easygovm.com/o/`（**模糊匹配**，结尾带斜杠） |
| 前缀占用规则 | `占用`（自己的域名，防止别的小程序抢注同前缀） |
| 小程序功能页 | `pages/index/index` |
| 生效范围 | `体验版`（线上版未发布时选线上版等于挂在空版本上） |

**校验文件与微信不同种**：支付宝给的是 **32 位 md5 命名的 `.html`**（内容就是那串 hash），
微信给的是 `*.txt`。支付宝允许放「域名根目录或二维码地址的最后一级子目录」，我们二维码地址是
`/o/`，所以下面两个 URL 都必须 200（nginx 两条 location 都放行，根目录那路用 `try_files` 回落
到本挂载目录，所以文件只需放这里一份）：

```
https://cabinet.easygovm.com/o/859bd3a9053ea8be5fbfae876b4a709f.html
https://cabinet.easygovm.com/859bd3a9053ea8be5fbfae876b4a709f.html
```

> ⚠️ 不要为了「少一条 location」改 nginx 正则为 `.*\.html` —— 那会把 `/admin` 之外的
> 任意单段 html 也纳入静态查找，属于无谓的攻击面扩大。判据只认 32 位十六进制。

## 现有文件（2026-11-19 找回）

| 文件 | 内容 | 说明 |
|------|------|------|
| `aHNmrbOhxm.txt` | `cf00a1a789ce06c096d42c89de66d10b`（32 字节） | 微信校验文件；2026-10-21 下载，本地 `C:\Users\cwx\aHNmrbOhxm.txt` 原样拷入 |
| `859bd3a9053ea8be5fbfae876b4a709f.html` | `859bd3a9053ea8be5fbfae876b4a709f`（32 字节） | 支付宝校验文件；2026-11-19 由用户在「关联普通链接二维码」页下载 |

⚠️ 2026-11-19 实测：生产 `https://cabinet.easygovm.com/o/aHNmrbOhxm.txt` 返回 **404**
（146 字节 nginx 页，对照应用 404 页是 371 字节）——文件在生产容器里已随重建丢失。
规则当时仍是「已发布」，但微信会定期回查校验文件，缺文件会被「取消发布」。**需要重新部署本目录到生产。**

## 巡检（一条命令，建议每次部署后 + 每周）

```bash
curl -s -w ' %{http_code} %{size_download}\n' https://cabinet.easygovm.com/o/aHNmrbOhxm.txt
# 期望：cf00a1a789ce06c096d42c89de66d10b 200 32
# 若为 404 且 146 字节 ⇒ 校验文件丢了（容器重建），重新部署本目录并 force-recreate gateway，
# 否则微信回查会导致规则「取消发布」（扫普通码退回 302 → URL Link 中间页）。
```

## 生效后扫普通码的行为

微信扫码 → 识别命中规则 → **直接拉起小程序**，页面 `onLoad` 拿到 `q`（编码后的完整链接），
`packages/shared-uni/src/qrcode.ts#parseLaunchOptions` 从 `q` 解析出 `deviceId`。

支付宝扫码命中「关联普通链接二维码」规则 → 同样直接进小程序，但参数名是 **`qrCode`**，
且**只进 App 启动参数、页面 `onLoad` 拿不到**（需 `uni.getLaunchOptionsSync()` 兜底）。
两者差异由门禁 `check:alipay-mp-wiring` 判据 7/8 钉住（MA-364）。

规则未配置时，支付宝扫码仍走 `/o/{deviceId}` 的 UA 分流 → `alipays://` 承接页（部分机型会拦）。

## 不要做的事

- 不要把校验文件改名、改内容、加 BOM。
- 不要用 `docker cp` 代替本目录（重建即丢，正是之前的缺陷成因）。
