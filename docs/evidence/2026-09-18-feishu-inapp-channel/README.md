# 证据：应用内飞书告警渠道（2026-09-18）

运营台「系统 → 告警规则 → 告警渠道」这条**应用内**链路（`OpsAlertDispatcher`），
与监控栈链路（Prometheus → Alertmanager → `feishu-relay.py` → 飞书）是**两条独立的路**。
本目录记录前者的落地过程。

## 一句话结论

代码早就在，但**从未部署**；重建 trade-service 后渠道**真的投出去了**，并证明判据有分辨力。
运营台前端随后也**已重建并发布** ⇒ 现在在页面上「系统 → 告警规则 → 告警渠道」里
**能读、能改、能试发**（URL 即配置项，改完立即生效——`getValue` 直读 DB、不经缓存）。

## 文件

| 文件 | 内容 | 关键结论 |
|---|---|---|
| `ab-alert-test.txt` | A/B 对照 | 指拒绝替身 → `delivered=False` + `code=19024 Key Words Not Found`；指真飞书 → `delivered=True`；终态已复原 |
| `deploy-artifact-ab.txt` | 部署前后（产物级）+ 诚实边界 | 旧 jar `feishu=0` → 新 jar `feishu=4`；含"这份证据没证明什么" |
| `deploy-admin-rebuild.txt` | 前端重建与发布取证 | 产物/镜像/HTTP 三层 A/B：`feishu_webhook` 与「测试发送」均 `0 → 1`；含一例 vite 卡死排查 |

## 判据是否真的是「判据」（本轮的自我检查）

- **不是橡皮图章**：A 组把渠道指向一个按飞书契约返回 `code=19024` 的替身，
  `delivered` 必须变 `False` 且把业务码带出来。实测通过
  （`detail = code=19024 Key Words Not Found`）。
- **不是恒红**：B 组指回真实 webhook，必须 `delivered=True` 且 `detail` 为空。实测通过。
- **基线不是空的假象**：写入 URL 之前先调一次 `alert-test`，返回
  `{"code":0,"message":"ok","data":[]}` —— 未配置渠道时是**空列表**，
  说明它不会凭空造出"成功"。
- **读回真值**：`PUT` 后用 `GET` 回读，值确为写入值。

## 复现步骤

A/B 已固化为**入库脚本** `scripts/devops/verify-feishu-inapp-channel-ab.py`，
证据 `ab-alert-test.txt` 就是它的原始输出（可复跑，退出码即判据）：

```bash
# 1) 起「按飞书契约返回 code=19024」的替身（与 trade-service 同网络）
docker run -d --name tmp-feishu-sink --network ai-cabinet_default \
  -v "$PWD/scripts/devops/feishu-sink-mock.py:/app/sink.py:ro" \
  -e SINK_PORT=18099 -e SINK_CODE=19024 \
  python:3.12-slim python /app/sink.py

# 2) 取 admin token。dev 下 captcha-enabled=true ⇒ 必须过图形验证码：
#    GET  /api/v2/auth/captcha                        -> {captchaId, imageBase64}
#    POST /api/v2/auth/admin-password-login           -> token 在 **data.token**
#    （13900000001 / 123456；验证码是 PNG，需要读图识码）

# 3) 跑 A/B（A 组失败→B 组成功→终态复原，任一不成立即 exit 1）
AI_CABINET_OPS_TOKEN=<token> python scripts/devops/verify-feishu-inapp-channel-ab.py
```

脚本只做三件事：`PUT` 渠道值 → `POST .../alert-test` → 复核复原；
A 组改过的值**必定**被 B 组覆盖回真实 webhook，并单独断言复原结果。

## 待办

1. ✅ **重建运营台前端**（本轮已完成，见 `deploy-admin-rebuild.txt`）。
   ⚠️ 教训：`node scripts/build-admin.mjs` 直接写仓库内 `static/admin`（OneDrive 同步目录）会**卡死**
   ——实测 14 分钟零推进、CPU 冻结、`assets/` 被清空成 0 文件。对策：**把输出改到非 OneDrive 目录**
   （`node node_modules/vite/bin/vite.js build --outDir <临时目录> --emptyOutDir`，12.58 秒完成），
   再 `robocopy /MIR` 回 `static/admin`。
2. **凭据位置**：同一个 webhook 现在存在**两处** —— `infra/.env`（监控栈 relay 用，已 gitignore）
   与 dev 数据库 `system_config.ops.alert.feishu_webhook`（明文）。上生产时两处都要换。
3. **偶发失败无重试**：`OpsAlertDispatcher.post()` 捕获异常仅记 warn、不重试 ⇒ 会丢单条告警。
4. **业务告警 `send()` 未端到端**：只验了 `alert-test`；两者共用 `postJson`+`deliveryError`，调用方不同。
