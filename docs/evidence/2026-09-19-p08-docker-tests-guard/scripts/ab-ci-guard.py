"""P0-8 A/B：升级后的 build job 守卫（Verify Docker-backed tests ran）。

⚠️ 两条使用纪律（都是上一版踩出来的）：
  1. 必须用 **Git Bash 的绝对路径 + 前置 Git 的 usr/bin 到 PATH**：直接 `shutil.which('bash')`
     会命中 PortableGit/WSL 的 bash，其 PATH 里 `find` 解析到 C:\\Windows\\System32\\find.exe
     （Windows 的文本搜索工具，会等 stdin）⇒ 守卫静默「零发现」并挂起 ~64s，整批结果全假。
  2. 守卫脚本**只用临时副本**：上一版直接改 `.tmp/p08-guard.sh`，被 SIGTERM 时 finally 未执行，
     留下一个锚点被改坏的脚本 —— 实验对象被污染，后续所有结论作废。
"""
import contextlib
import os
import pathlib
import shutil
import subprocess
import sys

REPO = pathlib.Path(r"C:\Users\cwx\OneDrive\Desktop\demo\ai-cabinet")
GUARD = REPO / ".tmp" / "p08-guard.sh"
RUN_GUARD = REPO / ".tmp" / "_p08-guard-run.sh"
SR = REPO / "services" / "trade-service" / "target" / "surefire-reports"

BASH = r"D:\devTools\Git\bin\bash.exe"
assert pathlib.Path(BASH).exists(), BASH
ENV = dict(os.environ)
ENV["PATH"] = r"D:\devTools\Git\usr\bin;D:\devTools\Git\bin;" + ENV.get("PATH", "")
ENV.pop("BASH_ENV", None)
ENV.pop("ENV", None)

GUARD_BASE = GUARD.read_bytes()
CASES = []


def run_guard(data):
    """用临时副本跑守卫（绝不写回原文件）。"""
    RUN_GUARD.write_bytes(data)
    try:
        r = subprocess.run(
            [BASH, str(RUN_GUARD)],
            cwd=str(REPO),
            env=ENV,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=300,
        )
        return r.returncode, ((r.stdout or "") + (r.stderr or "")).strip()
    finally:
        RUN_GUARD.unlink(missing_ok=True)


@contextlib.contextmanager
def temp_bytes(path, data):
    bak = path.read_bytes()
    try:
        path.write_bytes(data)
        yield
    finally:
        path.write_bytes(bak)


@contextlib.contextmanager
def temp_deleted(path):
    bak = path.read_bytes()
    try:
        path.unlink()
        yield
    finally:
        path.write_bytes(bak)


@contextlib.contextmanager
def temp_renamed(path, suffix):
    alt = path.parent / (path.name + suffix)
    path.rename(alt)
    try:
        yield
    finally:
        if alt.exists() and not path.exists():
            alt.rename(path)


def record(label, expect_red, rc, out):
    ok = (rc != 0) if expect_red else (rc == 0)
    CASES.append((label, expect_red, rc, ok))
    print(f"{'PASS' if ok else '**FAIL**'}  {label}  (rc={rc}, 期望{'红' if expect_red else '绿'})", flush=True)
    for line in out.splitlines()[:4]:
        print(f"        {line.strip()[:150]}", flush=True)
    print(flush=True)


def case(label, expect_red, guard_data=None, report_mutation=None):
    rc, out = run_guard(GUARD_BASE if guard_data is None else guard_data)
    if report_mutation is None:
        record(label, expect_red, rc, out)
    else:
        record(label, expect_red, rc, out)


# ── 0. 基线（必须先绿，否则后续用例的红无法归因） ─────────────────────────
rc, out = run_guard(GUARD_BASE)
record("基线（真产物）", False, rc, out)
if rc != 0:
    print(
        "\n!! 基线不是绿的 ⇒ 产物本身是脏的（例如上一批 A/B 被中断留下的失败报告）。\n"
        "   后续用例的『红』将无法归因，已中止。请先刷新 target/surefire-reports 再跑。",
        flush=True,
    )
    sys.exit(2)

