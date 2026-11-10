package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiRestockClient;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockProgress;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockSubmitCommand;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockSubmitResult;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §7.11 整单提交器钉测：下架语义必须显式确认、空清单拒绝、无挂接条码拒绝、
 * requestId 同内容同号/异内容异号（附录 B 重试口径）、清单用 barCode 不用 productId。
 */
@ExtendWith(MockitoExtension.class)
class JiangyiRestockServiceTest {

    private static final String DEVICE = "EG943322621391";
    private static final String IDENT = "CQYB10720";
    private static final String BAR = "6954767430386";

    @Mock private JiangyiRestockClient restockClient;
    @Mock private JiangyiDeviceDirectory deviceDirectory;
    @Mock private DeviceSkuInventoryMapper inventoryMapper;
    @Mock private SkuCatalogMapper skuCatalogMapper;
    @Mock private JiangyiSkuJiangyiLinkMapper linkMapper;
    @Mock private MerchantSkuPricingService pricingService;
    @Mock private AdminAuditService auditService;

    private JiangyiRestockService service() {
        return new JiangyiRestockService(restockClient, deviceDirectory, inventoryMapper,
                skuCatalogMapper, linkMapper, pricingService, auditService);
    }

    private JiangyiDevice device() {
        JiangyiDevice d = new JiangyiDevice();
        d.setDeviceId(DEVICE);
        d.setIdentifier(IDENT);
        return d;
    }

    private JiangyiSkuJiangyiLink link(String barCode) {
        JiangyiSkuJiangyiLink l = new JiangyiSkuJiangyiLink();
        l.setSkuId("100013");
        l.setBarCode(barCode);
        l.setSyncStatus("BOUND");
        return l;
    }

    @Test
    void preview_skipsSkuWithoutBoundBarCode() {
        when(deviceDirectory.findDevice(DEVICE)).thenReturn(device());
        DeviceSkuInventory inv = new DeviceSkuInventory();
        inv.setSkuId("100013");
        inv.setQuantity(3);
        when(inventoryMapper.findByIdDeviceId(DEVICE)).thenReturn(List.of(inv));
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("100013");
        sku.setSkuName("雪碧");
        when(skuCatalogMapper.selectById("100013")).thenReturn(sku);
        when(linkMapper.listBySku("100013")).thenReturn(List.of());

        JiangyiRestockService.Preview preview = service().preview(DEVICE);

        assertTrue(preview.lines().isEmpty());
        assertEquals(List.of("100013"), preview.skippedSkus());
    }

    /**
     * 2026-11-10 实测回归：我方 7.6 新建（CREATED）的商品审核通过后同样要上架——
     * 预览若只收 BOUND，冰红茶 41175 永远进不了 7.11 清单，柜内采集永远 onShelf=false。
     */
    @Test
    void preview_includesSelfCreatedLink() {
        when(deviceDirectory.findDevice(DEVICE)).thenReturn(device());
        DeviceSkuInventory inv = new DeviceSkuInventory();
        inv.setSkuId("100014");
        inv.setQuantity(2);
        when(inventoryMapper.findByIdDeviceId(DEVICE)).thenReturn(List.of(inv));
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("100014");
        sku.setSkuName("冰红茶");
        when(skuCatalogMapper.selectById("100014")).thenReturn(sku);
        JiangyiSkuJiangyiLink created = new JiangyiSkuJiangyiLink();
        created.setSkuId("100014");
        created.setBarCode("6921294307016");
        created.setSyncStatus("CREATED");
        when(linkMapper.listBySku("100014")).thenReturn(List.of(created));
        when(pricingService.resolveUnitPriceCents(eq(DEVICE), any(SkuCatalog.class), any()))
                .thenReturn(350);

        JiangyiRestockService.Preview preview = service().preview(DEVICE);

        assertEquals(1, preview.lines().size());
        assertEquals("100014", preview.lines().get(0).skuId());
        assertEquals("6921294307016", preview.lines().get(0).barCode());
        assertEquals(2, preview.lines().get(0).stockNum());
        assertTrue(preview.skippedSkus().isEmpty());
    }

