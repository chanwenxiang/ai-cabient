#!/usr/bin/env python3
"""check-ops-alert-channels 门禁的「判据有效性」A/B 验证（2026-09-18）。

为什么必须有这个脚本
--------------------
「实跑通过 ≠ 判据有效」。只会打 OK 的门禁等于一条 `exit 0`；必须**真注入漂移**证明它
每条分支都会红，同时用 `expect_red=False` 的反向用例证明它**不会乱红**（假红与假绿同害）。
沿用同目录先例（`verify-edge-threading-gate-drift.py`、`verify-miniapp-privacy-drift.py`、
`verify-metric-names-drift.py`）的形态：逐例注入 → 跑门禁 → 断言退出码与报错文案 →
还原 → 复跑必须回绿。任何情况下都会还原文件。

覆盖的分支（与 check-ops-alert-channels.mjs 的 fail()/problems 一一对应）
-----------------------------------------------------------------------
  R1  1. FEISHU 渠道名被换成别的渠道                → 「未声明 FEISHU 渠道」
      2. 整条删掉 FEISHU 渠道声明                    → 「CHANNELS 结构可能已变」（计数守卫先触发，
                                                       所以 R1 的「未声明」文案就靠第 1 例覆盖）
      3. FEISHU 渠道改绑企微的配置常量               → 「绑定的配置常量是」
  R2  4. feishuPayload 改用钉钉的 `msgtype`          → 「未产出 msg_type」
  R3  5. payloadFor 删掉 FEISHU 分支（落 default）   → 「未把 "FEISHU" 路由到 feishuPayload」
  R4  6. deliveryError 的 FEISHU 分支改看 `errcode`  → 「FEISHU 分支未看 code」
      7. postJson 退回 `toBodilessEntity()`          → 「toBodilessEntity() 丢弃响应体」
  R5  8. 去掉飞书 webhook 的 upsertIfAbsent seed     → 「未在 upsertIfAbsent 里 seed」
  R6  9. 运营台 BUILTIN_GROUPS 删掉飞书渠道项        → 「未出现在 AlertRuleView.vue」
  R7 10. probeChannels 不再遍历 CHANNELS             → 「未遍历 CHANNELS」
  R8 11. 改掉 POST /alert-test 路径                  → 「未暴露 POST /alert-test」
  R9 12. systemConfigAlertTest 指向错误路径          → 「不一致」
     13. AlertRuleView 不再调用该端点               → 「端点是死代码」
  R10 14. 桥未配置时改回返回 200                     → 「未在 FEISHU_WEBHOOK_URL 缺失时返回 503」
     15. 桥把「飞书业务码非 0」也返回 200            → 「没有返回失败码」
     15b. 桥改用单线程 HTTPServer 装配              → 「未用 ThreadingHTTPServer 装配」
     16. 桥的报文改用钉钉 `msgtype`                 → 「未产出 msg_type」
  R11 17. compose 把 relay 服务改名                  → 「没有这个服务名」（跨文件断链）
     18. compose 改 relay 监听端口                  → 「而该服务实际监听」
     19. receiver 路径改成桥不接受的路由             → 「只接受」
  R12 20. prometheus-full 删掉 alerting 段           → 「却没有 alerting 段」
     21. prometheus-full 推错 alertmanager 端口      → 「推错端口」
  R13 22. alertmanager 摘出 alerting profile         → 「会被默认栈拉起」
     23. alertmanager 不再 depends_on 桥            → 「未 depends_on」
     24. alertmanager 不挂仓库那份配置              → 「未挂载」
  守卫 25. MIN_CHANNELS 抬到 99                      → 「CHANNELS 结构可能已变」（恒真守卫）
     26. MIN_ALERT_KEYS 抬到 99                     → 「常量格式可能已变」（锚点守卫）
     27. MIN_SERVICES 抬到 99                        → 「结构可能已变」（锚点守卫）
     28. MIN_RELAY_CHARS 抬到 999999                 → 「只读到」（锚点守卫）
  反向 29. CHANNELS 里把 FEISHU 挪到最后一位          → **必须仍绿**（不绑顺序）
     30. feishuPayload 多写一个无关字段              → **必须仍绿**（不绑整块文本）
     31. receiver URL 用单引号包裹                  → **必须仍绿**（不绑引号）
     32. 服务多挂一个 profile                        → **必须仍绿**（不绑「只有 alerting」）
     33. 全部还原后                                 → 必须回绿（防「永久红」被当有效）

用法
----
    python scripts/devops/verify-ops-alert-channels-drift.py

退出码 0 = 全部符合预期；1 = 有分支未按预期表现（判据可疑）。
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from shutil import which

ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / "scripts" / "check-ops-alert-channels.mjs"
DISPATCHER = (
    ROOT
    / "services"
    / "trade-service"
    / "src"
    / "main"
    / "java"
    / "com"
    / "aicabinet"
    / "trade"
    / "service"
    / "OpsAlertDispatcher.java"
)
CONFIG_SERVICE = (
    ROOT
    / "services"
    / "trade-service"
    / "src"
    / "main"
    / "java"
    / "com"
    / "aicabinet"
    / "trade"
    / "service"
    / "SystemConfigService.java"
)
ALERT_VIEW = ROOT / "clients" / "admin-vue" / "src" / "views" / "system" / "AlertRuleView.vue"
CONTROLLER = (
    ROOT
    / "services"
    / "trade-service"
    / "src"
    / "main"
    / "java"
    / "com"
    / "aicabinet"
    / "trade"
    / "api"
    / "SystemConfigController.java"
)
ENDPOINTS = ROOT / "clients" / "admin-vue" / "src" / "api" / "endpoints.ts"
RELAY_FILE = ROOT / "infra" / "monitoring" / "feishu-relay.py"
AM_CONFIG = ROOT / "infra" / "monitoring" / "alertmanager.yml"
FULL_COMPOSE = ROOT / "infra" / "docker-compose.full.yml"
PROM_FULL = ROOT / "infra" / "monitoring" / "prometheus-full.yml"

FEISHU_CHANNEL_LINE = (
    b'            new Channel("FEISHU", SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK),\n'
)
WEBHOOK_CHANNEL_LINE = b'            new Channel("WEBHOOK", SystemConfigService.OPS_ALERT_WEBHOOK)\n'
PAYLOAD_FOR_FEISHU_CASE = (
    b'            case "FEISHU" -> feishuPayload(text,\n'
    b'                    systemConfigService.getValue('
    b'SystemConfigService.OPS_ALERT_FEISHU_SIGN_SECRET, ""));\n'
)
FEISHU_SEED = (
    b'        upsertIfAbsent(OPS_ALERT_FEISHU_WEBHOOK, "",\n'
    b'                "\xe8\xbf\x90\xe8\x90\xa5\xe5\x91\x8a\xe8\xad\xa6\xef\xbc\x9a'
    b'\xe9\xa3\x9e\xe4\xb9\xa6\xe8\x87\xaa\xe5\xae\x9a\xe4\xb9\x89\xe6\x9c\xba\xe5\x99\xa8'
    b'\xe4\xba\xba Webhook URL\xef\xbc\x88\xe7\x95\x99\xe7\xa9\xba\xe4\xb8\x8d\xe6\x8e\xa8'
    b'\xe9\x80\x81\xef\xbc\x89");\n'
)
VIEW_FEISHU_KEY = b"    'ops.alert.feishu_webhook',\n"


def node_bin() -> str:
    found = which("node")
    if found:
        return found
    fallback = (
        Path.home() / ".workbuddy" / "binaries" / "node" / "versions" / "22.22.2-3" / "node.exe"
    )
    if fallback.exists():
        return str(fallback)
    print("找不到 node，无法运行门禁", file=sys.stderr)
    sys.exit(2)


NODE = node_bin()


def run_gate() -> tuple[int, str]:
    proc = subprocess.run(
        [NODE, str(GATE)],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def read(path: Path) -> bytes:
    return path.read_bytes()


def write(path: Path, data: bytes) -> None:
    """按字节写回：避免 Python 文本模式把 LF 改成 CRLF（本仓踩过这个坑）。"""
    path.write_bytes(data)


class Case:
    def __init__(self, name: str, expect_red: bool, marker: str, mutate) -> None:
        self.name = name
        self.expect_red = expect_red
        self.marker = marker
        self.mutate = mutate


def replace_tokens(path: Path, pairs: list[tuple[bytes, bytes]]):
    """按字节替换；锚点一律用 `\\n` 写，落到文件时自动折成该文件的实际行尾。

    本仓的行尾并不统一（`OpsAlertDispatcher.java` 是 CRLF，其余是 LF），
    锚点写死某一种行尾就会「找不到片段」——那是**脚本自己**坏掉，不是门禁失效。
    """

    def _mutate() -> None:
        data = read(path)
        eol = b"\r\n" if b"\r\n" in data else b"\n"
        for old, new in pairs:
            needle = old.replace(b"\n", eol)
            if needle not in data:
                raise AssertionError(
                    f"{path.name} 里找不到待替换片段 {old!r} —— 门禁锚点已漂移，请同步本脚本"
                )
            data = data.replace(needle, new.replace(b"\n", eol))
        write(path, data)

    return _mutate


CASES = [
    # ---------- R1 渠道接线 ----------
    Case(
        "FEISHU 渠道名被换成别的渠道",
        True,
        "未声明 FEISHU 渠道",
        replace_tokens(
            DISPATCHER,
            [(b'new Channel("FEISHU", SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK)',
              b'new Channel("SLACK", SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK)')],
        ),
    ),
    Case(
        "整条删掉 FEISHU 渠道声明（先撞上计数守卫）",
        True,
        "CHANNELS 结构可能已变",
        replace_tokens(DISPATCHER, [(FEISHU_CHANNEL_LINE, b"")]),
    ),
    Case(
        "FEISHU 渠道改绑企微的配置常量",
        True,
        "绑定的配置常量是",
        replace_tokens(
            DISPATCHER,
            [
                (
                    b"SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK)",
                    b"SystemConfigService.OPS_ALERT_WECOM_WEBHOOK)",
                )
            ],
        ),
    ),
    # ---------- R2 报文形状 ----------
    Case(
        "feishuPayload 改用钉钉的 msgtype",
        True,
        "未产出",
        replace_tokens(DISPATCHER, [(b'body.put("msg_type", "text")', b'body.put("msgtype", "text")')]),
    ),
    # ---------- R3 分发路由 ----------
    Case(
        "payloadFor 删掉 FEISHU 分支（落 default）",
        True,
        '未把 "FEISHU" 路由到 feishuPayload',
        replace_tokens(DISPATCHER, [(PAYLOAD_FOR_FEISHU_CASE, b"")]),
    ),
    # ---------- R4 失败判定 ----------
    Case(
        "deliveryError 的 FEISHU 分支改看 errcode",
        True,
        "FEISHU 分支未看",
        replace_tokens(DISPATCHER, [(b'case "FEISHU" -> "code";', b'case "FEISHU" -> "errcode";')]),
    ),
    Case(
        "postJson 退回 toBodilessEntity()",
        True,
        "toBodilessEntity() 丢弃响应体",
        replace_tokens(DISPATCHER, [(b".toEntity(String.class);", b".toBodilessEntity();")]),
    ),
    # ---------- R5 配置闭环 ----------
    Case(
        "去掉飞书 webhook 的 upsertIfAbsent seed",
        True,
        "未在 upsertIfAbsent 里 seed",
        replace_tokens(CONFIG_SERVICE, [(FEISHU_SEED, b"")]),
    ),
    # ---------- R6 运营台可见 ----------
    Case(
        "运营台 BUILTIN_GROUPS 删掉飞书渠道项",
        True,
        "未出现在 AlertRuleView.vue",
        replace_tokens(ALERT_VIEW, [(VIEW_FEISHU_KEY, b"")]),
    ),
    # ---------- R7 试发能力 ----------
    Case(
        "probeChannels 不再遍历 CHANNELS",
        True,
        "未遍历 CHANNELS",
        replace_tokens(
            DISPATCHER,
            [(
                b"        List<ChannelProbe> probes = new ArrayList<>();\n"
                b"        for (Channel channel : CHANNELS) {",
                b"        List<ChannelProbe> probes = new ArrayList<>();\n"
                b"        for (Channel channel : List.<Channel>of()) {",
            )],
        ),
    ),
    # ---------- R8 试发端点 ----------
    Case(
        "改掉 POST /alert-test 路径",
        True,
        "未暴露 POST /alert-test",
        replace_tokens(CONTROLLER, [(b'@PostMapping("/alert-test")', b'@PostMapping("/alert-probe")')]),
    ),
    # ---------- R9 前端闭环 ----------
    Case(
        "systemConfigAlertTest 指向错误路径",
        True,
        "不一致",
        replace_tokens(ENDPOINTS, [(b"${ops}/system-configs/alert-test", b"${ops}/system-configs/alert-probe")]),
    ),
    Case(
        "AlertRuleView 不再调用试发端点",
        True,
        "端点是死代码",
        replace_tokens(ALERT_VIEW, [(b"AdminEndpoints.systemConfigAlertTest", b"AdminEndpoints.systemConfigs")]),
    ),
    # ---------- R10 桥的失败语义 ----------
    Case(
        "桥在未配置飞书地址时改回返回 200",
        True,
        "未在 `FEISHU_WEBHOOK_URL` 缺失时返回 503",
        replace_tokens(
            RELAY_FILE,
            [(b'        return 503, "FEISHU_WEBHOOK_URL', b'        return 200, "FEISHU_WEBHOOK_URL')],
        ),
    ),
    Case(
        "桥把「飞书业务码非 0」也返回 200（拒收=送达）",
        True,
        "没有返回失败码",
        replace_tokens(
            RELAY_FILE,
            [(
                '        return 502, f"飞书拒绝投递'.encode(),
                '        return 200, f"飞书拒绝投递'.encode(),
            )],
        ),
    ),
    Case(
        "桥的报文改用钉钉的 msgtype",
        True,
        "未产出 `msg_type`",
        replace_tokens(RELAY_FILE, [(b'    body["msg_type"] = "text"', b'    body["msgtype"] = "text"')]),
    ),
    # ---------- R11 链路接线（跨文件） ----------
    Case(
        "compose 把 relay 服务改名（alertmanager 的 receiver 就跟不上了）",
        True,
        "没有这个服务名",
        replace_tokens(FULL_COMPOSE, [(b"  feishu-alert-relay:\n", b"  feishu-relay:\n")]),
    ),
    Case(
        "compose 改掉 relay 的监听端口（receiver 还发旧端口）",
        True,
        "而该服务实际监听",
        replace_tokens(FULL_COMPOSE, [(b'      PORT: "8098"', b'      PORT: "8099"')]),
    ),
    Case(
        "alertmanager 的 receiver 路径改成桥不接受的路由",
        True,
        "只接受",
        replace_tokens(AM_CONFIG, [(b"/webhook\n", b"/notify\n")]),
    ),
    # ---------- R12 规则有加载就必须有下游 ----------
    Case(
        "prometheus-full 删掉 alerting 段（规则仍加载）",
        True,
        "却没有 alerting 段",
        replace_tokens(
            PROM_FULL,
            [(
                b"alerting:\n  alertmanagers:\n    - static_configs:\n"
                b'        - targets: ["alertmanager:9093"]\n',
                b"",
            )],
        ),
    ),
    Case(
        "prometheus-full 把告警推错的 alertmanager 端口",
        True,
        "推错端口",
        replace_tokens(PROM_FULL, [(b'["alertmanager:9093"]', b'["alertmanager:9999"]')]),
    ),
    # ---------- R13 起得来 ----------
    Case(
        "compose 的 alertmanager 从 alerting profile 里摘掉",
        True,
        "会被默认栈拉起",
        replace_tokens(FULL_COMPOSE, [(b'    profiles: ["alerting"]\n    restart: unless-stopped\n'
                                       b'    command:\n      - "--config.file',
                                       b'    restart: unless-stopped\n'
                                       b'    command:\n      - "--config.file')]),
    ),
    Case(
        "compose 的 alertmanager 不再 depends_on 桥",
        True,
        "未 depends_on",
        replace_tokens(FULL_COMPOSE, [(b"    depends_on: [feishu-alert-relay]\n", b"")]),
    ),
    Case(
        "compose 的 alertmanager 不挂仓库里那份配置",
        True,
        "未挂载",
        replace_tokens(FULL_COMPOSE, [(b"./monitoring/alertmanager.yml", b"./monitoring/other.yml")]),
    ),
    # ---------- 恒真/锚点守卫 ----------
    Case(
        "MIN_CHANNELS 抬到 99",
        True,
        "CHANNELS 结构可能已变",
        replace_tokens(GATE, [(b"const MIN_CHANNELS = 4;", b"const MIN_CHANNELS = 99;")]),
    ),
    Case(
        "MIN_ALERT_KEYS 抬到 99",
        True,
        "常量格式可能已变",
        replace_tokens(GATE, [(b"const MIN_ALERT_KEYS = 5;", b"const MIN_ALERT_KEYS = 99;")]),
    ),
    Case(
        "MIN_SERVICES 抬到 99",
        True,
        "结构可能已变",
        replace_tokens(GATE, [(b"const MIN_SERVICES = 8;", b"const MIN_SERVICES = 99;")]),
    ),
    Case(
        "MIN_RELAY_CHARS 抬到 999999",
        True,
        "只读到",
        replace_tokens(GATE, [(b"const MIN_RELAY_CHARS = 2000;", b"const MIN_RELAY_CHARS = 999999;")]),
    ),
    # ---------- 反向守卫：不许乱红 ----------
    Case(
        "CHANNELS 里把 FEISHU 挪到最后一位（应仍绿）",
        False,
        "",
        replace_tokens(
            DISPATCHER,
            [
                (FEISHU_CHANNEL_LINE, b""),
                (
                    WEBHOOK_CHANNEL_LINE,
                    WEBHOOK_CHANNEL_LINE[:-1] + b",\n" + FEISHU_CHANNEL_LINE,
                ),
            ],
        ),
    ),
    Case(
        "feishuPayload 多写一个无关字段（应仍绿）",
        False,
        "",
        replace_tokens(
            DISPATCHER,
            [(
                b'        body.put("msg_type", "text");',
                b'        body.put("trace", "ops");\n        body.put("msg_type", "text");',
            )],
        ),
    ),
    Case(
        "alertmanager 的 receiver URL 改用单引号包裹（应仍绿）",
        False,
        "",
        replace_tokens(
            AM_CONFIG,
            [(
                b"      - url: http://feishu-alert-relay:8098/webhook\n",
                b"      - url: 'http://feishu-alert-relay:8098/webhook'\n",
            )],
        ),
    ),
    Case(
        "compose 给两个服务再挂一个 profile（应仍绿：不绑「只有 alerting」）",
        False,
        "",
        replace_tokens(
            FULL_COMPOSE,
            [(b'    profiles: ["alerting"]\n', b'    profiles: ["alerting", "observability"]\n')],
        ),
    ),
    Case(
        "桥改用单线程 HTTPServer 装配（keep-alive 下会被饿死）",
        True,
        "未用 ThreadingHTTPServer 装配",
        replace_tokens(RELAY_FILE, [(b"server = ThreadingHTTPServer(", b"server = HTTPServer(")]),
    ),
    Case("全部还原后回绿", False, "", lambda: None),
]

TARGETS = [
    GATE,
    DISPATCHER,
    CONFIG_SERVICE,
    ALERT_VIEW,
    CONTROLLER,
    ENDPOINTS,
    RELAY_FILE,
    AM_CONFIG,
    FULL_COMPOSE,
    PROM_FULL,
]


def main() -> int:
    originals = {path: read(path) for path in TARGETS}
    results: list[tuple[str, bool, str]] = []
    try:
        code, out = run_gate()
        print(
            f"[baseline] exit={code} "
            f"{'OK' if code == 0 else 'RED（改造前基线就不绿，后续结论不可信）'}"
        )

        for case in CASES:
            for path, data in originals.items():
                write(path, data)
            case.mutate()

            code, out = run_gate()
            went_red = code != 0
            marker_ok = (case.marker in out) if case.expect_red else True
            passed = (went_red == case.expect_red) and marker_ok

            detail = f"exit={code}"
            if case.expect_red and not marker_ok:
                detail += f"（报错文案里未出现 {case.marker!r}）"
            if not case.expect_red and went_red:
                first = next((ln for ln in out.splitlines() if "FAIL" in ln), "")
                detail += f"（不该红却红了：{first.strip()}）"
            results.append((case.name, passed, detail))
    finally:
        for path, data in originals.items():
            write(path, data)
        restored = all(read(p) == d for p, d in originals.items())
        print(f"[restore] 字节级还原={'一致' if restored else '不一致！'}")

    print()
    width = max(len(name) for name, _, _ in results)
    ok = 0
    for name, passed, detail in results:
        print(f"  [{'PASS' if passed else 'FAIL'}] {name.ljust(width)}  {detail}")
        ok += 1 if passed else 0
    print()
    print(f"{ok}/{len(results)} 用例符合预期")
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
