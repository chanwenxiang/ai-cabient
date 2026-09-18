# 飞书告警渠道接入 —— 证据（2026-09-18）

## 这一步补的是什么

告警链路 `Prometheus → Alertmanager → feishu-alert-relay → 飞书` 此前**只差一个变量**：
`infra/.env` 里没有 `FEISHU_WEBHOOK_URL`，于是桥按 fail-closed 设计对每次投递返回 **503**。
链路本身、门禁、A/B 漂移、真实栈端到端都已在 `../2026-09-18-monitoring-stack-e2e/` 与
`../2026-09-18-feishu-alert-channel/` 里验过。

**唯一拿不到的东西是飞书 webhook 地址本身** —— 它只能由人在飞书客户端里创建群机器人得到，
没有任何 API 能代取。所以本目录做的是：把"拿到地址之后"的最后一个手工步骤压成一条命令，
**并让这条命令自己证明是否真的接通**，而不是"跑完了、没报错、大概好了"。

## 一条命令

```bash
python scripts/devops/activate-feishu-webhook.py --url "https://open.feishu.cn/open-apis/bot/v2/hook/xxxx"
```

它依次做五件事，任何一步不成立就以非零退出，**绝不报"已对接"**：

| 步 | 动作 | 判据 |
| --- | --- | --- |
| 1 | URL 形状校验 | scheme=https、主机 ∈ 飞书域名、路径前缀 `/open-apis/bot/v2/hook/`、token ≥ 8 位、无空白 |
| 2 | 幂等写入 `infra/.env` | 写后回读比对；同名键去重；保留原 **CRLF**（2595 B → 不再全量改动） |
| 3 | 重建 relay 容器 | `--force-recreate`；重建前把 `FEISHU_*` 从子进程环境摘掉，防旧值盖住 `.env` |
| 4 | 复核**容器内**实际取值 | `docker inspect` 读到的值必须 == 目标值；`/health.configured` 必须为 true |
| 5 | 真实投递自检 | POST 一条合成告警 → 读 `/last.detail` 必须为「已送达」 |

辅助模式：`--check`（只体检）、`--clear`（回到未配置）、`--no-recreate` / `--no-test`（分段调试）。

## 实测结果

| 文件 | 用例 | 期望 | 实测 |
| --- | --- | --- | --- |
| `neg-url-shape-guard.txt` | 4 个非法 URL（http、错路径、非飞书主机、token 截断） | 全部拒绝且 `.env` 字节不变 | 4/4 exit=1；`infra/.env` md5 与基线一致 |
| `neg-real-feishu-invalid-token.txt` | 形状合法但 token 无效 | 必须红，且如实转述飞书业务码 | exit=1；**真的连上了 open.feishu.cn**，拿到 `code=19001 param invalid: incoming webhook access token invalid` |
| `pos-mock-delivered.txt` | 指向按飞书契约的本地替身（回 `code=0`） | 必须绿 | exit=0；「已送达」 |
| `neg-check-drift.txt` | 只写 `.env` 不重建 | `--check` 必须红 | exit=1；「.env 与容器不一致 ⇒ 改了 .env 但没重建容器」 |
| `restore-unconfigured.txt` | `--clear` 后投递 | 必须 503，不是 200 | exit=0；`POST /webhook -> HTTP=503`；`/last` 明说"配置缺失，不是送达成功" |
| `gates-and-drift.txt` | 回归 | 门禁绿、漂移 34/34 | `check-ops-alert-channels` exit=0；`verify-ops-alert-channels-drift.py` 34/34 exit=0 |
| `real-feishu-e2e.txt` | **拿到真实 URL 后**（21:31–21:41 补） | 真机送达 | 见下方「第二轮」 |

`pos-mock-delivered.txt` 那条另做了**独立佐证**（不听桥自己说）：替身 `/last` 里确实收到了 1 条报文，
且形状是飞书契约的 `{"msg_type":"text","content":{"text":...}}`（下划线 `msg_type`，不是钉钉的 `msgtype`）。

## 第二轮（2026-09-18 21:31–21:41）：接入**真实飞书**并端到端实测