    @Test
    void submit_withoutConfirmDelistIs409() {
        when(deviceDirectory.findDevice(DEVICE)).thenReturn(device());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service().submit(1L, DEVICE, new JiangyiRestockService.SubmitCommand(
                        1, List.of(new JiangyiRestockService.SubmitLine(BAR, 3, 350)), false)));
        assertEquals(409, ex.getStatusCode().value());
        verify(restockClient, never()).submitRestock(any());
    }

    @Test
    void submit_emptyLinesIs409() {
        when(deviceDirectory.findDevice(DEVICE)).thenReturn(device());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service().submit(1L, DEVICE, new JiangyiRestockService.SubmitCommand(1, List.of(), true)));
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void submit_unlinkedBarCodeIs409() {
        when(deviceDirectory.findDevice(DEVICE)).thenReturn(device());
        when(linkMapper.byBarCode(BAR)).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service().submit(1L, DEVICE, new JiangyiRestockService.SubmitCommand(
                        1, List.of(new JiangyiRestockService.SubmitLine(BAR, 3, 350)), true)));
        assertEquals(409, ex.getStatusCode().value());
        verify(restockClient, never()).submitRestock(any());
    }

    @Test
    void submit_usesBarCodeAndReturnsProgressAndAudits() {
        when(deviceDirectory.findDevice(DEVICE)).thenReturn(device());
        when(linkMapper.byBarCode(BAR)).thenReturn(Optional.of(link(BAR)));
        when(restockClient.submitRestock(any())).thenReturn(new RestockSubmitResult("H-1", List.of()));
        when(restockClient.restockProgress(IDENT, "H-1"))
                .thenReturn(new RestockProgress(true, "APPLIED", 1, 1, List.of(), List.of()));

        JiangyiRestockService.SubmitResult result = service().submit(1L, DEVICE,
                new JiangyiRestockService.SubmitCommand(1,
                        List.of(new JiangyiRestockService.SubmitLine(BAR, 3, 350)), true));

        ArgumentCaptor<RestockSubmitCommand> captor = ArgumentCaptor.forClass(RestockSubmitCommand.class);
        verify(restockClient).submitRestock(captor.capture());
        assertEquals(IDENT, captor.getValue().deviceIdentifier());
        assertEquals(1, captor.getValue().floorNum());
        assertEquals(BAR, captor.getValue().products().get(0).barCode());
        assertEquals(null, captor.getValue().products().get(0).productId());
        assertEquals(350, captor.getValue().products().get(0).salePriceCents());
        assertEquals("H-1", result.historyNo());
        assertTrue(result.progress().ready());
        verify(auditService).appendLog(eq(1L), eq("JIANGYI_RESTOCK_SUBMIT"), eq("JIANGYI_DEVICE"),
                eq(DEVICE), anyString());
    }

    @Test
    void submit_requestIdStableForSameContentAndChangesWithContent() {
        when(deviceDirectory.findDevice(DEVICE)).thenReturn(device());
        when(linkMapper.byBarCode(BAR)).thenReturn(Optional.of(link(BAR)));
        when(restockClient.submitRestock(any())).thenReturn(new RestockSubmitResult("H", List.of()));
        when(restockClient.restockProgress(eq(IDENT), anyString()))
                .thenReturn(new RestockProgress(false, "BUILDING", 1, 0, List.of(), List.of()));

        service().submit(1L, DEVICE, new JiangyiRestockService.SubmitCommand(
                1, List.of(new JiangyiRestockService.SubmitLine(BAR, 3, 350)), true));
        service().submit(1L, DEVICE, new JiangyiRestockService.SubmitCommand(
                1, List.of(new JiangyiRestockService.SubmitLine(BAR, 3, 350)), true));
        service().submit(1L, DEVICE, new JiangyiRestockService.SubmitCommand(
                1, List.of(new JiangyiRestockService.SubmitLine(BAR, 4, 350)), true));

        ArgumentCaptor<RestockSubmitCommand> captor = ArgumentCaptor.forClass(RestockSubmitCommand.class);
        verify(restockClient, org.mockito.Mockito.times(3)).submitRestock(captor.capture());
        List<RestockSubmitCommand> cmds = captor.getAllValues();
        assertEquals(cmds.get(0).requestId(), cmds.get(1).requestId(), "同内容重试必须同号");
        assertNotEquals(cmds.get(0).requestId(), cmds.get(2).requestId(), "内容变化必须换号");
        assertTrue(cmds.get(0).requestId().length() <= 64);
    }
}