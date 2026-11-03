package com.aicabinet.trade.api;

import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.StaffWalletService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA-074：补货员钱包「标记线下打款出账」是真金出账的销账动作，此前全类零留痕、
 * 也没取操作者（同族 ADJUST/PAYOUT 都有台账）——出错了查不到是谁标的。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpsStaffWalletPayoutAuditTest {

    @Mock private StaffWalletService staffWalletService;
    @Mock private AdminAuditService auditService;
    @Mock private HttpServletRequest request;

    private OpsStaffWalletController controller;

    @BeforeEach
    void setUp() {
        controller = new OpsStaffWalletController(staffWalletService, auditService);
        when(request.getAttribute(anyString())).thenReturn(9001L);
    }

    private Map<String, Object> body() {
        Map<String, Object> m = new HashMap<>();
        m.put("amountCents", 3000);
        m.put("requestNo", "REQ-1");
        return m;
    }

    @Test
    void payoutApplied_leavesAuditTrailWithOperatorAndIdempotencyKey() {
        when(staffWalletService.payoutIfAbsent(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(true);

        controller.payout(7781L, body(), request);

        verify(auditService).appendLog(eq(9001L), eq("STAFF_WALLET_PAYOUT"), eq("STAFF_WALLET"),
                eq("7781"), contains("requestNo=REQ-1"));
    }

    @Test
    void payoutIdempotentHit_doesNotLogAgain() {
        // 幂等命中说明这笔出账在首次标记时已记过；再记一条会在审计里凭空多出一次出款
        when(staffWalletService.payoutIfAbsent(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(false);

        controller.payout(7781L, body(), request);

        verify(auditService, never()).appendLog(any(), anyString(), anyString(), anyString(), anyString());
    }
}
