#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""withdraw-paying-timeout 托管接线的负向验证（§11.1：新进判据集合的条目必须证明它**会红**）。

对每个用例：注入一处真实漂移 -> 跑门禁 -> 必须 RC!=0 且报错文本命中预期关键词
-> 字节级还原 -> 门禁必须复绿。任何一步不符即判定失败。
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "services/trade-service/src/main/java/com/aicabinet/trade"
KEYS = JAVA / "service/XxlJobManagedTasks.java"
HANDLER = JAVA / "service/ScheduledTaskXxlJobHandler.java"
ZONES = JAVA / "support/ScheduleZones.java"
XXL_SEED = ROOT / "infra/xxl-job/seed_aicabinet_jobs.sql"

WIRING = "scripts/check-xxl-job-wiring.mjs"
SEEDGATE = "scripts/check-scheduled-task-seed.mjs"

CASES = [
    {
        "id": "N1",
        "desc": "MAX_SILENCE_BY_TASK 漏条目 -> 看护会静默跳过该任务（停跑无人发现）",
        "file": ZONES,
        "pairs": [
            (
                'Map.entry("withdraw-paying-timeout", Duration.ofMinutes(30)),      // 10min ×3\n',
                "",
            )
        ],
        "gate": WIRING,
        "expect": "缺少超期看护阈值",
    },
    {
        "id": "N2",
        "desc": "XXL_CRON_BY_TASK 漏条目 -> 调度中心无排期依据",
        "file": ZONES,
        "pairs": [
            (
                'Map.entry("withdraw-paying-timeout", "0 0/10 * * * ?"),\n            ',
                "",
            )
        ],
        "gate": WIRING,
        "expect": "缺少 XXL cron 约定",
    },
    {
        "id": "N3",
        "desc": "XXL cron 写成 7 段（日/周同时限定）-> XXL CronExpression 拒绝解析，job 永不触发",
        "file": ZONES,
        "pairs": [
            (
                'Map.entry("withdraw-paying-timeout", "0 0/10 * * * ?")',
                'Map.entry("withdraw-paying-timeout", "0 0/10 * * * ? *")',
            )
        ],
        "gate": WIRING,
        "expect": "是 7 段",
    },
    {
        "id": "N4",
        "desc": "具名 handler 缺失 -> 调度台派发 job handler not found",
        "file": HANDLER,
        "pairs": [
            (
                '    @XxlJob("withdrawPayingTimeoutJob")\n'
                '    public void withdrawPayingTimeoutJob() {\n'
                '        runKey("withdraw-paying-timeout");\n'
                "    }\n",
                "",
            )
        ],
        "gate": WIRING,
        "expect": "缺少具名 @XxlJob handler",
    },
    {
        "id": "N5",
        "desc": "XXL 种子排期行缺失 -> 执行器 handler 无排期，永远不触发",
        "file": XXL_SEED,
        "pairs": [
            (
                ",\n-- 提现打款超时兜底（H38）：写型任务（置 FAILED + 解冻资金），故 fail_retry_count=1\n"
                "(132, 10, '提现打款超时兜底', now(), now(), 'aicabinet', '',\n"
                " 'CRON', '0 0/10 * * * ?', 'DO_NOTHING', 'FAILOVER',\n"
                " 'withdrawPayingTimeoutJob', '', 'SERIAL_EXECUTION', 0, 1, 'BEAN', '', "
                "'GLUE代码初始化', now(), '', 1, 0, 0)",
                "",
            )
        ],
        "gate": WIRING,
        "expect": "未在 seed_aicabinet_jobs.sql 排期",
    },
    {
        "id": "N6",
        "desc": "scheduled_task 种子行缺失 -> 执行记录被 finish() 静默丢弃，运营台不可见",
        "file": ROOT
        / "services/trade-service/src/main/resources/db/migration/V278__withdraw_paying_timeout_scheduled_task.sql",
        "pairs": [
            (
                "('withdraw-paying-timeout', '提现打款超时兜底', 'FINANCE', '每 10 分钟',\n"
                " 'PAYING 超 1 小时的提现单置 FAILED 并解冻（商户/线长），之后可人工重试打款（XXL-JOB 托管）')\n",
                "",
            )
        ],
        "gate": SEEDGATE,
        "expect": "缺少 scheduled_task 种子行",
    },
    {
        "id": "N7",
        "desc": "register() 漏注册 -> XXL 派发进来 registry.get(key) 为空，handleFail「任务未注册」",
        "file": JAVA / "service/ScheduledTaskRegistry.java",
        "pairs": [
            (
                'register("withdraw-paying-timeout", "提现打款超时兜底", FINANCE, "每 10 分钟", 600,\n'
                "                reconciliationScheduler::failStalePayingWithdraws);\n",
                "",
            )
        ],
        "gate": WIRING,
        "expect": "未在 ScheduledTaskRegistry 注册",
    },
]


def adapt(text: str, raw: bytes) -> str:
    """按目标文件实际行尾改写锚点（Java 源 CRLF，SQL 可能 LF）。"""
    if b"\r\n" in raw:
        return text.replace("\n", "\r\n")
    return text


def run(gate: str):
    p = subprocess.run(
        ["node", gate], cwd=ROOT, capture_output=True, text=True, encoding="utf-8"
    )
    return p.returncode, (p.stdout or "") + (p.stderr or "")


fails = []
all_restored = True
for c in CASES:
    path: Path = c["file"]
    raw = path.read_bytes()
    original = raw.decode("utf-8")
    mutated = original
    ok_pairs = True
    for old, new in c["pairs"]:
        a = adapt(old, raw)
        b = adapt(new, raw)
        if a not in mutated:
            ok_pairs = False
            break
        mutated = mutated.replace(a, b, 1)
    if not ok_pairs:
        fails.append(f"{c['id']} ANCHOR-MISS: 注入锚点未命中（判据已失效，需同步脚本）")
        continue

    path.write_bytes(mutated.encode("utf-8"))
    try:
        rc, out = run(c["gate"])
        red = rc != 0 and c["expect"] in out
        if not red:
            fails.append(
                f"{c['id']} FAIL: 期望门禁转红且命中「{c['expect']}」，实得 RC={rc}"
            )
    finally:
        path.write_bytes(raw)  # 字节级还原

    restored = path.read_bytes() == raw
    if not restored:
        all_restored = False
        fails.append(f"{c['id']} RESTORE-FAIL: 文件未复原")
    rc2, _ = run(c["gate"])
    if rc2 != 0:
        all_restored = False
        fails.append(f"{c['id']} REGREEN-FAIL: 还原后门禁未复绿 RC={rc2}")
    print(f"{c['id']} {'PASS' if red and restored and rc2 == 0 else 'FAIL'}: {c['desc']}")

print()
print(f"用例 {len(CASES)} 个，失败 {len(fails)} 个，ALL_RESTORED={all_restored}")
for f in fails:
    print("  -", f)
sys.exit(1 if fails else 0)
