#!/usr/bin/env python3
"""check-prometheus-metric-names 门禁的「判据有效性」A/B 验证（2026-09-18）。

为什么必须有这个脚本
--------------------
本轮实证的缺陷形态是「**静默失效**」：Micrometer 对 Gauge 剥离 `_total` 后缀，
于是 `cabinet.devices.total` 实际导出成 `cabinet_devices`，而告警/看板按
`cabinet_devices_total` 引用 —— 既不报错、也不触发。一个只会打 OK 的门禁对这种形态
毫无价值，必须**真注入漂移**逐条证明它会红。沿用同目录先例的形态。

覆盖的分支（与 check-prometheus-metric-names.mjs 的 fail() 一一对应）
--------------------------------------------------------------------
  1. 注册点改回 `cabinet.devices.total`（Gauge 的 `_total` 会被 Micrometer 剥掉）
  2. 告警规则改回 `cabinet_devices_total`
  3. Grafana 看板改回 `cabinet_devices_total`
  4. admin 运维页改回 `cabinet_devices_total`
  5. 摘掉 alerts.yml 的 `# gate: draft-unimplemented`（未被加载 ⇒ 死配置）
  6. 把已标记为草稿的 alerts.yml 挂进 rule_files（草稿不得被加载）
  7. `MIN_REGISTERED` 抬到 99（Java 注册点锚点失效 ⇒ 恒绿守卫）
  8. `MIN_REFS` 抬到 999（消费方 expr 锚点失效 ⇒ 恒绿守卫）
  9. 全部还原后必须回绿（反向守卫，防「永久红」被当成有效）

2026-09-21 追加（修 Loki 看板误报后）
------------------------------------
  10. **Loki 看板注入假指标名 ⇒ 仍绿**：R3 只校验 Prometheus 数据源的面板。
      加 5 个 Loki 日志看板时，门禁曾连报 9 条「不存在的指标」（`service`/`keyword`/`traceId`/
      `count_over_time`/`warn`/`exception`/`fail`），全是把 LogQL 的标签名、模板变量、函数名
      当成了 PromQL 指标名。此例锁住「按数据源同源过滤」这一语义，防止后人再把它改回全量扫描。
  11. `MIN_PROM_EXPRS` 抬到 999（Prometheus 面板 expr 锚点失效 ⇒ 恒绿守卫）：
      证明第 10 条的「绿」是因为**真的过滤了**，不是因为过滤写错导致一条都没校验。

用法
----
    python scripts/devops/verify-metric-names-drift.py

退出码 0 = 全部符合预期；1 = 有分支未按预期表现。任何情况下都会还原文件。
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from shutil import which

ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / "scripts" / "check-prometheus-metric-names.mjs"
METRICS_JAVA = (
    ROOT / "services" / "trade-service" / "src" / "main"
    / "java" / "com" / "aicabinet" / "trade" / "metrics" / "CabinetMetrics.java"
)
ALERT_RULES = ROOT / "infra" / "prometheus" / "alert_rules.yml"
DASHBOARD = (
    ROOT / "infra" / "monitoring" / "grafana" / "provisioning"
    / "dashboards" / "json" / "ai-cabinet-overview.json"
)
ADMIN_VIEW = ROOT / "clients" / "admin-vue" / "src" / "views" / "system" / "DevOpsHubView.vue"
LOKI_DASHBOARD = (
    ROOT / "infra" / "monitoring" / "grafana" / "provisioning"
    / "dashboards" / "json" / "ai-cabinet-logs-stream.json"
)
DRAFT_ALERTS = ROOT / "infra" / "monitoring" / "alerts.yml"
PROM_FULL = ROOT / "infra" / "monitoring" / "prometheus-full.yml"

GOOD = b"cabinet.devices.count"
BAD = b"cabinet.devices.total"
GOOD_M = b"cabinet_devices_count"
BAD_M = b"cabinet_devices_total"
# 该 Loki 看板里必然出现的模板变量（`|~ "$keyword"`）：换成假指标名后，若门禁仍绿 ⇒ 说明它确实按数据源跳过了。
# ⚠️ 别用 `count_over_time` 之类 LogQL 函数名：只有 metrics/errorcount 页有，stream 页没有（锚点会找不到）。
LOKI_TOKEN = b"$keyword"
LOKI_FAKE = b"cabinet_devices_probe_not_a_metric"


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
    path.write_bytes(data)


class Case:
    def __init__(self, name: str, expect_red: bool, marker: str, mutate) -> None:
        self.name = name
        self.expect_red = expect_red
        self.marker = marker
        self.mutate = mutate


def replace_tokens(path: Path, pairs: list[tuple[bytes, bytes]]):
    def _mutate() -> None:
        data = read(path)
        for old, new in pairs:
            if old not in data:
                raise AssertionError(
                    f"{path.name} 里找不到待替换片段 {old!r} —— 门禁锚点已漂移，请同步本脚本"
                )
            data = data.replace(old, new)
        write(path, data)

    return _mutate


def mount_draft_rule() -> None:
    """把已标记为草稿的 alerts.yml 挂进 rule_files（应当被判红）。

    锚点不含换行：本仓 yml 行尾不统一（CRLF/LF 混用），带 `\\n` 的锚点会匹配不到。
    """
    data = read(PROM_FULL)
    old = b"- /etc/prometheus/alert_rules.yml"
    if old not in data:
        raise AssertionError("prometheus-full.yml 的 rule_files 片段已变，请同步本脚本")
    new = old + b"\n  - /etc/prometheus/alerts.yml"
    write(PROM_FULL, data.replace(old, new, 1))


CASES = [
    Case("注册点改回 gauge 的 .total", True, "引用了不存在的指标", replace_tokens(METRICS_JAVA, [(GOOD, BAD)])),
    Case("告警规则改回 cabinet_devices_total", True, "引用了不存在的指标", replace_tokens(ALERT_RULES, [(GOOD_M, BAD_M)])),
    Case("Grafana 看板改回 cabinet_devices_total", True, "引用了不存在的指标", replace_tokens(DASHBOARD, [(GOOD_M, BAD_M)])),
    Case("admin 运维页改回 cabinet_devices_total", True, "引用了不存在的指标", replace_tokens(ADMIN_VIEW, [(GOOD_M, BAD_M)])),
    Case(
        "摘掉 alerts.yml 的草稿标记",
        True,
        "没有被任何 prometheus 配置的 rule_files 引用",
        # 不带换行锚定：本仓 yml 存在 CRLF 行尾，带 `\n` 会匹配不到（实测坑）。
        replace_tokens(DRAFT_ALERTS, [(b"# gate: draft-unimplemented", b"")]),
    ),
    Case("把草稿挂进 rule_files", True, "草稿不得被加载", mount_draft_rule),
    Case("MIN_REGISTERED 抬到 99", True, "只从 Java 解析出", replace_tokens(GATE, [(b"const MIN_REGISTERED = 15;", b"const MIN_REGISTERED = 99;")])),
    Case("MIN_REFS 抬到 999", True, "只解析出", replace_tokens(GATE, [(b"const MIN_REFS = 8;", b"const MIN_REFS = 999;")])),
    # 反向例：Loki（LogQL）面板里的"假指标名"**不该**被报出来 —— 它本来就不是 PromQL。
    Case(
        "Loki 看板注入假指标名仍应绿",
        False,
        "",
        replace_tokens(LOKI_DASHBOARD, [(LOKI_TOKEN, LOKI_FAKE)]),
    ),
    Case("MIN_PROM_EXPRS 抬到 999", True, "只校验到", replace_tokens(GATE, [(b"const MIN_PROM_EXPRS = 15;", b"const MIN_PROM_EXPRS = 999;")])),
    Case("全部还原后回绿", False, "", lambda: None),
]

TARGETS = [METRICS_JAVA, ALERT_RULES, DASHBOARD, ADMIN_VIEW, LOKI_DASHBOARD, DRAFT_ALERTS, PROM_FULL, GATE]


def main() -> int:
    originals = {path: read(path) for path in TARGETS}
    results: list[tuple[str, bool, str]] = []
    try:
        code, _ = run_gate()
        print(f"[baseline] exit={code} {'OK' if code == 0 else 'RED（改造前基线就不绿，后续结论不可信）'}")

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
            results.append((case.name, passed, detail))
            print(f"[{'PASS' if passed else 'FAIL'}] {case.name}: {detail}")
    finally:
        for path, data in originals.items():
            write(path, data)

    restored = all(read(path) == originals[path] for path in TARGETS)
    code, _ = run_gate()
    print(f"[restore] 字节级还原={'OK' if restored else '不一致'} 复跑 exit={code}")
    if not restored or code != 0:
        print("还原后未回绿 —— 仓库处于不一致状态，请 git diff 检查", file=sys.stderr)
        return 1

    failed = [name for name, ok, _ in results if not ok]
    print(f"\n汇总：{len(results) - len(failed)}/{len(results)} 例符合预期")
    if failed:
        print("未按预期表现：" + "、".join(failed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
