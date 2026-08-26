#!/usr/bin/env python3
"""Apply java:S6809 fixes: @Lazy self + call-site rewrites."""
from __future__ import annotations

import re
import urllib.request
import base64
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVICE_DIR = ROOT / "services/trade-service/src/main/java/com/aicabinet/trade/service"
SELF_COMMENT = "    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */"

# file -> list of (old_fragment, new_fragment) unique replacements
CALL_FIXES: dict[str, list[tuple[str, str]]] = {
    "RefundPolicyService.java": [
        ("return globalDefault();", "return self.globalDefault();"),
        ("return resolveForDevice(deviceId) == RefundPolicy.AUTO_REFUND;",
         "return self.resolveForDevice(deviceId) == RefundPolicy.AUTO_REFUND;"),
        ("if (!allowsAutoRefund(order.getDeviceId())) {",
         "if (!self.allowsAutoRefund(order.getDeviceId())) {"),
        ("if (partial && !allowsConsumerPartialRefund()) {",
         "if (partial && !self.allowsConsumerPartialRefund()) {"),
    ],
    "InventoryService.java": [
        ("return deductForOrder(deviceId, items, null, null);",
         "return self.deductForOrder(deviceId, items, null, null);"),
        ("return deductForOrder(deviceId, items, refId, null);",
         "return self.deductForOrder(deviceId, items, refId, null);"),
        ("return adjustForOrder(deviceId, oldItems, newItems, Map.of());",
         "return self.adjustForOrder(deviceId, oldItems, newItems, Map.of());"),
    ],
    "OpsExceptionService.java": [
        ("return list(operatorId, status, severity, overdueOnly, null, page, size);",
         "return self.list(operatorId, status, severity, overdueOnly, null, page, size);"),
        ("return list(operatorId, status, severity, false, page, size);",
         "return self.list(operatorId, status, severity, false, page, size);"),
        ("return list(operatorId, status, null, false, page, size);",
         "return self.list(operatorId, status, null, false, page, size);"),
    ],
    "DisputeService.java": [
        ("return createTicket(session,", "return self.createTicket(session,"),
        ("return listTickets(operatorId, page, size, status, sessionId, deviceId, null, category, reviewCode);",
         "return self.listTickets(operatorId, page, size, status, sessionId, deviceId, null, category, reviewCode);"),
        ("return getMerchantDetail(userId, ticketId);",
         "return self.getMerchantDetail(userId, ticketId);"),
    ],
    "MerchantNotifyService.java": [
        ("return getPrefs(userId);", "return self.getPrefs(userId);"),
        ("if (maybeNotifyUser(userId)) {", "if (self.maybeNotifyUser(userId)) {"),
    ],
    "OpsRbacService.java": [
        ("return getRolePermissions(operatorId, roleId);",
         "return self.getRolePermissions(operatorId, roleId);"),
    ],
    "SessionService.java": [
        ("return finishRestockSnapshot(session.getSessionId());",
         "return self.finishRestockSnapshot(session.getSessionId());"),
        ("getSession(userId, sessionId);", "self.getSession(userId, sessionId);"),
    ],
    "SlaMetricsService.java": [
        ("SlaRealtimeDto realtime = realtimeMetrics(operatorId);",
         "SlaRealtimeDto realtime = self.realtimeMetrics(operatorId);"),
        ("SlaDailySnapshot snap = buildSnapshot(yesterday);",
         "SlaDailySnapshot snap = self.buildSnapshot(yesterday);"),
    ],
    "SettlementService.java": [
        ("return processRecognitionResult(session, recognition, true);",
         "return self.processRecognitionResult(session, recognition, true);"),
        ("return waiveAndRefund(session, true);", "return self.waiveAndRefund(session, true);"),
    ],
    "MerchantPortalService.java": [
        ("List<MerchantDailySettlementDto> days = listDailySettlements(userId, fromDate, toDate);",
         "List<MerchantDailySettlementDto> days = self.listDailySettlements(userId, fromDate, toDate);"),
        ("for (MerchantDeviceReportDto r : deviceReports(userId)) {",
         "for (MerchantDeviceReportDto r : self.deviceReports(userId)) {"),
    ],
    "PromotionService.java": [
        ('return updateStatus(activityId, "ACTIVE");',
         'return self.updateStatus(activityId, "ACTIVE");'),
        ('return updateStatus(activityId, "STOPPED");',
         'return self.updateStatus(activityId, "STOPPED");'),
    ],
    "PointsExpiryScheduler.java": [
        ("int reminded = remind(7);", "int reminded = self.remind(7);"),
        ("int expired = expire();", "int expired = self.expire();"),
    ],
    "NotificationService.java": [
        ('send("CONSUMER", userId, null, templateCode, params, bizType, bizId);',
         'self.send("CONSUMER", userId, null, templateCode, params, bizType, bizId);'),
        ('send("MERCHANT", null, merchantId, templateCode, params, bizType, bizId);',
         'self.send("MERCHANT", null, merchantId, templateCode, params, bizType, bizId);'),
    ],
    "ReplenishmentService.java": [
        ("return seedDraftRestockLines(taskId, task.getDeviceId(), skuQty);",
         "return self.seedDraftRestockLines(taskId, task.getDeviceId(), skuQty);"),
        ("return listTaskLines(taskId);", "return self.listTaskLines(taskId);"),
    ],
    "DeviceAvailabilityKpiService.java": [
        ("return snapshotDaily(LocalDate.now(ZONE).minusDays(1));",
         "return self.snapshotDaily(LocalDate.now(ZONE).minusDays(1));"),
        ("return getByDate(date);", "return self.getByDate(date);"),
    ],
    "CouponExpiryReminderScheduler.java": [
        ("int reminded = remind(3);", "int reminded = self.remind(3);"),
    ],
    "DeviceAssetService.java": [
        ("return stockHealth(operatorId, dimension, merchantId, routeCode, lifecycleStatus, null);",
         "return self.stockHealth(operatorId, dimension, merchantId, routeCode, lifecycleStatus, null);"),
        ("List<StockHealthRowDto> all = stockHealth(",
         "List<StockHealthRowDto> all = self.stockHealth("),
    ],
    "RepairTicketService.java": [
        ("return transition(operatorId, ticketId, toStatus, remark, false);",
         "return self.transition(operatorId, ticketId, toStatus, remark, false);"),
    ],
    "RecognitionTestService.java": [
        ("return previewUpload(imageBytes, filename, null);",
         "return self.previewUpload(imageBytes, filename, null);"),
    ],
    "CouponService.java": [],  # already fixed
    "AuthService.java": [],
    "ApprovalWorkflowService.java": [],
    "CompetitiveGapService.java": [],
    "SkuDelistReviewService.java": [
        ("return list();", "return self.list();"),
    ],
    "SkuVisionEnrollmentService.java": [
        ("DeviceVisionContextDto ctx = deviceVisionContext(deviceId);",
         "DeviceVisionContextDto ctx = self.deviceVisionContext(deviceId);"),
    ],
    "SiteRentSplitService.java": [
        ("return listByContract(operatorId, contractId);",
         "return self.listByContract(operatorId, contractId);"),
    ],
    "MerchantFinanceService.java": [
        ("List<MerchantDailySettlementDto> days = listDailySettlements(userId, fromDate, toDate);",
         "List<MerchantDailySettlementDto> days = self.listDailySettlements(userId, fromDate, toDate);"),
    ],
    "SalesVelocityService.java": [
        ("return velocityBySku(deviceId).getOrDefault(skuId, new SkuVelocity(0, 0, 0, 0));",
         "return self.velocityBySku(deviceId).getOrDefault(skuId, new SkuVelocity(0, 0, 0, 0));"),
    ],
    "LineManagerService.java": [
        ("return ledgersForManager(managerId, limit);",
         "return self.ledgersForManager(managerId, limit);"),
    ],
    "MemberService.java": [
        ("earnPoints(member, paidAmountCents, orderId);",
         "self.earnPoints(member, paidAmountCents, orderId);"),
    ],
    "MerchantAnalyticsService.java": [
        ("return competitiveGapService.salesReportCsv(salesReports(userId, dim, fromDate, toDate));",
         "return competitiveGapService.salesReportCsv(self.salesReports(userId, dim, fromDate, toDate));"),
    ],
    "FundBillService.java": [
        ("for (FundDailyBillDto d : listDailyBills(operatorId, fromDate, toDate)) {",
         "for (FundDailyBillDto d : self.listDailyBills(operatorId, fromDate, toDate)) {"),
    ],
    "IdempotencyService.java": [
        ("saveIdempotency(idempotencyKey, businessType, businessId, null);",
         "self.saveIdempotency(idempotencyKey, businessType, businessId, null);"),
    ],
    "FinanceReportService.java": [
        ("FinanceStatsDto summary = stats(operatorId);",
         "FinanceStatsDto summary = self.stats(operatorId);"),
    ],
    "DeviceTempPlanService.java": [
        ("applyNow(plan.getDeviceId());", "self.applyNow(plan.getDeviceId());"),
    ],
    "DemoDataService.java": [
        ("String fallback = resolveFallbackSku(DEMO_DEVICE_ID);",
         "String fallback = self.resolveFallbackSku(DEMO_DEVICE_ID);"),
    ],
    "DataConsistencyService.java": [
        ("return fixInconsistencyDetailed(recordId).fixed();",
         "return self.fixInconsistencyDetailed(recordId).fixed();"),
    ],
    "OpsService.java": [
        ("return openDoorForRestockAsUser(operatorUserId, request.deviceId(), request.taskId());",
         "return self.openDoorForRestockAsUser(operatorUserId, request.deviceId(), request.taskId());"),
    ],
    "MerchantSelfServiceGate.java": [
        ("requirePlanogramEdit(userId, deviceId);", "self.requirePlanogramEdit(userId, deviceId);"),
    ],
    "MerchantSkuPricingService.java": [
        ("resolveUnitPriceCents(deviceId, sku),",
         "self.resolveUnitPriceCents(deviceId, sku),"),
    ],
    "DepartmentService.java": [
        ("return members(operatorId, dept.getDeptId());",
         "return self.members(operatorId, dept.getDeptId());"),
    ],
}

