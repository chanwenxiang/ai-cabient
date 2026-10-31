# R0 环境基线（2026-10-31）

- 执行人：本会话（cwx）。HEAD：`cb904b71`（== origin/dev，工作树干净，peer 在途批次已于同日以 `cb904b71` 落库并推远端，CI run 41169834961 success）。
- 栈形态：模式 B 全栈 Docker（`-p ai-cabinet`，full + win-ports），14 容器全部 Up（约 10 小时）。

## 1. 实测结果

| 项 | 结果 | 证据 |
|----|------|------|
| trade :18080 | ✅ `/v3/api-docs` 200 | 本轮 curl |
| gateway :80（admin 静态 + API） | ✅ `/admin/index.html` 200 | 本轮 curl |
| Postgres :15433（宿主） | ✅ TCP 通 | `/dev/tcp` 探针 |
| jiangyi-gateway :18084 | ✅ TCP 通 | 同上 |
| jiangyi-gateway :18083 | ❌ 未映射 | 模式 B 无此映射，**属正常**，勿当缺陷 |
| 三账号登录 | ✅ 全通 | 超管 `13900000001`（redis captcha → `admin-password-login`，userId 100000001）；商户 `13800138001` 与补货员 `13800138007`（`merchant-password-login`，userId 100000002 / 100000011）。请求字段 `phoneNumber`，响应字段 `token` |
| admin 产物一致性 | ✅ 一致，未重建 | 源码最后提交 `24e2a900`(10-31 10:16) < 产物最后提交 `e6c97106`(10-31 10:24)，其后 4 笔（c74ad729/34d022c8/ff23b74a/cb904b71）均无 admin-vue 变更 |
| 双小程序编译 | ✅ 各 exit 0 | `pnpm build:merchant-mp` / `build:consumer-mp`，appid 显式前置（consumer=`wx6dacefb3671de630`，merchant=`wxf7de3625991a6431`）注入正确；日志 `.tmp/r0-mp-{merchant,consumer}-build.log` |
| 种子/数据在位 | ✅ | `flyway_schema_history` max=V417（与 tip 一致）；`user_info` 14、`merchant` 2、`warehouse` 1、`sku_catalog` 16、`jiangyi_device` 2（注意：与 10-30「5 台柜」说法计数口径不同，指 `jiangyi_device` 表行数，R3 审计时重拉） |

## 2. 🔴 发现并处置的基线缺口：trade 容器 jar 落后 HEAD

- **证据**：容器 `ai-cabinet-trade-service-1` StartedAt=2026-10-30T18:49Z（北京 10-31 02:49），早于 `ff23b74a`(10-31 12:43，含 6 个 Java/契约文件)；活体 `/v3/api-docs` grep `replenishment/tasks/{taskId}/videos` **0 命中** ⇒ CB-078 商户录像端点不在运行栈。
- **处置**：干净树上按标准命令重建（compose build + `up -d --no-deps --force-recreate trade-service`），日志 `.tmp/r0-trade-rebuild.log`。重建后 `serverBootEpoch` 变更 ⇒ 既有 token 全失效属预期。
- ✅ **已闭合（同日 13:15）**：`REBUILD_EXIT=0`，新容器 StartedAt=2026-10-31T05:15Z，`/v3/api-docs` grep 上述端点 **1 命中** ⇒ 运行栈 == HEAD `cb904b71`。R1 无此阻塞。

## 3. README「环境速查」核准记录

| 条目 | 结论 |
|------|------|
| docker compose（trade :18080、Postgres :15433、gateway :80、admin 挂 /admin/） | ✅ 实测吻合 |
| 账号 13900000001 / 13800138001 / 13800138007 | ✅ 三号均能登录（本报告 §1） |
| admin 密码登录走 `admin-password-login`（redis captcha） | ✅；captcha 键 `aicabinet:captcha:<id>`，redis 密码从容器 env 取 |
| admin 改源码后须 `node scripts/build-admin.mjs` + Ctrl+F5 | ✅（本轮未改源码，未触发；产物级实证流程见 PROJECT_KNOWLEDGE §12 10-31 条） |
| mp appid 来自进程环境 `MP_WEIXIN_APPID_*` | ✅；本轮以显式前置 env 构建通过（铁律 49 复验） |
| 支付/资金类只走 dev mock；造数 `AUDIT-` 前缀落 `seeds/` | ⚠️ 本轮未触资金面；R6/R11 开工时按此执行 |

## 4. 需用户配合（不阻塞 R1–R8）

- 微信开发者工具分别打开 `clients/consumer-mp/dist/dev/mp-weixin` 与 `clients/merchant-mp/dist/dev/mp-weixin` 各预览一次（编译已由 §1 证明，预览项留给真工具）。
- 真机项（扫码/开门/支付/推送）按 README 规则单列，集中约时间。

## 5. 结论

**R0 通过**（trade 重建已同日闭合，见 §2）。R1（概览 9 页）可开工；开工 Step 0 照 README 规则 2 重拉清单并记录当日 HEAD。
