package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceFaultOrder;
import com.aicabinet.trade.mapper.DeviceFaultOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 设备侧故障单读写门面（CB-077，V416 device_fault_order）。
 *
 * <p>存在意义与 {@link JiangyiOrderVideoService} 同型：{@code JiangyiInternalController}
 * （gateway 转发）与 {@code AdminJiangyiController}/{@code MerchantPortalController}
 * （展示面）都要用 mapper，而「Controller 不得直连 Mapper」是本仓 ArchUnit 门禁
 * （TradeArchitectureTest#controllersShouldNotDependOnMappers），在此收口。</p>
 *
 * <p>克制原则（台账 CB-077 ③）：故障单只作展示/诊断，不参与结算/资金路径、
 * 不驱动会话状态机——会话生命周期由 watchdog 兜底不变。</p>
 *
 * <p>V16 §4.2.20 新增原因「商品识别参数未就绪」的处置（2026-11-05 拍板 A 档）：
 * <b>只额外报一条独立运维告警</b>，仍不驱动会话状态机、不碰资金。理由见
 * `docs/JIANGYI_INTERFACE_CHECKLIST.md` §12B：不加工时该单会被 10 秒开门超时清扫成
 * 「设备未响应」，根因指错；A 档先把「看得见、说得清」补上，B 档（会话终结）待真机验证后再议。</p>
 */
@Service
public class DeviceFaultOrderService {

    private static final Logger log = LoggerFactory.getLogger(DeviceFaultOrderService.class);

    /** V16 §4.2.20 / §6.3 原文：设备拒绝开锁时的故障原因。 */
    static final String MSG_PARAMS_NOT_READY = "商品识别参数未就绪";

    /** 固件原文不可信（铁律）：单字段入库前截断，防超长报文撑爆行。 */
    private static final int MAX_FIELD_LEN = 512;

    private final DeviceFaultOrderMapper deviceFaultOrderMapper;
    private final OpsExceptionService opsExceptionService;

    public DeviceFaultOrderService(DeviceFaultOrderMapper deviceFaultOrderMapper,
                                   OpsExceptionService opsExceptionService) {
        this.deviceFaultOrderMapper = deviceFaultOrderMapper;
        this.opsExceptionService = opsExceptionService;
    }

    /**
     * 故障单落库（gateway 转发入口）。🔴 失败只记 warn 不抛——上报链路是展示增强，
     * 存库失败不得把设备侧 POST 打成 5xx（固件会无退避重试，见 D-4 前车之鉴）。
     */
    public void recordFault(String deviceId, String identifier, String orderNo,
                            String errorCode, String errorMsg) {
        try {
            deviceFaultOrderMapper.insertFault(compact(deviceId), compact(identifier),
                    compact(orderNo), compact(errorCode), compact(errorMsg), Instant.now());
        } catch (Exception e) {
            log.warn("device fault order insert failed deviceId={} orderNo={}: {}",
                    deviceId, orderNo, e.getMessage());
        }
        raiseIfParamsNotReady(deviceId, orderNo, errorMsg);
    }

    /**
     * 「商品识别参数未就绪」→ 独立运维告警（按 orderNo 去重，同一单重投只留一条 OPEN）。
     *
     * <p>只读旁路：告警失败同样只记 warn，不影响故障单落库与设备侧 200。</p>
     */
    private void raiseIfParamsNotReady(String deviceId, String orderNo, String errorMsg) {
        if (!isParamsNotReady(errorMsg)) {
            return;
        }
        try {
            opsExceptionService.report("JIANGYI_PARAMS_NOT_READY", "MEDIUM",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(deviceId, null, orderNo, null),
                    "商品识别参数未就绪",
                    "设备拒绝开锁（V16 §6.3）：本柜识别参数未下发完成。处理：① 确认已调 §7.11 同步该层补货；"
                            + "② 用 §7.10 查商品状态，未完成采集的按第七章补做；③ 用 §7.12 查下发进度，ready=true 后恢复。");
        } catch (Exception e) {
            log.warn("params-not-ready alert failed deviceId={} orderNo={}: {}",
                    deviceId, orderNo, e.getMessage());
        }
    }

    /** 固件原文可能带前后缀，按包含判定（文档原文「商品识别参数未就绪」）。 */
    static boolean isParamsNotReady(String errorMsg) {
        return errorMsg != null && errorMsg.contains(MSG_PARAMS_NOT_READY);
    }

    /** 设备详情「故障记录」列表行（纯列表，一期不接待办/红点）。 */
    public record FaultListItem(Long id, String deviceId, String identifier, String orderNo,
                                String errorCode, String errorMsg, Instant reportedAt) {}

    /** 按设备查最近故障（reported_at 降序，limit 钳制 1..100，默认 20）。 */
    public List<FaultListItem> listByDevice(String deviceId, int limit) {
        return deviceFaultOrderMapper.findRecentByDevice(deviceId, limit).stream()
                .map(DeviceFaultOrderService::toListItem)
                .toList();
    }

    private static FaultListItem toListItem(DeviceFaultOrder row) {
        return new FaultListItem(row.getId(), row.getDeviceId(), row.getIdentifier(),
                row.getOrderNo(), row.getErrorCode(), row.getErrorMsg(), row.getReportedAt());
    }

    private static String compact(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.length() <= MAX_FIELD_LEN ? v : v.substring(0, MAX_FIELD_LEN);
    }
}
