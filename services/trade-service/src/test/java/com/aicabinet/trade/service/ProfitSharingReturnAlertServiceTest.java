package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CompensationTask;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ProfitSharingReturnAlertServiceTest {

    @Mock private OpsAlertDispatcher opsAlertDispatcher;
    @Mock private OpsExceptionService opsExceptionService;

    @InjectMocks
    private ProfitSharingReturnAlertService alertService;

    @Test
    void sendCompensationExhausted_shouldReportExceptionAndDispatchAlert() {
        CompensationTask task = new CompensationTask();
        task.setTaskId(42L);
        task.setRetryCount(5);

        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-1");
        split.setOrderId("O-1");
        split.setMerchantId("M-1");
        split.setDeviceId("D-1");
        split.setWechatPendingReturnNo("PSR-1");
        split.setWechatPendingReturnCents(880L);
        split.setFailureReason("分账回退未成功");

        alertService.sendCompensationExhausted(task, split);

        verify(opsExceptionService).report(
                eq("SPLIT_EXCEPTION"),
                eq("HIGH"),
                eq("D-1"),
                eq(null),
                eq("O-1"),
                eq(null),
                eq("[分账回退补偿失败]"),
                org.mockito.ArgumentMatchers.contains("SPLIT-1"));
        verify(opsAlertDispatcher).send(
                eq("PROFIT_SHARING_RETURN_FAILED"),
                eq("[分账回退补偿失败]"),
                org.mockito.ArgumentMatchers.contains("O-1"),
                anyMap());
    }

    @Test
    void sendManualSupplementRequired_shouldReportExceptionAndDispatchAlert() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-2");
        split.setOrderId("O-2");
        split.setMerchantId("M-2");
        split.setDeviceId("D-2");
        split.setStatus("WECHAT_SUBMITTED");

        alertService.sendManualSupplementRequired(split, 360L, 900L);

        verify(opsExceptionService).report(
                eq("SPLIT_EXCEPTION"),
                eq("HIGH"),
                eq("D-2"),
                eq(null),
                eq("O-2"),
                eq(null),
                eq("[分账需人工补分账]"),
                org.mockito.ArgumentMatchers.contains("540"));
        verify(opsAlertDispatcher).send(
                eq("PROFIT_SHARING_MANUAL_SUPPLEMENT"),
                eq("[分账需人工补分账]"),
                org.mockito.ArgumentMatchers.contains("O-2"),
                anyMap());
    }

    @Test
    void sendReturnSubmitFailed_shouldReportExceptionAndDispatchAlert() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-R");
        split.setOrderId("O-R");
        split.setMerchantId("M-R");
        split.setDeviceId("D-R");

        alertService.sendReturnSubmitFailed(split, "PSR-R", 360L, "渠道拒绝");

        verify(opsExceptionService).report(
                eq("SPLIT_EXCEPTION"),
                eq("HIGH"),
                eq("D-R"),
                eq(null),
                eq("O-R"),
                eq(null),
                eq("[分账回退提交失败]"),
                org.mockito.ArgumentMatchers.contains("PSR-R"));
        verify(opsAlertDispatcher).send(
                eq("PROFIT_SHARING_RETURN_SUBMIT_FAILED"),
                eq("[分账回退提交失败]"),
                org.mockito.ArgumentMatchers.contains("O-R"),
                anyMap());
    }

    @Test
    void sendManualSupplementRequired_skipsWhenNoDelta() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-3");
        split.setOrderId("O-3");

        alertService.sendManualSupplementRequired(split, 900L, 900L);

        verifyNoInteractions(opsExceptionService, opsAlertDispatcher);
    }
}