用户提供了真实群机器人 webhook（形如 `…/hook/******44f5`，明文只落在被 gitignore 的 `infra/.env`）。
详细记录见 **`real-feishu-e2e.txt`**，结论：

| 验证项 | 结果 |
| --- | --- |
| 接入命令五步 | 5/5 全绿，`activate_exit=0`，桥 `/last.detail` = **已送达** |
| 桥拿到的飞书结果 | `status=200 detail=已送达`（`code==0` 才得出此结论，非"HTTP 200 即成功"） |
| **Alertmanager → 桥 → 真飞书** | ✅ 受控告警（唯一 alertname）经 `172.22.0.16`（= alertmanager 容器）送达 |
| **恢复通知** | ✅ `【告警恢复】…已送达`；延迟 301s 来自 `group_interval=5m`，**不是缺陷** |
| 门禁 / 漂移回归 | `check-ops-alert-channels` exit=0；漂移 **34/34** |
| 凭据安全 | `git check-ignore -v infra/.env` → `.gitignore:36` 命中；明文无第二处 |

🔴 **差分测试的重要发现**：直投飞书三种文案（应用内形状 `[争议SLA提醒]…`、监控栈形状 `【告警触发】…`、
**不含任何关键词的中性文案**）**全部 `code=0`** ⇒ 该机器人**当前未启用任何安全设置**。
- 好处：监控栈与应用内两条链路都可用，不必迁就关键词。
- 风险：**URL 即凭据，泄露即可被人往群里发消息**，建议补安全设置。
- 若补「关键词」：两条链路的文案**没有共用词** ⟹ 关键词会断掉其中一条 ⇒ **建议改用「签名校验」**
  （桥侧 `FEISHU_SIGN_SECRET`、应用内侧 `OPS_ALERT_FEISHU_SIGN_SECRET` 都支持）。

## 这份证据**没有**证明什么（诚实边界）

> ⚠️ 本节的第 1 条已被第二轮**推翻**，原文保留以示对照；第 2、3、4 条仍然成立。

1. ~~**从未成功投递到真实飞书群。**~~ → **第二轮已推翻**：已向真实飞书投递并送达（含 Alertmanager 全链）。
   原文保留理由是：当时手上没有有效 token，最近的一步是 `neg-real-feishu-invalid-token.txt`（证明网络可达 +
   能解析业务码）。**该缺口于 21:35 关闭。**
2. **关键词 / 签名 / IP 白名单三条安全设置未做真机校验。**
   脚本只按飞书文档把 `code=19021 / 19024 / 19022` 映射成提示文案，
   这三条映射**本身**是通过本地替身（`SINK_CODE`）验的，不是被真飞书拒出来的。
   （第二轮新增事实：真机差分测试表明**当前三者都没开**，因此这三条映射仍未获真机验证。）
3. **`--allow-non-feishu` 是联调逃生门**，会同时放行 http 与任意主机。
   生产接入**绝不能**带这个 flag（脚本在不带 flag 时的严格分支保留完整校验）。
4. **`FEISHU_SIGN_SECRET` 注入路径只验到"值进了容器"**，没验"签名能被飞书接受"（同第 2 条）。
5. **应用内告警渠道（运营台 DB 配置）本轮未配置。** 差分测试只证明该机器人**接受**应用内文案形状，
   ≠ 那条链路已接通（它读 `system_config.ops.alert.feishu_webhook`，本轮未写）。
6. **未证明 Prometheus 的 22 条业务规则会真产生告警** —— 端到端是**直接向 Alertmanager 注入**合成告警，
   绕过了 Prometheus 规则评估。
7. **未测长期稳定性**：飞书自定义机器人有频控（约 100 次/分钟），告警风暴下的表现未验。

## 当前终态

`infra/.env:85` 已写入真实 `FEISHU_WEBHOOK_URL`（文件 2741 → 2822 B，换行仍为 CRLF），
relay 容器已重建并**指向真飞书**，`/health.configured=true`。监控栈 4 个容器在跑。
⚠️ 该值**未提交也不会提交**（`infra/.env` 被 gitignore）。若要回到未配置态：`--clear`。
