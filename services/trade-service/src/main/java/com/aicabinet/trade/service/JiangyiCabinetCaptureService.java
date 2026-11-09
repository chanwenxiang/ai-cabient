package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiRestockClient;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.dto.JiangyiRestockDtos.CabinetCaptureProduct;
import com.aicabinet.trade.dto.JiangyiRestockDtos.CabinetCaptureSession;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * §7.13 厂商货道核对 + §7.14~§7.18 柜内采集的运营编排。
 *
 * <p>柜内采集是手机采集审核通过（CABINET_PENDING）并上架后的<b>补充</b>环节，
 * 用于提高识别准确率；不能替代手机采集。§7.15 同一台柜同时只能有一个会话，
 * 未结束会话占用该柜约 15 分钟（§7.18），故 UI 必须提供取消出口。</p>
 *
 * <p>本服务只做设备解析 + 透传 + 审计，不改资金/会话状态机；采集互斥（补货/自检中）
 * 由厂商侧返回错误，经 {@code JiangyiRestockException} 透出。</p>
 */
@Service
public class JiangyiCabinetCaptureService {

    private final JiangyiRestockClient restockClient;
    private final JiangyiDeviceDirectory deviceDirectory;
    private final AdminAuditService auditService;

    public JiangyiCabinetCaptureService(JiangyiRestockClient restockClient,
                                        JiangyiDeviceDirectory deviceDirectory,
                                        AdminAuditService auditService) {
        this.restockClient = restockClient;
        this.deviceDirectory = deviceDirectory;
        this.auditService = auditService;
    }

    public record ShelfItem(long productId, String productName, double salePriceYuan, int stock) {}
    public record ShelfFloor(int floorNum, String floorOrientation, List<ShelfItem> items) {}
    public record VendorShelf(String deviceName, String identifier, int totalFloorNum, List<ShelfFloor> floors) {}

    /** §7.13 读厂商当前货道（只读对账：厂商侧 vs 我方清单）。 */
    public VendorShelf vendorShelf(String deviceId) {
        JsonNode data = restockClient.shelf(identifier(deviceId));
        List<ShelfFloor> floors = new ArrayList<>();
        JsonNode datas = data == null ? null : data.path("datas");
        if (datas != null && datas.isArray()) {
            for (JsonNode f : datas) {
                List<ShelfItem> items = new ArrayList<>();
                JsonNode dtos = f.path("dtos");
                if (dtos.isArray()) {
                    for (JsonNode d : dtos) {
                        items.add(new ShelfItem(d.path("productId").asLong(),
                                d.path("productName").asText(""),
                                d.path("salePrice").asDouble(),
                                d.path("stock").asInt()));
                    }
                }
                floors.add(new ShelfFloor(f.path("floorNum").asInt(),
                        f.path("floorOrientation").isMissingNode() || f.path("floorOrientation").isNull()
                                ? null : f.path("floorOrientation").asText(),
                        items));
            }
        }
        return new VendorShelf(data == null ? "" : data.path("deviceName").asText(""),
                data == null ? "" : data.path("identifier").asText(""),
                data == null ? 0 : data.path("totalFloorNum").asInt(), floors);
    }

    /** §7.14 待柜内采集商品（onShelf=false 需先补货上架）。 */
    public List<CabinetCaptureProduct> list(String deviceId) {
        return restockClient.cabinetCaptureList(identifier(deviceId));
    }

    /** §7.15 开始柜内采集（厂商云自动开锁录制）。 */
    public CabinetCaptureSession start(Long operatorId, String deviceId, long productId) {
        CabinetCaptureSession session = restockClient.startCabinetCapture(identifier(deviceId), productId);
        auditService.appendLog(operatorId, "JIANGYI_CABINET_CAPTURE_START", "JIANGYI_DEVICE", deviceId,
                "productId=" + productId + ",sessionId=" + session.sessionId());
        return session;
    }

    /** §7.16 进度（phase 见文档；建议每秒轮询）。 */
    public CabinetCaptureSession status(String deviceId, int sessionId) {
        return restockClient.cabinetCaptureStatus(identifier(deviceId), sessionId);
    }

    /** §7.17 结束（关门后调用，设备开始上传柜内图）。 */
    public CabinetCaptureSession finish(Long operatorId, String deviceId, int sessionId) {
        CabinetCaptureSession session = restockClient.finishCabinetCapture(identifier(deviceId), sessionId);
        auditService.appendLog(operatorId, "JIANGYI_CABINET_CAPTURE_FINISH", "JIANGYI_DEVICE", deviceId,
                "sessionId=" + sessionId);
        return session;
    }

    /** §7.18 取消（释放约 15 分钟占用）。 */
    public CabinetCaptureSession cancel(Long operatorId, String deviceId, int sessionId) {
        CabinetCaptureSession session = restockClient.cancelCabinetCapture(identifier(deviceId), sessionId);
        auditService.appendLog(operatorId, "JIANGYI_CABINET_CAPTURE_CANCEL", "JIANGYI_DEVICE", deviceId,
                "sessionId=" + sessionId);
        return session;
    }

    private String identifier(String deviceId) {
        JiangyiDevice device = deviceDirectory.findDevice(deviceId);
        if (device == null || device.getIdentifier() == null || device.getIdentifier().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "非将邑柜或未登记 identifier：" + deviceId);
        }
        return device.getIdentifier();
    }
}