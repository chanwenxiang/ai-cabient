package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceInventoryDto;
import com.aicabinet.common.dto.UpdateDeviceSkuPriceRequest;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.DeviceSkuPrice;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuPriceMapper;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 「跨柜一键上架」的单柜写侧（每台柜一个独立事务）。
 *
 * <p><b>为什么单独成类 + REQUIRES_NEW</b>：批量上架是逐柜回显结果的部分成功语义。写映射/改价/
 * 铺货分别在三个既有服务里，任何一个抛错都会把外层事务标成 rollback-only（Spring 语义），
 * 外层 catch 之后提交就会炸成 UnexpectedRollbackException。拆成独立事务后「一台柜失败」只回滚
 * 它自己，其余柜照常落库。</p>
 *
 * <p><b>口径（钱相关）</b>：只认柜机<b>当前模型里已有的那一行识别映射</b>——识别名与 classId 的
 * 对应关系由 V400 复核/自动重算保证；本类只往这行挂 SKU 并置 ACTIVE，不改 classId/textName，
 * 因此复核戳继续有效。以下情况一律跳过并写清原因，绝不替运营猜：</p>
 * <ul>
 *   <li>柜机未绑定 / 映射未按当前 classes 版本复核；</li>
 *   <li>该柜模型里没有这个识别名（旧模型）、同一识别名多行（冲突）；</li>
 *   <li>该识别名已挂<b>另一个</b> SKU（已成交过的类目不许静默改收款对象）。</li>
 * </ul>
 *
 * <p>价格与库存复用既有写入口（{@link OpsDevicePricingService#updatePricing} /
 * {@link OpsReplenishmentAdminService#upsertInventory}），因此限价校验、乐观锁、审计、
 * 批次账本保护全部照旧；库存只补「账面为 0 且无批次账本」的柜，避免抹掉营业中的实盘。</p>
 */
@Service
public class JiangyiShelfWriter {

    private static final Logger log = LoggerFactory.getLogger(JiangyiShelfWriter.class);

    public static final String STATUS_SHELVED = "SHELVED";
    public static final String STATUS_ALREADY = "ALREADY";
    public static final String STATUS_SKIPPED = "SKIPPED";

    private final JiangyiDeviceMapper deviceMapper;
    private final JiangyiClassMappingMapper mappingMapper;
    private final JiangyiMappingReviewService mappingReviewService;
    private final OpsDevicePricingService devicePricingService;
    private final DeviceSkuPriceMapper priceMapper;
    private final DeviceSkuInventoryMapper inventoryMapper;
    private final InventoryLotService inventoryLotService;
    private final OpsReplenishmentAdminService replenishmentAdminService;
    private final AdminAuditService adminAuditService;

    public JiangyiShelfWriter(JiangyiDeviceMapper deviceMapper,
                              JiangyiClassMappingMapper mappingMapper,
                              JiangyiMappingReviewService mappingReviewService,
                              OpsDevicePricingService devicePricingService,
                              DeviceSkuPriceMapper priceMapper,
                              DeviceSkuInventoryMapper inventoryMapper,
                              InventoryLotService inventoryLotService,
                              OpsReplenishmentAdminService replenishmentAdminService,
                              AdminAuditService adminAuditService) {
        this.deviceMapper = deviceMapper;
        this.mappingMapper = mappingMapper;
        this.mappingReviewService = mappingReviewService;
        this.devicePricingService = devicePricingService;
        this.priceMapper = priceMapper;
        this.inventoryMapper = inventoryMapper;
        this.inventoryLotService = inventoryLotService;
        this.replenishmentAdminService = replenishmentAdminService;
        this.adminAuditService = adminAuditService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WriterOutcome shelveOne(Long operatorId, String deviceId, String skuId, String textName,
                                   Integer priceCents, Integer quantity) {
        JiangyiDevice device = deviceMapper.selectById(deviceId);
        if (device == null || !"BOUND".equals(device.getStatus())) {
            return WriterOutcome.skipped("柜机未绑定将邑，无法上架");
        }
        if (!mappingReviewService.isVerified(deviceId)) {
            return WriterOutcome.skipped("映射未按当前模型复核（重训会让识别编号平移），请先到识别映射页复核");
        }
        List<JiangyiClassMapping> hits = mappingMapper.listByDevice(deviceId).stream()
                .filter(m -> m.getClassId() != null && m.getClassId() >= 0)
                .filter(m -> textName.equals(strip(m.getTextName())))
                .toList();
        if (hits.isEmpty()) {
            return WriterOutcome.skipped("该柜当前模型没有识别名「" + textName + "」（可能是旧模型），需先下发并复核");
        }
        if (hits.size() > 1) {
            return WriterOutcome.skipped("识别名「" + textName + "」对应 " + hits.size()
                    + " 行映射（冲突），请先人工合并");
        }
        JiangyiClassMapping row = hits.get(0);
        boolean alreadyActive = skuId.equals(strip(row.getSkuId())) && "ACTIVE".equals(row.getStatus());
        if (!alreadyActive) {
            if (row.getSkuId() != null && !row.getSkuId().isBlank()
                    && !skuId.equals(row.getSkuId().trim())) {
                return WriterOutcome.skipped("识别名「" + textName + "」已挂其它 SKU（" + row.getSkuId()
                        + "），改收款对象须人工处理");
            }
            row.setSkuId(skuId);
            row.setStatus("ACTIVE");
            mappingMapper.upsert(row);
        }

        List<String> notes = new ArrayList<>();
        notes.add("识别编号 " + row.getClassId()
                + (alreadyActive ? "（原本已上架）" : "，已挂 SKU 并启用"));
        if (priceCents != null) {
            Long expectedVersion = priceMapper.findByDeviceIdAndSkuId(deviceId, skuId)
                    .map(DeviceSkuPrice::getVersion)
                    .orElse(null);
            devicePricingService.updatePricing(operatorId, deviceId, skuId,
                    new UpdateDeviceSkuPriceRequest(priceCents, true, expectedVersion));
            notes.add("柜级价 ¥" + String.format("%.2f", priceCents / 100.0));
        }
        Integer appliedQuantity = applyStock(operatorId, deviceId, skuId, quantity, notes);

        adminAuditService.appendLog(operatorId, "JIANGYI_SHELF", "jiangyi_device", deviceId,
                "textName=" + textName + " skuId=" + skuId + " classId=" + row.getClassId()
                        + " priceCents=" + priceCents + " quantity=" + appliedQuantity);
        log.info("jiangyi shelf deviceId={} skuId={} textName={} classId={} already={} price={} qty={}",
                deviceId, skuId, textName, row.getClassId(), alreadyActive, priceCents, appliedQuantity);
        return new WriterOutcome(alreadyActive ? STATUS_ALREADY : STATUS_SHELVED,
                row.getClassId(), priceCents, appliedQuantity, List.copyOf(notes));
    }

    /** 只补空柜：有批次账本的柜走货道盘点，已有库存的柜不动（避免抹掉营业中的实盘）。 */
    private Integer applyStock(Long operatorId, String deviceId, String skuId, Integer quantity,
                               List<String> notes) {
        if (quantity == null || quantity <= 0) {
            return null;
        }
        if (inventoryLotService.deviceUsesLotLedger(deviceId)) {
            notes.add("该柜已启用批次账本，整机铺货不可用，请走货道盘点");
            return null;
        }
        DeviceSkuInventory existing = inventoryMapper
                .findById(new DeviceSkuInventoryId(deviceId, skuId)).orElse(null);
        int current = existing == null ? 0 : existing.getQuantity();
        if (current > 0) {
            notes.add("已有库存 " + current + " 件，未改动");
            return null;
        }
        int capacity = Math.max(existing == null ? 0 : existing.getCapacity(), quantity);
        int lowThreshold = existing == null ? 2 : existing.getLowThreshold();
        replenishmentAdminService.upsertInventory(operatorId, new DeviceInventoryDto(
                deviceId, skuId, quantity, capacity, lowThreshold, Instant.now(),
                existing == null ? 0L : existing.getVersion()));
        notes.add("铺货 " + quantity + " 件（账面原为 0）");
        return quantity;
    }

    private static String strip(String value) {
        return value == null ? "" : value.trim();
    }

    /** 单柜写侧结果；{@code status} 见本类 STATUS_* 常量（SKIPPED 不写库）。 */
    public record WriterOutcome(String status, Integer classId, Integer priceCents, Integer quantity,
                                List<String> notes) {
        static WriterOutcome skipped(String reason) {
            return new WriterOutcome(STATUS_SKIPPED, null, null, null, List.of(reason));
        }
    }
}
