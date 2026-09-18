#!/usr/bin/env python3
"""stop_grace_period 的**行为级**验证（2026-09-18）——不是「配置文件里有这行」，而是「真的有秒钟」。

为什么必须有这个脚本
--------------------
`check-compose-stop-grace.mjs` 只能证明「键写了、值不低」。它证明不了三件更要紧的事，
而那三件事**只能实测**，且都是本项目的坑：

  1. 本机 Docker **没有** 10 秒引擎默认值 —— 不写该键起出来的容器 `Config.StopTimeout` 就是 **1**，
     `docker stop` 1 秒后就 SIGKILL。所以「默认会兜底」这个假设在本机是错的。
  2. 写了 `stop_grace_period` 到底有没有生效（覆盖 daemon 默认、且真被遵守）——实测 `docker stop`
     的**等待秒数**。
  3. 「装了宽限」≠「能优雅停机」：容器里进程是 **PID 1**，内核会**忽略 PID 1 对未安装 handler 的信号**。
     一个不装 handler 的 PID 1 进程，宽限给 30 秒也会**被 SIGKILL（exit 137）**。

第 3 条正是 `feishu-alert-relay` 的真实缺陷：实测「无 handler + 30s 宽限」= 等满 33 秒后 137；
装上 handler 后 = 3 秒 exit 0。

另附一条**反直觉**的发现（本脚本会打印，供配置时参考）
------------------------------------------------------
各镜像的 `STOPSIGNAL` 并不都是 SIGTERM：`postgres:16-alpine` 是 **SIGINT**（= fast shutdown，
退出快、不需长宽限），`nginx:alpine` 是 **SIGQUIT**（= 优雅排空）。而 `docker kill --signal=TERM`
**绕过** STOPSIGNAL，给 postgres 发 SIGTERM 会触发 **smart shutdown：一直等客户端断开**（实测挂住）。
⇒ 想判断「某服务到底怎么停机」，看镜像的 STOPSIGNAL + 进程的 handler，别凭信号名猜。

用法
----
    python scripts/devops/verify-stop-grace-behavior.py          # 需要本机 Docker 可用
    python scripts/devops/verify-stop-grace-behavior.py --quick  # 跳过最慢的 postgres 对照

退出码 0 = 全部符合预期；1 = 有观测与预期不符（结论可疑）。
所有探针容器都带 `sg-probe-` 前缀，脚本结束前一定会删干净。
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

PROBE_IMG = "python:3.12-slim"
PG_IMG = "postgres:16-alpine"


def repo_root() -> Path:
    return REPO_ROOT

# 一个「不装 handler」的探针：PID 1 会忽略 SIGTERM，只记下收到了什么。
PROBE_PY = (
    "import signal,sys,time;"
    "signal.signal(signal.SIGTERM, lambda *a: print('GOT SIGTERM', flush=True));"
    "print('ready', flush=True);"
    "[time.sleep(1) for _ in iter(int, 1)]"
)

results: list[tuple[bool, str]] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    results.append((ok, label))
    print(f"  {'✓' if ok else '✗'} {label}" + (f"    {detail}" if detail else ""))


def run(*args: str, timeout: int = 180) -> subprocess.CompletedProcess:
    return subprocess.run(
        list(args),
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )


def docker(*args: str, timeout: int = 180) -> subprocess.CompletedProcess:
    return run("docker", *args, timeout=timeout)


def rm(name: str) -> None:
    docker("rm", "-f", name)


def inspect(name: str, fmt: str) -> str:
    p = docker("inspect", name, "--format", fmt)
    return (p.stdout or "").strip()


def logs(name: str) -> str:
    """容器日志必须**合并 stdout+stderr**：postgres 把日志打到 stderr，
    只读 stdout 会拿到空串，判据就会假红（本脚本第一版正是这么错的）。"""
    p = docker("logs", name)
    return (p.stdout or "") + (p.stderr or "")


def start_probe(name: str, extra: list[str]) -> None:
    rm(name)
    docker(
        "run", "-d", "--name", name, *extra, PROBE_IMG, "python", "-u", "-c", PROBE_PY,
        timeout=120,
    )
    time.sleep(3)


def stop_and_time(name: str, extra_args: list[str] | None = None) -> tuple[float, str]:
    t0 = time.time()
    docker("stop", *(extra_args or []), name, timeout=300)
    elapsed = time.time() - t0
    return elapsed, inspect(name, "{{.State.ExitCode}}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--quick", action="store_true", help="跳过 postgres 对照（最慢的一段）")
    args = ap.parse_args()

    print("=" * 78)
    print("stop_grace_period 行为级验证")
    print("=" * 78)

    # ---------- 1. daemon 默认宽限 ----------
    print("\n[1] 本机 daemon 默认宽限（不写 stop_grace_period）")
    start_probe("sg-probe-default", [])
    default_to = inspect("sg-probe-default", "{{.Config.StopTimeout}}")
    check(
        default_to == "1",
        "默认 StopTimeout == 1（⇒ 「引擎会兜底 10s」在本机不成立）",
        f"实测 StopTimeout={default_to}",
    )
    elapsed, code = stop_and_time("sg-probe-default")
    check(
        code == "137",
        "默认宽限下被 SIGKILL（exit 137）",
        f"docker stop 耗时 {elapsed:.0f}s，exit={code}",
    )
    rm("sg-probe-default")

    # ---------- 2. compose 的 stop_grace_period 真的生效 ----------
    print("\n[2] compose 声明 stop_grace_period: 30s 是否覆盖默认且被遵守")
    start_probe("sg-probe-grace", ["--stop-timeout", "30"])
    grace_to = inspect("sg-probe-grace", "{{.Config.StopTimeout}}")
    check(grace_to == "30", "StopTimeout 被覆盖为 30", f"实测 StopTimeout={grace_to}")
    elapsed, code = stop_and_time("sg-probe-grace")
    check(
        elapsed >= 25,
        "docker stop 真的等满 ~30s 才 SIGKILL（宽限被遵守）",
        f"实测等待 {elapsed:.0f}s（>25s 即证明等的是宽限而不是 1s），exit={code}",
    )
    rm("sg-probe-grace")

    # ---------- 3. PID 1 忽略信号 ⇒ 宽限全白给；装了 handler 才优雅 ----------
    print("\n[3] PID 1 无声明的信号会被忽略 ⇒ 只加宽限没用")
    start_probe("sg-probe-pid1", ["--stop-timeout", "30"])
    elapsed, code = stop_and_time("sg-probe-pid1")
    probe_log = logs("sg-probe-pid1").strip()
    check(
        code == "137" and elapsed >= 25,
        "不装 handler 的 PID 1：宽限 30s 全被浪费、最后仍是 SIGKILL",
        f"等待 {elapsed:.0f}s 后 exit={code}；日志尾部 " + (probe_log.splitlines()[-1][:60] if probe_log else "(空)"),
    )
    check("GOT SIGTERM" in probe_log, "进程其实**收到**了 SIGTERM（只是没 handler ⇒ 被忽略）")
    rm("sg-probe-pid1")

    print("\n     对照：同一个探针**装上** handler（这正是 feishu-relay 的修法）")
    handler_py = (
        "import signal,sys,time,threading;"
        "signal.signal(signal.SIGTERM, lambda *a: (print('GOT SIGTERM', flush=True), sys.exit(0)));"
        "print('ready', flush=True);"
        "[time.sleep(1) for _ in iter(int, 1)]"
    )
    rm("sg-probe-handler")
    docker(
        "run", "-d", "--name", "sg-probe-handler", "--stop-timeout", "30",
        PROBE_IMG, "python", "-u", "-c", handler_py, timeout=120,
    )
    time.sleep(3)
    elapsed, code = stop_and_time("sg-probe-handler")
    check(
        code == "0" and elapsed < 10,
        "装了 handler：立刻优雅退出（宽限一点都没浪费）",
        f"等待 {elapsed:.0f}s，exit={code}",
    )
    rm("sg-probe-handler")

    # ---------- 4. 镜像 STOPSIGNAL 不等于 SIGTERM 的反例 ----------
    print("\n[4] 各镜像的 STOPSIGNAL 并不都是 SIGTERM（决定「怎么停」的是它，不是信号名）")
    for img in [PG_IMG, "nginx:alpine", "redis:7-alpine", "mysql:8.0"]:
        sig = (docker("image", "inspect", img, "--format", "{{.Config.StopSignal}}").stdout or "").strip()
        print(f"      {img:34} STOPSIGNAL={sig if sig else '(空 ⇒ SIGTERM)'}")
    pg_sig = (
        docker("image", "inspect", PG_IMG, "--format", "{{.Config.StopSignal}}").stdout or ""
    ).strip()
    check(
        pg_sig == "SIGINT",
        "postgres 的 STOPSIGNAL 是 SIGINT（= fast shutdown，退出快、不需长宽限）",
        f"实测 {pg_sig}",
    )

    if not args.quick:
        print("\n[5] 同一个 postgres：docker stop（按 STOPSIGNAL=SIGINT）vs docker kill -s TERM")
        rm("sg-probe-pg")
        docker(
            "run", "-d", "--name", "sg-probe-pg", "-e", "POSTGRES_PASSWORD=probe", PG_IMG, timeout=180
        )
        for _ in range(40):
            if docker("exec", "sg-probe-pg", "pg_isready", "-U", "postgres").returncode == 0:
                break
            time.sleep(1)
        time.sleep(3)
        elapsed, code = stop_and_time("sg-probe-pg")
        check(
            code == "0" and elapsed < 15,
            "docker stop 走 SIGINT ⇒ 干净退出、快（无需长宽限）",
            f"等待 {elapsed:.0f}s，exit={code}",
        )

        rm("sg-probe-pg2")
        docker(
            "run", "-d", "--name", "sg-probe-pg2", "-e", "POSTGRES_PASSWORD=probe", PG_IMG, timeout=180
        )
        for _ in range(40):
            if docker("exec", "sg-probe-pg2", "pg_isready", "-U", "postgres").returncode == 0:
                break
            time.sleep(1)
        time.sleep(3)
        docker("exec", "-d", "sg-probe-pg2", "sh", "-c",
               'psql -U postgres -c "BEGIN; SELECT pg_sleep(600);" > /tmp/c.log 2>&1')
        time.sleep(4)
        docker("kill", "--signal=TERM", "sg-probe-pg2")
        time.sleep(8)
        status = inspect("sg-probe-pg2", "{{.State.Status}}")
        log = logs("sg-probe-pg2")
        check(
            status == "running" and "smart shutdown request" in log,
            "docker kill -s TERM 触发 smart shutdown：8 秒后仍在跑（在等客户端断开）",
            f"8s 后状态={status}",
        )
        rm("sg-probe-pg2")

    # ---------- 6. 本仓库里「PID 1 跑 python」的服务：HEAD vs 当前 ----------
    print("\n[6] 仓库内 PID-1 python 服务的 A/B（HEAD 修复前 vs 当前）")
    print("     判据：宽限 10s 下，HEAD 版本必然被 SIGKILL(137)，当前版本应 exit 0")
    for label, rel, port in [
        ("feishu-relay", "infra/monitoring/feishu-relay.py", 8098),
        ("sms-webhook-mock", "scripts/sms-webhook-mock.py", 8099),
    ]:
        head_blob = repo_root() / ".git"  # 占位，下面直接走 git show
        del head_blob
        proc = run("git", "show", f"HEAD:{rel}", timeout=60)
        if proc.returncode != 0:
            check(False, f"{label}: 无法从 HEAD 取到 {rel}", proc.stderr.strip()[:80])
            continue
        tmp = Path(tempfile.gettempdir()) / f"sg-head-{Path(rel).name}"
        tmp.write_bytes(proc.stdout.encode("utf-8"))

        outcomes = {}
        for tag, host_dir in [("HEAD", tmp), ("当前", repo_root() / rel)]:
            name = f"sg-probe-{label}-{time.time_ns() % 100000}"
            rm(name)
            docker(
                "run", "-d", "--name", name, "--stop-timeout", "10",
                "-e", f"PORT={port}",
                "-v", f"{Path(host_dir).as_posix()}:/app/{Path(rel).name}:ro",
                PROBE_IMG, "python", "-u", f"/app/{Path(rel).name}", timeout=180,
            )
            ok_health = False
            for _ in range(25):
                p = docker(
                    "exec", name, "python", "-c",
                    f"import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:{port}/health').status)",
                )
                if p.returncode == 0 and "200" in (p.stdout or ""):
                    ok_health = True
                    break
                time.sleep(1)
            elapsed, code = stop_and_time(name)
            outcomes[tag] = (ok_health, elapsed, code)
            rm(name)

        h_ok, h_el, h_code = outcomes["HEAD"]
        c_ok, c_el, c_code = outcomes["当前"]
        check(
            h_ok and c_ok,
            f"{label}: 两版都能起来并通过 /health（A/B 前提成立）",
            f"HEAD health={h_ok} / 当前 health={c_ok}",
        )
        check(
            h_code == "137" and h_el >= 8,
            f"{label} HEAD（无 handler）被 SIGKILL：宽限全被浪费",
            f"等待 {h_el:.0f}s，exit={h_code}",
        )
        check(
            c_code == "0" and c_el < 8,
            f"{label} 当前版本优雅退出",
            f"等待 {c_el:.0f}s，exit={c_code}",
        )
        tmp.unlink(missing_ok=True)

    print("\n" + "=" * 78)
    failed = [label for ok, label in results if not ok]
    for ok, label in results:
        print(f"  {'✓' if ok else '✗'} {label}")
    print("=" * 78)
    if failed:
        print(f"存在未按预期表现的分支（{len(failed)} 个）")
        return 1
    print(f"全部符合预期（{len(results)} 项）")
    return 0


def cleanup_all() -> None:
    """清掉所有 `sg-probe-` 容器。**必须在 finally 里调用** —— 第一版把 `rm()` 写在流程内，
    中途抛异常（当时是个变量名笔误）就在机器上留了 2 个探针容器。"""
    p = docker("ps", "-a", "--format", "{{.Names}}")
    leftovers = [n for n in (p.stdout or "").split() if n.startswith("sg-probe-")]
    for n in leftovers:
        rm(n)
    if leftovers:
        print(f"（已清理残留探针容器 {len(leftovers)} 个）")


if __name__ == "__main__":
    try:
        CODE = main()
    finally:
        cleanup_all()
    sys.exit(CODE)