# ── ① 某个 @Testcontainers 类的报告被删 ──────────────────────────────────
with temp_deleted(SR / "TEST-com.aicabinet.trade.integration.WeChatNotifyIntegrationTest.xml"):
    rc, out = run_guard(GUARD_BASE)
    record("漂移① 有源码但报告被删（该测试根本没跑）", True, rc, out)

# ── ② 报告在但 0 条用例 ─────────────────────────────────────────────────
_p = SR / "TEST-com.aicabinet.trade.integration.ReconciliationIntegrationTest.xml"
with temp_bytes(_p, _p.read_bytes().replace(b"<testcase ", b"<testcaseDISABLED ")):
    rc, out = run_guard(GUARD_BASE)
    record("漂移② 报告在但 <testcase> 计数为 0（静默跳过）", True, rc, out)

# ── ③ 报告里出现 <failure> ──────────────────────────────────────────────
_p = SR / "TEST-com.aicabinet.trade.e2e.MerchantE2ETest.xml"
with temp_bytes(_p, _p.read_bytes().replace(b"</testsuite>", b"<failure>injected</failure></testsuite>", 1)):
    rc, out = run_guard(GUARD_BASE)
    record("漂移③ 报告里出现 <failure>", True, rc, out)

# ── ④ 权限漂移守卫（不带 @Testcontainers）报告被删 ──────────────────────
with temp_deleted(SR / "TEST-com.aicabinet.trade.auth.PermissionCodeDriftTest.xml"):
    rc, out = run_guard(GUARD_BASE)
    record("漂移④ PermissionCodeDriftTest 报告被删", True, rc, out)

# ── ⑤ 权限漂移守卫报告 0 条用例 ─────────────────────────────────────────
_p = SR / "TEST-com.aicabinet.trade.auth.PermissionCodeDriftTest.xml"
with temp_bytes(_p, _p.read_bytes().replace(b"<testcase ", b"<testcaseDISABLED ")):
    rc, out = run_guard(GUARD_BASE)
    record("漂移⑤ PermissionCodeDriftTest 0 条用例", True, rc, out)

# ── ⑥ 护栏：守卫自己的 @Testcontainers 筛选被改坏（零发现必须红） ────────
rc, out = run_guard(GUARD_BASE.replace(b"'^[[:space:]]*@Testcontainers'", b"'NEVER_MATCHES_XYZ'"))
record("漂移⑥ 守卫的 @Testcontainers 筛选被改坏（护栏）", True, rc, out)

# ── ⑦ 整个 surefire-reports 目录消失 ───────────────────────────────────
with temp_renamed(SR, ".hold"):
    rc, out = run_guard(GUARD_BASE)
    record("漂移⑦ 整个 surefire-reports 目录消失", True, rc, out)

# ── 反假红：非 @Testcontainers 的报告有 failure ⇒ 守卫不管它，不该红 ─────
OTHER = SR / "TEST-com.aicabinet.trade.architecture.TradeArchitectureTest.xml"
if OTHER.exists():
    with temp_bytes(OTHER, OTHER.read_bytes().replace(b"</testsuite>", b"<failure>x</failure></testsuite>", 1)):
        rc, out = run_guard(GUARD_BASE)
        record("反假红 非 @Testcontainers 报告有 failure（mvn 自己会红，守卫不越权）", False, rc, out)
else:
    print(f"        (跳过反假红：{OTHER.name} 不存在)\n", flush=True)

print("=" * 72, flush=True)
bad = [c for c in CASES if not c[3]]
print(f"A/B 汇总：{len(CASES)} 例，不符合期望 {len(bad)} 例", flush=True)
for label, expect_red, rc, ok in CASES:
    print(f"  {'OK ' if ok else 'BAD'}  {'红' if expect_red else '绿'}  rc={rc:<3} {label}", flush=True)
sys.exit(1 if bad else 0)
