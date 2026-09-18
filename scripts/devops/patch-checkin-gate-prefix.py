#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 `doCheckInTask` 的终态闸临时改回**修复前**的形态，用来做运行时负向验证。

用法：
    python scripts/devops/patch-checkin-gate-prefix.py prefix    # 改回修复前
    python scripts/devops/patch-checkin-gate-prefix.py restore   # 从备份逐字节还原

为什么需要它：静态门禁只能证明「源码与脚本的期望值一致」。要证明
「`scripts/e2e-checkin-contract.ps1` 真的能抓到终态被复活」，必须让**修复前的代码真的跑起来**，
再看实跑脚本是否转红 —— 否则「用例通过」与「用例恒真」分不开。

改回的两处（与 git HEAD 的旧行为等价）：
  1. 删掉方法开头的终态闸（`COMPLETED`/`CANCELLED` → 409）；
  2. 方法末尾恢复旧条件 `!IN_PROGRESS && !COMPLETED` ——
     这个条件**放行 CANCELLED**，于是签到能把已取消的任务改回 IN_PROGRESS。

⚠️ 旧代码在实跑中会真的改动 `replenishment_task` 里的存量行（把 COMPLETED/CANCELLED 签到成
IN_PROGRESS 并覆盖 check_in_at/lat/lng）—— 跑完必须连数据一起还原，
见 docs/evidence/2026-09-18-replenishment-checkin/ 里的 task_rows.BEFORE/AFTER.tsv。
"""
import hashlib
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "services/trade-service/src/main/java/com/aicabinet/trade/service/ReplenishmentService.java"
BAK = ROOT / "docs/evidence/2026-09-18-replenishment-checkin/ReplenishmentService.java.FIXED.bak"

GATE_BLOCK = (
    "        if (STATUS_COMPLETED.equals(task.getStatus()) || STATUS_CANCELLED.equals(task.getStatus())) {\n"
    "            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_TASK_FINISHED);\n"
    "        }\n"
)
# 三行不带 taskRepository.save 的形态在文件里唯一（另两处同名分支体内都有 save）。
SETSTATUS_BLOCK = (
    "        if (!STATUS_IN_PROGRESS.equals(task.getStatus())) {\n"
    "            task.setStatus(STATUS_IN_PROGRESS);\n"
    "        }\n"
)
SETSTATUS_PREFIX = (
    "        if (!STATUS_IN_PROGRESS.equals(task.getStatus())\n"
    "                && !STATUS_COMPLETED.equals(task.getStatus())) {\n"
    "            task.setStatus(STATUS_IN_PROGRESS);\n"
    "        }\n"
)


def md5(b: bytes) -> str:
    return hashlib.md5(b).hexdigest()


def apply_prefix() -> int:
    raw = SRC.read_bytes()
    crlf = b"\r\n" in raw
    text = raw.decode("utf-8")

    def adapt(s: str) -> str:
        return s.replace("\n", "\r\n") if crlf else s

    # 🔴 幂等护栏：源文件已经是「修复前」形态时**必须拒绝**继续。
    # 否则第二次 prefix 会把「修复前」的内容写进备份，把还原点毁掉 ——
    # 那时 restore 只会"还原"成一个坏文件，而且看起来一切正常。
    if adapt(SETSTATUS_PREFIX) in text:
        print("[FAIL] 源文件已是「修复前」形态，拒绝再次 patch（否则会覆盖还原点）。先跑 restore。")
        return 1
    for anchor, expect in ((GATE_BLOCK, 1), (SETSTATUS_BLOCK, 1)):
        hits = text.count(adapt(anchor))
        if hits != expect:
            print(f"[FAIL] 锚点命中 {hits} 次（期望 {expect}）：{anchor.splitlines()[0]!r}")
            return 1
    BAK.write_bytes(raw)  # 确认源文件是「已修复」形态后，才把它存成还原点
    text = text.replace(adapt(GATE_BLOCK), "", 1)
    text = text.replace(adapt(SETSTATUS_BLOCK), adapt(SETSTATUS_PREFIX), 1)
    SRC.write_bytes(text.encode("utf-8"))
    print(f"[prefix] 已改回修复前：md5 {md5(raw)} -> {md5(SRC.read_bytes())}")
    print(f"[prefix] 「已修复」备份：{BAK.relative_to(ROOT)}（md5 {md5(raw)}）")
    return 0


def restore() -> int:
    if not BAK.exists():
        print(f"[FAIL] 备份不存在：{BAK}")
        return 1
    want = BAK.read_bytes()
    shutil.copyfile(BAK, SRC)
    got = SRC.read_bytes()
    if md5(got) != md5(want):
        print("[FAIL] 还原后 md5 不一致")
        return 1
    print(f"[restore] 已逐字节还原：md5 {md5(got)}")
    return 0


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "prefix":
        sys.exit(apply_prefix())
    if mode == "restore":
        sys.exit(restore())
    print(__doc__)
    sys.exit(2)
