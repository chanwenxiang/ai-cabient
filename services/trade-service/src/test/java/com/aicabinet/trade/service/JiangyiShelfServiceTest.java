package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.dto.JiangyiGatherDtos.TrainedProduct;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 跨柜一键上架的服务侧契约：识别名只认厂商回包、前置校验整批 fail-fast、逐柜部分成功。
 */
@ExtendWith(MockitoExtension.class)
class JiangyiShelfServiceTest {

    private static final String PID = "41175";
    private static final String TEXT_NAME = "雪碧500ml";
    private static final String SKU_ID = "SKU-100017";
    private static final String DEV_A = "100000000001";
    private static final String DEV_B = "100000000002";

    @Mock private JiangyiGatherClient jiangyiGatherClient;
    @Mock private JiangyiSkuJiangyiLinkMapper linkMapper;
    @Mock private SkuCatalogMapper skuMapper;
    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private JiangyiShelfWriter shelfWriter;
    @Mock private PermissionService permissionService;

    private DeviceInfo info(String deviceId, String name) {
        DeviceInfo i = new DeviceInfo();
        i.setDeviceId(deviceId);
        i.setDeviceName(name);
        return i;
    }

    private JiangyiShelfService service() {
        return new JiangyiShelfService(jiangyiGatherClient, linkMapper, skuMapper,
                deviceInfoMapper, shelfWriter, permissionService);
    }

    private TrainedProduct trained(String textName) {
        return new TrainedProduct(41175L, "雪碧 500ml 柠檬味", textName, "6928804011169");
    }

    private JiangyiSkuJiangyiLink link(String syncStatus) {
        JiangyiSkuJiangyiLink l = new JiangyiSkuJiangyiLink();
        l.setSkuId(SKU_ID);
        l.setJiangyiProductId(PID);
        l.setSyncStatus(syncStatus);
        return l;
    }

    private SkuCatalog sku(String status, int basePriceCents) {
        SkuCatalog s = new SkuCatalog();
        s.setSkuId(SKU_ID);
        s.setSkuName("雪碧 500ml");
        s.setStatus(status);
        s.setPriceCents(basePriceCents);
        return s;
    }