# OpsRbacService has 3 issues - need to find other 2 from Sonar
# Let me add more OpsRbac fixes after reading file


def load_token() -> str:
    for line in (ROOT / "infra/.env").read_text(encoding="utf-8").splitlines():
        if line.startswith("SONAR_TOKEN="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("SONAR_TOKEN not found")


def fetch_extra_ops_rbac_fixes() -> list[tuple[str, str]]:
    token = load_token()
    auth = base64.b64encode(f"{token}:".encode()).decode()
    url = (
        "http://localhost:19002/api/issues/search?"
        "componentKeys=ai-cabinet-dev&rules=java:S6809&statuses=OPEN&branch=dev&ps=500"
    )
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {auth}"})
    with urllib.request.urlopen(req) as resp:
        import json

        data = json.load(resp)
    fixes = []
    path = SERVICE_DIR / "OpsRbacService.java"
    lines = path.read_text(encoding="utf-8").splitlines()
    for issue in data["issues"]:
        comp = issue["component"].split(":")[-1]
        if not comp.endswith("OpsRbacService.java"):
            continue
        ln = issue.get("line")
        if not ln or ln > len(lines):
            continue
        old = lines[ln - 1].strip()
        if old and "self." not in old:
            # generic: prefix first method call on line
            new_line = lines[ln - 1]
            for m in re.finditer(r"(?<![.\w])(\w+)\s*\(", new_line):
                name = m.group(1)
                if name in {"if", "for", "while", "return", "new", "throw", "catch"}:
                    continue
                new_line = new_line[: m.start(1)] + "self." + name + new_line[m.end(1) :]
                fixes.append((lines[ln - 1], new_line))
                break
    return fixes


def class_name(content: str) -> str:
    m = re.search(r"public\s+class\s+(\w+)", content)
    if not m:
        raise ValueError("no class")
    return m.group(1)


def has_self_field(content: str, cn: str) -> bool:
    return f"private final {cn} self;" in content or f"private {cn} self;" in content


def add_constructor_self(content: str, cn: str) -> str:
    if has_self_field(content, cn):
        return content
    lines = content.splitlines()
    out: list[str] = []
    lazy_added = False
    field_added = False
    in_ctor = False
    depth = 0
    ctor_need_close_assign = False

    for i, line in enumerate(lines):
        if not lazy_added and line.startswith("import org.springframework.stereotype."):
            out.append("import org.springframework.context.annotation.Lazy;")
            lazy_added = True
        if not lazy_added and line.startswith("import org.springframework.context.annotation.Lazy;"):
            lazy_added = True

        if not field_added and re.match(r"\s+private\s+final\s+\w+", line) and " self;" not in line:
            out.append(line)
            nxt = lines[i + 1] if i + 1 < len(lines) else ""
            if not re.match(r"\s+private\s+final\s+\w+", nxt):
                out.append(SELF_COMMENT)
                out.append(f"    private final {cn} self;")
                field_added = True
            continue

        if re.search(rf"public\s+{cn}\s*\(", line):
            in_ctor = True
            depth += line.count("(") - line.count(")")
            if depth <= 0 and ") {" in line:
                line = line.replace(") {", f", @Lazy {cn} self) {{", 1)
                in_ctor = False
                ctor_need_close_assign = True
            out.append(line)
            continue

        if in_ctor:
            depth += line.count("(") - line.count(")")
            if depth <= 0 and ") {" in line:
                line = line.replace(") {", f", @Lazy {cn} self) {{", 1)
                in_ctor = False
                ctor_need_close_assign = True
            out.append(line)
            continue

        if ctor_need_close_assign and re.match(r"\s+this\.\w+\s*=", line):
            out.append(line)
            if i + 1 < len(lines) and lines[i + 1].strip() == "}":
                out.append("        this.self = self;")
                ctor_need_close_assign = False
            continue

        if ctor_need_close_assign and line.strip() == "}":
            out.append("        this.self = self;")
            ctor_need_close_assign = False

        out.append(line)

    if not field_added:
        # @Autowired field injection fallback
        if "@Autowired" in content:
            text = content
            if not lazy_added:
                text = text.replace(
                    "import org.springframework.beans.factory.annotation.Autowired;",
                    "import org.springframework.beans.factory.annotation.Autowired;\nimport org.springframework.context.annotation.Lazy;",
                    1,
                )
            if not has_self_field(text, cn):
                insert_after = text.rfind("@Autowired")
                line_end = text.find("\n", insert_after)
                while line_end != -1:
                    nxt = text.find("\n", line_end + 1)
                    chunk = text[line_end + 1 : nxt if nxt != -1 else len(text)]
                    if chunk.strip() and not chunk.strip().startswith("@"):
                        break
                    line_end = nxt
                injection = (
                    f"\n\n    @Autowired\n    @Lazy\n    private {cn} self;"
                )
                text = text[: line_end + 1] + injection + text[line_end + 1 :]
            return text
        raise ValueError(f"Failed to add self for {cn}")

    return "\n".join(out) + ("\n" if content.endswith("\n") else "")


def fix_file(name: str, replacements: list[tuple[str, str]]) -> bool:
    path = SERVICE_DIR / name
    if not path.exists():
        print(f"SKIP missing {name}")
        return False
    text = path.read_text(encoding="utf-8")
    cn = class_name(text)
    text = re.sub(
        r"/\*\* 缁\?Spring.*?\*/",
        SELF_COMMENT,
        text,
    )
    if not has_self_field(text, cn) and cn != "SessionService":
        text = add_constructor_self(text, cn)
    changed = False
    for old, new in replacements:
        if old in text and new not in text:
            text = text.replace(old, new, 1)
            changed = True
        elif old not in text and new in text:
            pass
        elif old not in text:
            print(f"  WARN {name}: fragment not found: {old[:60]}...")
    if text != path.read_text(encoding="utf-8"):
        path.write_text(text, encoding="utf-8", newline="\n")
        print(f"FIXED {name}")
        return True
    if changed:
        path.write_text(text, encoding="utf-8", newline="\n")
        print(f"FIXED {name}")
        return True
    print(f"UNCHANGED {name}")
    return False


def main() -> None:
    extra = fetch_extra_ops_rbac_fixes()
    ops = CALL_FIXES.setdefault("OpsRbacService.java", [])
    for old, new in extra:
        if (old, new) not in ops:
            ops.append((old, new))

    fixed = 0
    for name, reps in CALL_FIXES.items():
        if fix_file(name, reps):
            fixed += 1
    print(f"\nDone. Updated {fixed} files.")


if __name__ == "__main__":
    main()
