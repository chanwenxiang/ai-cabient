package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.dto.OrderVideoPlaylistDto;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;

/**
 * CB-078 商户端补货作业录像读路径（档 A）。
 *
 * <p>钉两件事：</p>
 * <ol>
 *   <li><b>授权模型没走偏</b>：权限点必须仍是补货页既有的 {@code merchant:replenishment:view}，
 *   而不是运营侧的 {@code ops:device:video}（商户与运营是两个授权主体，混用＝给商户开运营后台权限）；
 *   闸的顺序与同页的「补货凭证」读面 {@code listTaskEvidence} 一致。</li>
 *   <li><b>闸没过就不查录像</b>：签发预签名地址是有代价的外部调用，越权路径必须零触发。</li>
 * </ol>
 */
class MerchantReplenishmentTaskVideosTest {

    private PermissionService permissionService;
    private MerchantFeaturePackService featurePackService;
    private MerchantPortalGuard merchantPortalGuard;
    private ReplenishmentTaskMapper taskRepository;
    private JiangyiOrderVideoService jiangyiOrderVideoService;
    private MerchantReplenishmentService service;

    @BeforeEach
    void setUp() {
        permissionService = Mockito.mock(PermissionService.class);
        featurePackService = Mockito.mock(MerchantFeaturePackService.class);
        merchantPortalGuard = Mockito.mock(MerchantPortalGuard.class);
        taskRepository = Mockito.mock(ReplenishmentTaskMapper.class);
        jiangyiOrderVideoService = Mockito.mock(JiangyiOrderVideoService.class);
        service = new MerchantReplenishmentService(
                permissionService, featurePackService, merchantPortalGuard,
                null, null, null, null, null, null, null,
                taskRepository, null, null, null, null, null,
                jiangyiOrderVideoService);
    }

    private ReplenishmentTask task(long id, String deviceId) {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(id);
        task.setDeviceId(deviceId);
        return task;
    }

    @Test
    void scopedTask_returnsSignedPlaylistAndUsesReplenishmentViewPermission() {
        Mockito.when(taskRepository.findById(19L)).thenReturn(Optional.of(task(19L, "CAB-1")));
        Mockito.when(jiangyiOrderVideoService.playByTask(19L)).thenReturn(List.of(
                new JiangyiOrderVideoService.PlayUrlView(45L, "STK-1", "CAB-1", 1, 2,
                        java.time.Instant.parse("2026-10-31T02:00:00Z"),
                        List.of(new JiangyiOrderVideoService.PlayUrlItem(
                                1, "https://signed/left.mp4", true, true, null)))));

        OrderVideoPlaylistDto dto = service.listTaskVideos(1001L, 19L);

        assertEquals(OrderVideoPlaylistDto.SOURCE_JIANGYI, dto.source());
        assertEquals(1, dto.clips().size());
        assertEquals("https://signed/left.mp4", dto.clips().get(0).url());
        // 🔴 权限点字面量：复用补货页读权限，不借运营侧视频权限
        ArgumentCaptor<String> perm = ArgumentCaptor.forClass(String.class);
        Mockito.verify(permissionService).requirePermission(eq(1001L), perm.capture());
        assertEquals("merchant:replenishment:view", perm.getValue());
        Mockito.verify(merchantPortalGuard).requireAccess(1001L);
        Mockito.verify(featurePackService).requireDevicePack(eq(1001L), eq("CAB-1"), anyString());
    }

    @Test
    void missingTask_is404AndNeverQueriesVideos() {
        Mockito.when(taskRepository.findById(404L)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listTaskVideos(1001L, 404L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        Mockito.verify(jiangyiOrderVideoService, Mockito.never()).playByTask(Mockito.any());
    }

    @Test
    void taskOutsideDevicePack_isRejectedBeforeQueryingVideos() {
        Mockito.when(taskRepository.findById(19L)).thenReturn(Optional.of(task(19L, "CAB-OTHER")));
        Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "该设备未开通功能包"))
                .when(featurePackService).requireDevicePack(eq(1001L), eq("CAB-OTHER"), anyString());

        assertThrows(ResponseStatusException.class, () -> service.listTaskVideos(1001L, 19L));

        Mockito.verify(jiangyiOrderVideoService, Mockito.never()).playByTask(Mockito.any());
    }

    @Test
    void noReplenishmentViewPermission_isRejectedBeforeLoadingTask() {
        Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限"))
                .when(permissionService).requirePermission(1001L, "merchant:replenishment:view");

        assertThrows(ResponseStatusException.class, () -> service.listTaskVideos(1001L, 19L));

        Mockito.verify(taskRepository, Mockito.never()).findById(Mockito.any());
        Mockito.verify(jiangyiOrderVideoService, Mockito.never()).playByTask(Mockito.any());
    }

    @Test
    void taskWithoutRestockVideo_returnsNoneSourceNotAnError() {
        // 商户侧语义：这台柜子的这次补货没有录像＝空清单，前端据此显示「暂无录像」，不是 404
        Mockito.when(taskRepository.findById(19L)).thenReturn(Optional.of(task(19L, "CAB-1")));
        Mockito.when(jiangyiOrderVideoService.playByTask(19L)).thenReturn(List.of());

        OrderVideoPlaylistDto dto = service.listTaskVideos(1001L, 19L);

        assertEquals(OrderVideoPlaylistDto.SOURCE_NONE, dto.source());
        assertEquals(0, dto.clips().size());
    }
}
