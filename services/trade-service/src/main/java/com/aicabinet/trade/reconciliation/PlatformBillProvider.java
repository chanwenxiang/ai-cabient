package com.aicabinet.trade.reconciliation;

import java.time.LocalDate;
import java.util.List;

public interface PlatformBillProvider {
    String channel();

    List<PlatformBillLine> fetchDailyBill(LocalDate date);
}
