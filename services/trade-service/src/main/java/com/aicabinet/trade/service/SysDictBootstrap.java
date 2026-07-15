package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.SysDictData;
import com.aicabinet.trade.domain.SysDictType;
import com.aicabinet.trade.mapper.SysDictDataMapper;
import com.aicabinet.trade.mapper.SysDictTypeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeds sys_dict_* from the same baseline as packages/shared-dict when empty.
 */
@Component
public class SysDictBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SysDictBootstrap.class);

    private final SysDictTypeMapper typeRepository;
    private final SysDictDataMapper dataRepository;

    public SysDictBootstrap(SysDictTypeMapper typeRepository, SysDictDataMapper dataRepository) {
        this.typeRepository = typeRepository;
        this.dataRepository = dataRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (typeRepository.count() > 0) {
            return;
        }
        int sort = 1;
        for (Map.Entry<String, SeedType> entry : seedCatalog().entrySet()) {
            SysDictType type = new SysDictType();
            type.setDictType(entry.getKey());
            type.setDictName(entry.getValue().name());
            type.setStatus("ACTIVE");
            type.setSortOrder(sort++);
            typeRepository.save(type);
            int itemSort = 1;
            for (Map.Entry<String, String> item : entry.getValue().items().entrySet()) {
                SysDictData data = new SysDictData();
                data.setDictType(entry.getKey());
                data.setDictValue(item.getKey());
                data.setDictLabel(item.getValue());
                data.setSortOrder(itemSort++);
                data.setStatus("ACTIVE");
                dataRepository.save(data);
            }
        }
        log.info("Seeded {} dict types", typeRepository.count());
    }

    private record SeedType(String name, Map<String, String> items) {}

    private static Map<String, SeedType> seedCatalog() {
        Map<String, SeedType> map = new LinkedHashMap<>();
        map.put("device_type", t("设备类型", m("AI_CABINET_V1", "AI智能柜 V1")));
        map.put("session_state", t("会话状态", m(
                "CREATED", "已创建", "OPENING", "开门中", "SHOPPING", "购物中",
                "RECOGNIZING", "识别商品中", "WAITING_UPLOAD", "录像上传中", "SETTLING", "结算中",
                "COMPLETED", "已完成", "DISPUTED", "待审核", "FAILED", "失败", "CANCELLED", "已取消")));
        map.put("upload_status", t("上传状态", m(
                "NONE", "无需上传", "LOCAL_QUEUED", "待上传", "UPLOADING", "上传中",
                "UPLOADED", "已上传", "FAILED", "上传失败")));
        map.put("dispute_status", t("争议状态", m("OPEN", "待审核", "RESOLVED", "已结案", "CLOSED", "已结案")));
        map.put("pay_channel", t("支付渠道", m(
                "WECHAT", "微信", "ALIPAY", "支付宝", "MOCK", "其他", "BALANCE", "余额", "UNKNOWN", "未知")));
        map.put("split_status", t("分账状态", m(
                "PENDING", "待处理", "LEDGER_ONLY", "仅记账", "ACCRUED", "待分账",
                "WECHAT_SUBMITTED", "已提交", "WECHAT_FAILED", "失败", "SUBMITTED", "已提交",
                "SUCCESS", "成功", "FAILED", "失败")));
        map.put("merchant_status", t("商户状态", m("ACTIVE", "正常", "INACTIVE", "停用", "PENDING", "待审核")));
        map.put("online_status", t("在线状态", m("ONLINE", "在线", "OFFLINE", "离线", "UNKNOWN", "未知")));
        map.put("supplier_status", t("供应商状态", m("ACTIVE", "启用", "INACTIVE", "停用")));
        map.put("purchase_order_status", t("采购单状态", m(
                "CREATED", "待收货", "PARTIAL_RECEIVED", "部分收货", "RECEIVED", "已收货", "CANCELLED", "已取消")));
        map.put("warehouse_status", t("仓库状态", m("ACTIVE", "正常", "INACTIVE", "停用")));
        map.put("warehouse_outbound_status", t("出库单状态", m(
                "DRAFT", "待拣货", "PICKED", "已拣货", "SHIPPED", "已发运", "CANCELLED", "已取消")));
        map.put("handover_status", t("交接状态", m(
                "PENDING", "待备货", "READY", "待发运", "IN_TRANSIT", "在途", "PARTIAL", "部分签收", "RECEIVED", "已签收")));
        map.put("in_transit_status", t("在途状态", m(
                "IN_TRANSIT", "在途", "RECEIVED", "已签收", "LOST", "丢失", "DAMAGED", "破损")));
        map.put("warehouse_movement_type", t("库存变动类型", m(
                "PURCHASE_RECEIVE", "采购收货", "MANUAL_INBOUND", "手工入库", "OUTBOUND", "出库",
                "OUTBOUND_SHIP", "发运", "RETURN", "退回", "ADJUSTMENT", "库存调整")));
        map.put("business_reference_type", t("业务关联类型", m(
                "PURCHASE_ORDER", "采购单", "OUTBOUND_ORDER", "出库单", "REPLENISHMENT_TASK", "补货任务",
                "INVENTORY_ADJUSTMENT", "库存调整", "MANUAL", "人工操作")));
        map.put("replenishment_route_status", t("补货路线状态", m(
                "PLANNED", "待执行", "IN_PROGRESS", "执行中", "COMPLETED", "已完成", "CANCELLED", "已取消")));
        map.put("replenishment_task_status", t("补货任务状态", m(
                "PENDING", "待处理", "IN_PROGRESS", "进行中", "COMPLETED", "已完成", "CANCELLED", "已取消")));
        map.put("replenishment_request_status", t("补货申请状态", m(
                "SUBMITTED", "待审核", "ACCEPTED", "已接单", "REJECTED", "已驳回", "COMPLETED", "已完成")));
        map.put("inventory_lot_status", t("批次状态", m(
                "ON_SALE", "在售", "NEAR_EXPIRY", "临期", "BLOCKED", "已冻结", "DEPLETED", "已耗尽")));
        map.put("exception_severity", t("异常级别", m(
                "CRITICAL", "紧急", "HIGH", "高", "MEDIUM", "中", "LOW", "低")));
        map.put("exception_status", t("异常状态", m(
                "OPEN", "待处理", "PROCESSING", "处理中", "RESOLVED", "已解决", "CLOSED", "已关闭")));
        map.put("exception_type", t("异常类型", m(
                "DISPUTE", "消费争议", "LOW_STOCK", "低库存", "EXPIRY", "临期商品",
                "REPLENISHMENT_REQUIRED", "待补货", "DEVICE_OFFLINE", "设备离线", "DEVICE_FAULT", "设备故障",
                "DOOR_OPEN_TOO_LONG", "长时间未关门", "OPEN_TIMEOUT", "开门超时", "UPLOAD_STUCK", "录像上传滞留",
                "RECOGNITION_STUCK", "识别滞留", "RECOGNITION_FAILED", "识别存疑需人工审核",
                "RECOGNITION_UNAVAILABLE", "识别服务不可用", "BALANCE_INSUFFICIENT", "余额不足",
                "SETTLEMENT_FAILED", "结算失败", "SETTLEMENT_STUCK", "结算滞留", "INVENTORY_MISMATCH", "库存差异")));
        map.put("ops_exception_action", t("异常操作", m(
                "OPS_EXCEPTION_CLAIM", "领取异常", "OPS_EXCEPTION_TRANSFER", "转派异常",
                "OPS_EXCEPTION_NOTE", "添加备注", "OPS_EXCEPTION_RETRY", "重试识别/结算",
                "OPS_EXCEPTION_RETRY_SUCCESS", "重试成功", "OPS_EXCEPTION_CANCEL_SESSION", "取消会话并释放设备",
                "OPS_EXCEPTION_MANUAL_RESOLVE", "人工处置（确认商品/免单）", "OPS_EXCEPTION_RESOLVE", "标记已解决",
                "OPS_EXCEPTION_AUTO_RESOLVE", "系统自动解决", "MERCHANT_OPS_EXCEPTION_RESOLVE", "商家处理异常")));
        map.put("reconciliation_status", t("对账状态", m(
                "MATCHED", "已平账", "MISMATCH", "存在差异", "PENDING", "待处理", "FAILED", "失败")));
        map.put("sku_status", t("商品状态", m("ACTIVE", "在售", "INACTIVE", "停用", "DISABLED", "禁售")));
        map.put("order_status", t("订单状态", m(
                "PENDING", "待支付", "PROCESSING", "处理中", "PAID", "已支付", "COMPLETED", "已完成",
                "DISPUTED", "争议中", "REFUNDED", "已退款", "FAILED", "处理失败", "CANCELLED", "已取消")));
        return map;
    }

    private static SeedType t(String name, Map<String, String> items) {
        return new SeedType(name, items);
    }

    private static Map<String, String> m(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}
