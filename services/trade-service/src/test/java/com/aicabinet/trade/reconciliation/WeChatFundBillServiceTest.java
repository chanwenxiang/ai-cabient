package com.aicabinet.trade.reconciliation;

import com.aicabinet.trade.payment.WeChatPayV3Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeChatFundBillServiceTest {

    @Mock private WeChatPayV3Client client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void missingDownloadUrl_returnsExplicitEmptyBillInsteadOfFailingReconciliation() {
        LocalDate date = LocalDate.of(2026, 11, 24);
        String path = "/v3/bill/fundflowbill?bill_date=" + date + "&account_type=OPERATION";
        when(client.getWithStatus(path))
                .thenReturn(new WeChatPayV3Client.HttpResult(200, objectMapper.createObjectNode()));
        WeChatFundBillService service = new WeChatFundBillService(client);

        WeChatFundBillCsvParser.FundBill bill = service.fetchOperatingAccount(date);

        assertEquals(0, bill.lines().size());
        assertNull(bill.endingBalanceCents());
        verify(client, never()).download(anyString(), any(), any());
    }

    @Test
    void noStatementExist_returnsExplicitEmptyBill() {
        LocalDate date = LocalDate.of(2026, 11, 24);
        String path = "/v3/bill/fundflowbill?bill_date=" + date + "&account_type=OPERATION";
        when(client.getWithStatus(path)).thenReturn(new WeChatPayV3Client.HttpResult(200,
                objectMapper.createObjectNode().put("code", "NO_STATEMENT_EXIST")));

        WeChatFundBillCsvParser.FundBill bill = new WeChatFundBillService(client).fetchOperatingAccount(date);

        assertEquals(0, bill.lines().size());
        assertNull(bill.endingBalanceCents());
        verify(client, never()).download(anyString(), any(), any());
    }

    @Test
    void notFound_returnsExplicitEmptyBill() {
        LocalDate date = LocalDate.of(2026, 11, 24);
        String path = "/v3/bill/fundflowbill?bill_date=" + date + "&account_type=OPERATION";
        when(client.getWithStatus(path)).thenReturn(new WeChatPayV3Client.HttpResult(404,
                objectMapper.createObjectNode().put("code", "NOT_FOUND")));

        WeChatFundBillCsvParser.FundBill bill = new WeChatFundBillService(client).fetchOperatingAccount(date);

        assertEquals(0, bill.lines().size());
        assertNull(bill.endingBalanceCents());
    }

    @Test
    void otherNon2xx_stillFailsInsteadOfLookingLikeEmptyBill() {
        LocalDate date = LocalDate.of(2026, 11, 24);
        String path = "/v3/bill/fundflowbill?bill_date=" + date + "&account_type=OPERATION";
        when(client.getWithStatus(path)).thenReturn(new WeChatPayV3Client.HttpResult(401,
                objectMapper.createObjectNode().put("code", "NO_AUTH").put("message", "denied")));
        WeChatFundBillService service = new WeChatFundBillService(client);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.fetchOperatingAccount(date));

        assertTrue(error.getMessage().contains("status=401"));
        assertTrue(error.getMessage().contains("NO_AUTH"));
        verify(client, never()).download(anyString(), any(), any());
    }
}
