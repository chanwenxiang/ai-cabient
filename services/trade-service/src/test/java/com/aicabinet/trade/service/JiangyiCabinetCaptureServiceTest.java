package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiRestockClient;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.dto.JiangyiRestockDtos.CabinetCaptureProduct;
import com.aicabinet.trade.dto.JiangyiRestockDtos.CabinetCaptureSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §7.13 货道核对 + §7.14~§7.18 柜内采集编排钉测：
 * 非将邑柜 409、货道字段按 §7.13 实测形状映射、写操作必留审计。
 */
@ExtendWith(MockitoExtension.class)
class JiangyiCabinetCaptureServiceTest {

    private static final String DEVICE = "EG943322621391";
    private static final String IDENT = "CQYB10720";

    @Mock private JiangyiRestockClient restockClient;
    @Mock private JiangyiDeviceDirectory deviceDirectory;
    @Mock private AdminAuditService auditService;

    private JiangyiCabinetCaptureService service() {
        return new JiangyiCabinetCaptureService(restockClient, deviceDirectory, auditService);
    }

    private JiangyiDevice device() {
        JiangyiDevice d = new JiangyiDevice();
        d.setDeviceId(DEVICE);
        d.setIdentifier(IDENT);
        return d;
    }

    @Test
    void vendorShelf_mapsFloorsAndItems() throws Exception {
        when(deviceDirectory.findDevice(DEVICE)).thenReturn(device());
        when(restockClient.shelf(IDENT)).thenReturn(new ObjectMapper().readTree("""
                {"deviceName":"测试设备1","identifier":"CQYB10720","totalFloorNum":5,
                 "datas":[{"floorNum":1,"floorOrientation":null,"dtos":[
                   {"productId":41923,"productName":"雪碧柠檬味汽水330ml","salePrice":3.5,"stock":3}]}]}
                """));

        JiangyiCabinetCaptureService.VendorShelf shelf = service().vendorShelf(DEVICE);

        assertEquals("测试设备1", shelf.deviceName());
        assertEquals(5, shelf.totalFloorNum());
        assertEquals(1, shelf.floors().size());
        assertEquals(1, shelf.floors().get(0).floorNum());
        assertEquals(41923L, shelf.floors().get(0).items().get(0).productId());
        assertEquals(3.5, shelf.floors().get(0).items().get(0).salePriceYuan());
        assertEquals(3, shelf.floors().get(0).items().get(0).stock());
    }

    @Test
    void nonJiangyiDeviceIs409() {
        when(deviceDirectory.findDevice(DEVICE)).thenReturn(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service().vendorShelf(DEVICE));
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void list_delegatesWithIdentifier() {
        when(deviceDirectory.findDevice(DEVICE)).thenReturn(device());
        when(restockClient.cabinetCaptureList(IDENT)).thenReturn(List.of(
                new CabinetCaptureProduct(41923L, "雪碧", "6954767430386", "http://img", true)));

        List<CabinetCaptureProduct> list = service().list(DEVICE);

        assertEquals(1, list.size());
        assertEquals(41923L, list.get(0).productId());
    }

    @Test
    void start_finish_cancel_areAudited() {
        when(deviceDirectory.findDevice(DEVICE)).thenReturn(device());
        when(restockClient.startCabinetCapture(IDENT, 41923L))
                .thenReturn(new CabinetCaptureSession(7, IDENT, 41923L, "UNLOCKING", null));
        when(restockClient.finishCabinetCapture(IDENT, 7))
                .thenReturn(new CabinetCaptureSession(7, IDENT, 41923L, "DONE", null));
        when(restockClient.cancelCabinetCapture(IDENT, 7))
                .thenReturn(new CabinetCaptureSession(7, IDENT, 41923L, "CANCELLED", null));

        service().start(1L, DEVICE, 41923L);
        service().finish(1L, DEVICE, 7);
        service().cancel(1L, DEVICE, 7);

        // R22-7：start 有意落两条留痕——受理前 REQUESTING 意图（厂商已受理但本地读超时时
        // 仍可追 sessionId）+ 受理成功 ACCEPTED。旧断言 times(1) 未随行为同步，逐条钉语义。
        verify(auditService).appendLog(eq(1L), eq("JIANGYI_CABINET_CAPTURE_START"),
                eq("JIANGYI_DEVICE"), eq(DEVICE), contains("phase=REQUESTING"));
        verify(auditService).appendLog(eq(1L), eq("JIANGYI_CABINET_CAPTURE_START"),
                eq("JIANGYI_DEVICE"), eq(DEVICE), contains("phase=ACCEPTED"));
        verify(auditService).appendLog(eq(1L), eq("JIANGYI_CABINET_CAPTURE_FINISH"),
                eq("JIANGYI_DEVICE"), eq(DEVICE), anyString());
        verify(auditService).appendLog(eq(1L), eq("JIANGYI_CABINET_CAPTURE_CANCEL"),
                eq("JIANGYI_DEVICE"), eq(DEVICE), anyString());
    }
}