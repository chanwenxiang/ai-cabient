package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §7.10 轮询调度：只刷审核中的行、用批量接口、失败不重抛。 */
@ExtendWith(MockitoExtension.class)
class JiangyiProductStatePollSchedulerTest {

    @Mock private JiangyiSkuJiangyiLinkMapper linkMapper;
    @Mock private JiangyiProductStateService productStateService;
    @Mock private ScheduledTaskService taskService;

    private JiangyiProductStatePollScheduler scheduler() {
        return new JiangyiProductStatePollScheduler(linkMapper, productStateService, taskService);
    }

    private JiangyiSkuJiangyiLink link(String barCode) {
        JiangyiSkuJiangyiLink l = new JiangyiSkuJiangyiLink();
        l.setBarCode(barCode);
        l.setJiangyiState("REVIEWING");
        return l;
    }

    @Test
    void pollsDistinctBarCodesOfPendingRows() {
        when(taskService.tryBegin(eq("jiangyi-product-state-poll"), anyLong())).thenReturn(true);
        when(linkMapper.listPollable(100)).thenReturn(List.of(link("A"), link("B"), link("A"), link(" ")));
        when(productStateService.refresh(List.of("A", "B"))).thenReturn(2);

        scheduler().pollReviewingProducts();

        verify(productStateService).refresh(List.of("A", "B"));
        verify(taskService).finish(eq("jiangyi-product-state-poll"), eq("SUCCESS"), anyString(), anyLong());
    }

    @Test
    void noPendingRowsSkipsVendor() {
        when(taskService.tryBegin(eq("jiangyi-product-state-poll"), anyLong())).thenReturn(true);
        when(linkMapper.listPollable(100)).thenReturn(List.of());

        scheduler().pollReviewingProducts();

        verify(productStateService, never()).refresh(anyList());
        verify(taskService).finish(eq("jiangyi-product-state-poll"), eq("SUCCESS"), anyString(), anyLong());
    }

    @Test
    void vendorGateClosedRecordsFailedWithoutRethrow() {
        when(taskService.tryBegin(eq("jiangyi-product-state-poll"), anyLong())).thenReturn(true);
        when(linkMapper.listPollable(100)).thenReturn(List.of(link("A")));
        when(productStateService.refresh(anyList())).thenThrow(new IllegalStateException("503 商户没有 _ID-OK 设备"));

        scheduler().pollReviewingProducts();

        verify(taskService).finish(eq("jiangyi-product-state-poll"), eq("FAILED"), anyString(), anyLong());
    }
}