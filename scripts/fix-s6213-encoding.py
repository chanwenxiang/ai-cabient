#!/usr/bin/env python3
"""Restore S6213-touched files from pre-corruption commit and re-apply renames safely."""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRADE = ROOT / "services" / "trade-service"
RESTORE_REF = "4db7987a"

FILES = subprocess.check_output(
    ["git", "diff", "--name-only", RESTORE_REF, "aba28cc7", "--", "services/trade-service"],
    cwd=ROOT,
    text=True,
).strip().splitlines()


def restore(path: str) -> None:
    content = subprocess.check_output(["git", "show", f"{RESTORE_REF}:{path}"], cwd=ROOT)
    target = ROOT / path
    target.write_bytes(content)


def rename_audit(text: str) -> str:
    repls = [
        ("auditService.record(", "auditService.appendLog("),
        ("support.auditService().record(", "support.auditService().appendLog("),
        ("verify(auditService).record(", "verify(auditService).appendLog("),
        ("verify(audit).record(", "verify(audit).appendLog("),
    ]
    for old, new in repls:
        text = text.replace(old, new)
    return text


def patch_file(path: Path, text: str) -> str:
    rel = path.as_posix()
    text = rename_audit(text)

    if rel.endswith("AdminAuditService.java"):
        text = text.replace(
            "public void record(Long operatorId",
            "public void appendLog(Long operatorId",
        )
    elif rel.endswith("DeviceEnvService.java"):
        text = text.replace(
            "public void record(String deviceId",
            "public void saveReading(String deviceId",
        )
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


def main() -> None:
    for path in FILES:
        if not path.strip():
            continue
        print(f"restore {path}")
        restore(path)
        target = ROOT / path
        text = target.read_text(encoding="utf-8")
        text = patch_file(target, text)
        target.write_text(text, encoding="utf-8", newline="\n")
    print(f"done ({len(FILES)} files)")


if __name__ == "__main__":
    main()
