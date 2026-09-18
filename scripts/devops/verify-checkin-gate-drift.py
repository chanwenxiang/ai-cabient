#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""新判据的负向验证：每条真注入漂移 -> 门禁必须转红 -> 按字节还原。

用法：python scripts/devops/verify-checkin-gate-drift.py
退出码 0 = 全部按预期（每条注入都转红、还原后复绿）；1 = 有判据失灵。

为什么每条都要单独注入一次：门禁「存在」不等于「有效」。
一个恒真的判据和没有判据的差别只是**更糟** —— 它让人以为已经守住了。

两个方向都要验：
  * expect_red=True  —— 判据**该红时必须红**（否则是假绿）。
  * expect_red=False —— 判据**不该红时不许红**（否则是假红）。
    假红同样有害：它会让人开始不信门禁，然后「顺手」把判据删掉。
    D17 就是这类：只换文案、结构不动，必须保持绿。
"""
import hashlib
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / "scripts" / "check-replenishment-checkin-contract.mjs"

J = "services/trade-service/src/main/java/com/aicabinet/trade"
F_SERVICE = ROOT / f"{J}/service/ReplenishmentService.java"
F_DOOR = ROOT / f"{J}/service/DeviceValidationService.java"
F_PORTAL = ROOT / f"{J}/service/MerchantInventoryPortalService.java"
F_SUPPORT = ROOT / f"{J}/support/DeviceLocationSupport.java"
F_DTO = ROOT / "services/common/common-core/src/main/java/com/aicabinet/common/dto/ReplenishmentTaskDto.java"
F_LIB = ROOT / "scripts/e2e-lib.ps1"
F_E2E = ROOT / "scripts/e2e-checkin-contract.ps1"
F_CONTRACT = ROOT / "scripts/e2e-replenishment.ps1"

STATUS_BLOCK = (
    "        if (STATUS_COMPLETED.equals(task.getStatus()) || STATUS_CANCELLED.equals(task.getStatus())) {\n"
    "            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_TASK_FINISHED);\n"
    "        }\n"
)
DEVICE_LINE = "        boolean deviceHasCoords = DeviceLocationSupport.hasCoords(device);\n"


def md5(b: bytes) -> str:
    return hashlib.md5(b).hexdigest()


def run_gate():
    r = subprocess.run(
        ["node", str(GATE)],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return r.returncode, (r.stdout or "") + (r.stderr or "")


class Drift:
    """一次漂移注入 = 对某文件做一组有序的字符串替换。

    锚点一律用 LF 写；本仓库混用 CRLF/LF（Java 源是 CRLF、部分脚本是 LF），
    所以这里按**目标文件实际的行尾**自适应 —— 否则会写成「锚点命中 0 次」的假失败，
    那种失败看起来像门禁有问题，实际只是我自己的注入没生效。
    """

    def __init__(self, name: str, expect_red: bool, *pairs):
        self.name = name
        self.expect_red = expect_red
        self.pairs = pairs  # (path, old, new, [count])

    @staticmethod
    def _adapt(text: str, crlf: bool) -> str:
        return text.replace("\n", "\r\n") if crlf else text

    def apply(self, backup: dict) -> None:
        for pair in self.pairs:
            path, old, new = pair[0], pair[1], pair[2]
            count = pair[3] if len(pair) > 3 else 1
            # 读**当前盘上**的内容而不是备份：同一文件的多个 pair 必须逐次累积
            # （D2 要先删掉闸门再插到别处，两个 pair 打同一个文件 —— 读备份会让第二步
            #  把第一步的改动整个丢掉，于是「注入生效了」是假的）。
            raw = path.read_bytes()
            crlf = b"\r\n" in raw
            text = raw.decode("utf-8")
            old_a = self._adapt(old, crlf)
            new_a = self._adapt(new, crlf)
            hits = text.count(old_a)
            if hits != count:
                raise AssertionError(
                    f"锚点命中 {hits} 次（期望 {count}，crlf={crlf}）：{old[:60]!r}"
                )
            path.write_bytes(text.replace(old_a, new_a, count).encode("utf-8"))


CASES = [
    Drift(
        "D1 签到丢掉终态文案（换成另一条）",
        True,
        (F_SERVICE, "ApiMessages.REPLENISHMENT_TASK_FINISHED);", "ApiMessages.REPLENISHMENT_TASK_NOT_FOUND);"),
    ),
    Drift(
        "D2 终态闸被挪到坐标闸之后（顺序反了）",
        True,
        (F_SERVICE, STATUS_BLOCK, ""),
        # ⚠️ 必须插到「柜机无坐标」那道闸**之后**才算真的顺序反了。
        # 第一版我插在 device 查询行之后（仍在坐标闸之前），门禁正确地保持绿色 ——
        # 那不是门禁失灵，是我的注入没打到判据上。这类「注入没生效」必须靠看 gate_rc 发现，
        # 不能靠「我改了所以它应该红了」。
        (
            F_SERVICE,
            "                        ApiMessages.REPLENISHMENT_CHECK_IN_DEVICE_LOCATION_MISSING);\n"
            "            }\n",
            "                        ApiMessages.REPLENISHMENT_CHECK_IN_DEVICE_LOCATION_MISSING);\n"
            "            }\n" + STATUS_BLOCK,
        ),
    ),
    Drift(
        "D3 签到不再点名 CANCELLED",
        True,
        (
            F_SERVICE,
            "STATUS_COMPLETED.equals(task.getStatus()) || STATUS_CANCELLED.equals(task.getStatus())",
            "STATUS_COMPLETED.equals(task.getStatus())",
        ),
    ),
    Drift(
        "D4 开门侧终态文案分叉",
        True,
        (F_DOOR, "ApiMessages.REPLENISHMENT_TASK_FINISHED", "ApiMessages.REPLENISHMENT_TASK_MISMATCH"),
    ),
    Drift(
        "D5 坐标判据被重新内联（2 处）",
        True,
        (
            F_SERVICE,
            "deviceHasCoords = DeviceLocationSupport.hasCoords(device);",
            "deviceHasCoords = device.getLatitude() != null && device.getLongitude() != null;",
            2,
        ),
    ),
    Drift(
        "D6 商户门户列表路径不再用统一判据",
        True,
        (
            F_PORTAL,
            "deviceHasCoords = DeviceLocationSupport.hasCoords(device);",
            "deviceHasCoords = device.getLatitude() != null && device.getLongitude() != null;",
        ),
    ),
    Drift(
        "D7 坐标判据本体被改名",
        True,
        (F_SUPPORT, "static boolean hasCoords(", "static boolean deviceHasCoordinates("),
    ),
    Drift("D8 DTO 去掉 deviceHasCoords 字段", True, (F_DTO, "Boolean deviceHasCoords\n", "Boolean coordsReady\n")),
    Drift(
        "D9 实跑脚本不再断言终态文案（2 处）",
        True,
        (F_E2E, "-ExpectMessageContains $CheckInContract.TaskFinishedMessage `", "-ExpectMessageContains '随便一个文案' `", 2),
    ),
    # D10：把「缺终态任务」这条分支从**记失败**改成**记通过**。
    # 这正是静默跳过的结果形态：结果表里那一行看起来是绿的，读者会以为「验过了」。
    Drift(
        "D10 缺终态任务时把「未覆盖」记成通过",
        True,
        (F_E2E, '"终态 $st 拒签" $false', '"终态 $st 拒签" $true'),
    ),
    Drift(
        "D11 实跑脚本不再消费 deviceHasCoords（2 处属性访问）",
        True,
        (F_E2E, "$checked.deviceHasCoords", "$checked.deviceCoordsFlag", 2),
    ),
    Drift("D12 契约块终态文案漂移成非子串", True, (F_CONTRACT, '= "补货任务已结束"', '= "补货任务已取消"')),
    Drift("D13 契约块终态文案削成碎片", True, (F_CONTRACT, '= "补货任务已结束"', '= "补货"')),
    Drift(
        "D14 「状态未变」断言助手被改名",
        True,
        (F_LIB, "function Assert-E2eTaskStillTerminal {", "function Assert-E2eTerminalNotResurrected {"),
    ),
    Drift(
        "D15 终态拒签不再是 409（2 处）",
        True,
        (F_E2E, "-ExpectStatus 409 `", "-ExpectStatus 400 `", 2),
    ),
    # ── 针对「fail-loud 判据由句子改成结构」的补充验证 ────────────────────────
    # D16：整条 fail-loud 分支被删掉（改成 `continue` 静默跳过）—— 结构没了，门禁必须红。
    Drift(
        "D16 删掉整条 fail-loud 分支（静默 continue）",
        True,
        (
            F_E2E,
            '            Add-Result "终态 $st 拒签" $false `\n'
            '                "找不到任何 $st 的补货任务（已按 ?status=$st 显式查询）—— 本用例**未覆盖**"\n',
            "            continue\n",
        ),
    ),
    # D17（expect_red=False）：只换那句文案、**结构一字不动** —— 门禁必须保持绿。
    # 这一条是**防假红回归守卫**：2026-09-18 的原始判据写成 /库里找不到任何/（在判句子），
    # 重写实跑脚本时句子换了就报了假红。假红和假绿一样有害 —— 它会让人开始不信门禁，
    # 然后「顺手」把判据删掉。所以「换句子必须不红」本身也要有判据守着。
    Drift(
        "D17 只改 fail-loud 文案（结构不变）—— 不得假红",
        False,
        (F_E2E, "—— 本用例**未覆盖**", "—— 本次没有可用的终态样本，请先造一个"),
    ),
    # D18：实跑脚本不再覆盖 CANCELLED 终态（3 处字面量一起改）。
    Drift(
        "D18 实跑脚本不再覆盖 CANCELLED（3 处）",
        True,
        (F_E2E, "'CANCELLED'", "'CANCELED'", 3),
    ),
]


def main() -> int:
    rc, out = run_gate()
    print(f"[基线] RC={rc}  {'绿' if rc == 0 else '红'}")
    if rc != 0:
        print("基线不是绿的，无法做负向验证：\n" + out[-2000:])
        return 1

    paths = sorted({p for c in CASES for p in (pair[0] for pair in c.pairs)})
    backup = {p: p.read_bytes() for p in paths}
    print(f"[备份] {len(paths)} 个文件 / {sum(len(v) for v in backup.values())} 字节\n")

    failures = []
    try:
        for i, case in enumerate(CASES, 1):
            try:
                case.apply(backup)
            except AssertionError as e:
                failures.append(f"{case.name}: {e}")
                print(f"[{i:2d}] {case.name}\n      ANCHOR-MISS：{e}")
                for p in paths:
                    p.write_bytes(backup[p])
                continue
            rc, out = run_gate()
            last = (out.strip().splitlines() or ["(无输出)"])[-1][:170]
            for p in paths:
                p.write_bytes(backup[p])
            restored = all(md5(p.read_bytes()) == md5(backup[p]) for p in paths)
            ok = (rc != 0) == case.expect_red and restored
            if not ok:
                failures.append(f"{case.name}: gate_rc={rc} restored={restored}")
            print(f"[{i:2d}] {'OK ' if ok else '!! '} {case.name}")
            print(f"      gate_rc={rc}（期望{'红' if case.expect_red else '绿'}） restored={restored}")
            print(f"      {last}")
    finally:
        for p in paths:
            p.write_bytes(backup[p])

    all_restored = all(md5(p.read_bytes()) == md5(backup[p]) for p in paths)
    rc, out = run_gate()
    print(f"\n[还原复核] ALL_RESTORED={all_restored}  还原后 RC={rc}（期望 0）")
    if not all_restored:
        failures.append("有文件未逐字节还原")
    if rc != 0:
        failures.append("还原之后门禁仍不是绿的 —— 有注入残留")

    print()
    if failures:
        print(f"结果：{len(CASES) - len(failures)}/{len(CASES)} 按预期，失败项：")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"结果：{len(CASES)}/{len(CASES)} 全部按预期 —— 每条判据都能被真注入打红，逐字节还原，还原后复绿")
    return 0


if __name__ == "__main__":
    sys.exit(main())