    private void happyPreconditions() {
        when(jiangyiGatherClient.trainedProducts()).thenReturn(List.of(trained(TEXT_NAME)));
        when(linkMapper.byProduct(PID)).thenReturn(Optional.of(link("BOUND")));
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku("ACTIVE", 350));
    }

    @Test
    void shelve_delegatesPerDeviceAndReportsOutcomes() {
        happyPreconditions();
        when(deviceInfoMapper.findByDeviceIdIn(List.of(DEV_A, DEV_B)))
                .thenReturn(List.of(info(DEV_A, "1号柜"), info(DEV_B, "2号柜")));
        when(shelfWriter.shelveOne(9L, DEV_A, SKU_ID, TEXT_NAME, null, null))
                .thenReturn(new JiangyiShelfWriter.WriterOutcome(
                        JiangyiShelfWriter.STATUS_SHELVED, 29, null, null, List.of("识别编号 29")));
        when(shelfWriter.shelveOne(9L, DEV_B, SKU_ID, TEXT_NAME, null, null))
                .thenReturn(new JiangyiShelfWriter.WriterOutcome(
                        JiangyiShelfWriter.STATUS_ALREADY, 31, null, null, List.of("已上架")));

        JiangyiShelfService.ShelfResult result =
                service().shelve(9L, " " + PID + " ", List.of(DEV_A, DEV_B), null, null);

        assertEquals(PID, result.jiangyiProductId());
        assertEquals(SKU_ID, result.skuId());
        assertEquals(TEXT_NAME, result.textName());
        assertEquals(2, result.requestedDevices());
        assertEquals("SHELVED", result.devices().get(0).status());
        assertEquals("1号柜", result.devices().get(0).deviceName());
        assertEquals(29, result.devices().get(0).classId());
        assertEquals("ALREADY", result.devices().get(1).status());
        assertEquals("识别编号 29", result.devices().get(0).message());
    }

    @Test
    void shelve_vendorReadFailureIsBadGateway() {
        when(jiangyiGatherClient.trainedProducts()).thenThrow(new IllegalStateException("503 设备不在线"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().shelve(9L, PID, List.of(DEV_A), null, null));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());
        assertTrue(e.getReason().contains("503 设备不在线"));
        verifyNoInteractions(shelfWriter);
    }

    @Test
    void shelve_productNotInTrainedListIsConflict() {
        when(jiangyiGatherClient.trainedProducts()).thenReturn(List.of(trained(TEXT_NAME)));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().shelve(9L, "99999", List.of(DEV_A), null, null));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verifyNoInteractions(shelfWriter);
    }

    @Test
    void shelve_blankVendorTextNameIsConflict() {
        when(jiangyiGatherClient.trainedProducts()).thenReturn(List.of(trained("  ")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().shelve(9L, PID, List.of(DEV_A), null, null));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(e.getReason().contains("识别名"));
        verifyNoInteractions(linkMapper, shelfWriter);
    }

    @Test
    void shelve_missingOrRetiredLinkIsConflict() {
        when(jiangyiGatherClient.trainedProducts()).thenReturn(List.of(trained(TEXT_NAME)));
        when(linkMapper.byProduct(PID)).thenReturn(Optional.of(link("RETIRED")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().shelve(9L, PID, List.of(DEV_A), null, null));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(e.getReason().contains("未挂我方 SKU"));
        verifyNoInteractions(shelfWriter);
    }

    @Test
    void shelve_nonActiveSkuIsConflict() {
        when(jiangyiGatherClient.trainedProducts()).thenReturn(List.of(trained(TEXT_NAME)));
        when(linkMapper.byProduct(PID)).thenReturn(Optional.of(link("BOUND")));
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku("OFFLINE", 350));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().shelve(9L, PID, List.of(DEV_A), null, null));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verifyNoInteractions(shelfWriter);
    }

    @Test
    void shelve_priceOutOfBoundsIsRejectedBeforeAnyWrite() {
        happyPreconditions();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().shelve(9L, PID, List.of(DEV_A), 350 * 3, null));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verifyNoInteractions(permissionService, shelfWriter);
    }

    @Test
    void shelve_priceRequiresPricingPermission() {
        happyPreconditions();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限"))
                .when(permissionService).requirePermission(9L, "ops:pricing:edit");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().shelve(9L, PID, List.of(DEV_A), 350, null));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verifyNoInteractions(shelfWriter);
    }

    @Test
    void shelve_quantityRequiresReplenishmentPermission() {
        happyPreconditions();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限"))
                .when(permissionService).requirePermission(9L, "ops:replenishment:edit");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().shelve(9L, PID, List.of(DEV_A), null, 4));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verifyNoInteractions(shelfWriter);
    }

    @Test
    void shelve_dedupesAndTrimsDeviceIds() {
        happyPreconditions();
        when(shelfWriter.shelveOne(anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new JiangyiShelfWriter.WriterOutcome(
                        JiangyiShelfWriter.STATUS_SHELVED, 29, null, null, List.of()));

        JiangyiShelfService.ShelfResult result = service().shelve(9L, PID,
                List.of(" " + DEV_A + " ", DEV_A, "", DEV_B), null, null);

        assertEquals(2, result.requestedDevices());
        verify(shelfWriter).shelveOne(9L, DEV_A, SKU_ID, TEXT_NAME, null, null);
        verify(shelfWriter).shelveOne(9L, DEV_B, SKU_ID, TEXT_NAME, null, null);
    }

    @Test
    void shelve_rejectsMoreThanTwentyDevices() {
        List<String> devices = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            devices.add("1000000000" + i);
        }

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().shelve(9L, PID, devices, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verifyNoInteractions(jiangyiGatherClient, shelfWriter);
    }

    @Test
    void shelve_emptyDeviceListIsRejected() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().shelve(9L, PID, List.of("  "), null, null));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verifyNoInteractions(jiangyiGatherClient, shelfWriter);
    }

    @Test
    void shelve_oneDeviceFailureDoesNotAbortOthers() {
        happyPreconditions();
        when(shelfWriter.shelveOne(9L, DEV_A, SKU_ID, TEXT_NAME, null, null))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "价格处理中"));
        when(shelfWriter.shelveOne(9L, DEV_B, SKU_ID, TEXT_NAME, null, null))
                .thenReturn(new JiangyiShelfWriter.WriterOutcome(
                        JiangyiShelfWriter.STATUS_SHELVED, 31, null, null, List.of()));

        JiangyiShelfService.ShelfResult result =
                service().shelve(9L, PID, List.of(DEV_A, DEV_B), null, null);

        assertEquals("FAILED", result.devices().get(0).status());
        assertEquals("价格处理中", result.devices().get(0).message());
        assertEquals("SHELVED", result.devices().get(1).status());
        assertTrue(result.devices().get(1).message().isEmpty());
    }
}
