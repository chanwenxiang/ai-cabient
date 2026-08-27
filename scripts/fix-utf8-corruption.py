#!/usr/bin/env python3
"""Repair UTF-8 corruption: clean base (4db7987a) + S6213 renames + post-corruption fixes (aba28cc7..664ee5ec)."""
from __future__ import annotations

import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLEAN_REF = "4db7987a"
CORRUPT_REF = "aba28cc7"
HEAD_REF = "664ee5ec"

CORRUPT_FILES = subprocess.check_output(
    ["git", "diff", "--name-only", CLEAN_REF, CORRUPT_REF, "--", "services/trade-service"],
    cwd=ROOT,
    text=True,
).strip().splitlines()


def apply_s6213_patches(text: str, rel: str) -> str:
    text = text.replace("auditService.record(", "auditService.appendLog(")
    text = text.replace("support.auditService().record(", "support.auditService().appendLog(")
    text = text.replace("verify(auditService).record(", "verify(auditService).appendLog(")
    text = text.replace("verify(audit).record(", "verify(audit).appendLog(")

    if rel.endswith("AdminAuditService.java"):
        text = text.replace("public void record(Long operatorId", "public void appendLog(Long operatorId")
    elif rel.endswith("DeviceEnvService.java"):
        text = text.replace("public void record(String deviceId", "public void saveReading(String deviceId")
    elif rel.endswith("DeviceInternalController.java"):
        text = text.replace("envService.record(", "envService.saveReading(")
    elif rel.endswith("DeviceEnvConcurrencyTest.java") or rel.endswith("DeviceEnvServiceTest.java"):
        text = text.replace("service.record(", "service.saveReading(")
    elif rel.endswith("DataConsistencyService.java"):
        text = text.replace("DataConsistencyRecord record", "DataConsistencyRecord consistencyRecord")
        text = re.sub(r"(?<!\.)\brecord\.", "consistencyRecord.", text)
        text = text.replace("applyFix(record)", "applyFix(consistencyRecord)")
        for fn in (
            "fixOrderAmount", "fixInventoryMismatch", "fixOrderLineSum",
            "fixCouponUsedLink", "fixPaymentAmount",
        ):
            text = text.replace(f"{fn}(record)", f"{fn}(consistencyRecord)")
        text = text.replace("if (record == null)", "if (consistencyRecord == null)")
        text = text.replace("consistencyRepository.save(record)", "consistencyRepository.save(consistencyRecord)")
        text = text.replace("consistencyRecordLockKey", "recordLockKey")
    elif rel.endswith("DataConsistencyServiceTest.java"):
        text = text.replace("DataConsistencyRecord record", "DataConsistencyRecord consistencyRecord")
        text = re.sub(r"(?<!\.)\brecord\.", "consistencyRecord.", text)
        text = text.replace("Optional.of(record)", "Optional.of(consistencyRecord)")
        text = text.replace("save(record)", "save(consistencyRecord)")
    elif rel.endswith("DataConsistencyConcurrencyTest.java"):
        text = text.replace("consistencyRecordLockKey", "recordLockKey")
    elif rel.endswith("NotificationService.java"):
        text = text.replace("NotificationLog record", "NotificationLog logEntry")
        text = re.sub(r"(?<!private record Notification)\brecord\.", "logEntry.", text)
        text = text.replace("logRepository.save(record)", "logRepository.save(logEntry)")
        text = text.replace("return toDto(record)", "return toDto(logEntry)")
    elif rel.endswith("ExternalNotificationDispatcher.java"):
        text = text.replace("NotificationLog record = new NotificationLog();", "NotificationLog logEntry = new NotificationLog();")
        text = text.replace("record.set", "logEntry.set")
        text = text.replace("logRepository.save(record)", "logRepository.save(logEntry)")
    elif rel.endswith("InventoryOpsService.java"):
        text = text.replace("InventoryWriteOff record = new InventoryWriteOff();", "InventoryWriteOff writeOffEntry = new InventoryWriteOff();")
        text = text.replace("record.set", "writeOffEntry.set")
        text = text.replace("record = writeOffRepository.save(record)", "writeOffEntry = writeOffRepository.save(writeOffEntry)")
        text = text.replace("record.get", "writeOffEntry.get")
    elif rel.endswith("ReconciliationService.java"):
        for imp in (
            "import com.aicabinet.trade.reconciliation.PlatformBillProviderRegistry;\n",
            "import com.aicabinet.trade.mapper.PaymentOperationMapper;\n",
            "import com.aicabinet.trade.mapper.PaymentPlatformBillLineMapper;\n",
            "import com.aicabinet.trade.mapper.RechargeOrderMapper;\n",
            "import com.fasterxml.jackson.databind.ObjectMapper;\n",
        ):
            text = text.replace(imp, "")
    elif rel.endswith("MerchantAdminController.java"):
        text = text.replace("import java.util.List;\n", "")
    elif rel.endswith("WarehouseTransferController.java"):
        text = text.replace("\nimport java.util.List;\n", "\n")
    elif rel.endswith("MerchantMapper.java"):
        text = text.replace("import java.util.List;\n", "")

    return text


def git_show_text(ref: str, path: str) -> str:
    data = subprocess.check_output(["git", "show", f"{ref}:{path}"], cwd=ROOT)
    return data.decode("utf-8")


def apply_diff(base_path: Path, path: str) -> bool:
    diff = subprocess.check_output(
        ["git", "diff", CORRUPT_REF, HEAD_REF, "--", path],
        cwd=ROOT,
    )
    if not diff.strip():
        return True
    with tempfile.NamedTemporaryFile(suffix=".patch", delete=False) as tmp:
        tmp.write(diff)
        patch_file = tmp.name
    result = subprocess.run(
        ["git", "apply", "--whitespace=nowarn", patch_file],
        cwd=ROOT,
        capture_output=True,
    )
    Path(patch_file).unlink(missing_ok=True)
    if result.returncode != 0:
        print(f"  patch failed: {path}")
        print(result.stderr.decode("utf-8", errors="replace")[:500])
        return False
    return True


def repair_file(path: str) -> bool:
    target = ROOT / path
    text = git_show_text(CLEAN_REF, path)
    text = apply_s6213_patches(text, path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8", newline="\n")
    return apply_diff(target, path)


def main() -> None:
    ok = 0
    for path in CORRUPT_FILES:
        if not path.strip():
            continue
        print(f"repair {path}")
        if repair_file(path):
            ok += 1
    print(f"done ({ok}/{len(CORRUPT_FILES)} files)")


if __name__ == "__main__":
    main()
