package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.PullOffTaskDto;
import com.aicabinet.trade.domain.PullOffTask;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 临期下架告警分页必须把 deviceId 交给服务端过滤：前端对「已分页的一页」再 filter 时，
 * total 仍是全量，运营从设备详情下钻会看到「显示 N 条 / 共 M 条」不符且翻页大面积空。
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentServiceExpiryPageFilterTest {

    @Mock private PullOffTaskMapper pullOffTaskRepository;

    private ReplenishmentService replenishmentService;

    @BeforeEach
    void setUp() {
        replenishmentService = new ReplenishmentService(
                null, null, null, null, null, null, null, pullOffTaskRepository,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void openPullOffTaskPageHandsDeviceIdToTheServerSideQuery() {
        Page<PullOffTask> empty = new Page<>(1, 20);
        empty.setRecords(List.of());
        empty.setTotal(0);
        when(pullOffTaskRepository.searchPage("OPEN", "DEV-1", 0, 20)).thenReturn(empty);

        PageResult<PullOffTaskDto> result = replenishmentService.listOpenPullOffTasksPage("DEV-1", 0, 20);

        verify(pullOffTaskRepository).searchPage("OPEN", "DEV-1", 0, 20);
        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void openPullOffTaskPageForwardsNullDeviceIdForUnfilteredView() {
        Page<PullOffTask> empty = new Page<>(1, 20);
        empty.setRecords(List.of());
        empty.setTotal(0);
        when(pullOffTaskRepository.searchPage("OPEN", null, 0, 20)).thenReturn(empty);

        replenishmentService.listOpenPullOffTasksPage(null, 0, 20);

        verify(pullOffTaskRepository).searchPage("OPEN", null, 0, 20);
    }
}
