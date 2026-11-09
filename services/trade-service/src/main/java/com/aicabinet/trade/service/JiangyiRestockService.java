package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiRestockClient;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.dto.JiangyiRestockDtos.CabinetCaptureProduct;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockLine;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockProgress;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockSubmitCommand;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockSubmitResult;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * §7.11/§7.12 整单提交器：把「该层最终库存」从我方柜内清单生成并提交给将邑。
 *
 * <p><b>语义红线</b>：一次提交 = 该层补货后的最终库存，未列出的商品会被下架（§7.11）。
 * 因此提交必须带 {@code confirmDelist} 显式确认；{@code products:[]} 清层是否合法文档未证实
 * （附录 B 未覆盖）⇒ 空清单一律拒绝，清层走单独流程。</p>
 *
 * <p><b>重试幂等</b>：{@code requestId} 由 (deviceId, floorNum, 排序后的行内容) 哈希派生——
 * 同内容重试同号（附录 B「识别模板尚未生成」= 同号稍后重试），内容变化自动换号
 * （附录 B「补货内容已变更」= 换新号）。</p>
 *
 * <p>清单来源是<b>柜内库存 + 挂接条码 + 我方售价</b>，不依赖 class 映射复核闸
 * （那是识别结算的门，不是补货上架的门）；无 BOUND 挂接/无条码的 SKU 跳过并回报，
 * 让运营先建档/挂接，避免把结算认不出的商品上架。</p>
 */
@Service
public class JiangyiRestockService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiRestockService.class);

    private final JiangyiRestockClient restockClient;
    private final JiangyiDeviceDirectory deviceDirectory;
    private final DeviceSkuInventoryMapper inventoryMapper;
    private final SkuCatalogMapper skuCatalogMapper;
    private final JiangyiSkuJiangyiLinkMapper linkMapper;
    private final MerchantSkuPricingService pricingService;
    private final AdminAuditService auditService;

    @Value("${JIANGYI_AUTO_RESTOCK_SYNC:true}")
    private boolean autoRestockSync;

    public JiangyiRestockService(JiangyiRestockClient restockClient,
                                 JiangyiDeviceDirectory deviceDirectory,
                                 DeviceSkuInventoryMapper inventoryMapper,
                                 SkuCatalogMapper skuCatalogMapper,
                                 JiangyiSkuJiangyiLinkMapper linkMapper,
                                 MerchantSkuPricingService pricingService,
                                 AdminAuditService auditService) {
        this.restockClient = restockClient;
        this.deviceDirectory = deviceDirectory;
        this.inventoryMapper = inventoryMapper;
        this.skuCatalogMapper = skuCatalogMapper;
        this.linkMapper = linkMapper;
        this.pricingService = pricingService;
        this.auditService = auditService;
    }

    public record PreviewLine(String skuId, String skuName, String barCode, int stockNum, long salePriceCents) {}

    public record Preview(List<PreviewLine> lines, List<String> skippedSkus) {}

    public record SubmitLine(String barCode, int stockNum, long salePriceCents) {}

    public record SubmitCommand(int floorNum, List<SubmitLine> lines, boolean confirmDelist) {}

    public record SubmitResult(String historyNo, RestockProgress progress,
                               List<CabinetCaptureProduct> cabinetCaptureProducts) {}

    /** 该柜当前可上架清单（库存 + 挂接条码 + 售价）；无挂接的 SKU 进 skippedSkus。 */
    public Preview preview(String deviceId) {
        requireJiangyiDevice(deviceId);
        var policy = pricingService.loadPromoPolicy();
        List<PreviewLine> lines = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (DeviceSkuInventory inventory : inventoryMapper.findByIdDeviceId(deviceId)) {
            String skuId = inventory.getSkuId();
            SkuCatalog sku = skuCatalogMapper.selectById(skuId);
            if (sku == null) {
                skipped.add(skuId);
                continue;
            }
            String barCode = linkMapper.listBySku(skuId).stream()
                    .filter(l -> "BOUND".equals(l.getSyncStatus()))
                    .map(JiangyiSkuJiangyiLink::getBarCode)
                    .filter(b -> b != null && !b.isBlank())
                    .findFirst().orElse(null);
            if (barCode == null) {
                skipped.add(skuId);
                continue;
            }
            lines.add(new PreviewLine(skuId, sku.getSkuName(), barCode,
                    inventory.getQuantity(),
                    pricingService.resolveUnitPriceCents(deviceId, sku, policy)));
        }
        return new Preview(lines, skipped);
    }

    /** §7.11 提交该层最终库存；返回 historyNo + 首轮 §7.12 进度（UI 继续轮 progress）。 */
    public SubmitResult submit(Long operatorId, String deviceId, SubmitCommand command) {
        JiangyiDevice device = requireJiangyiDevice(deviceId);
        if (!command.confirmDelist()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "提交即覆盖该层清单：未列出的商品会被下架，请先勾选确认");
        }
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "清单不能为空：products:[] 清层语义文档未证实，清层请走单独流程");
        }
        List<RestockLine> products = new ArrayList<>();
        List<String> canonical = new ArrayList<>();
        for (SubmitLine line : command.lines()) {
            if (line.barCode() == null || line.barCode().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "行缺少条码");
            }
            if (line.stockNum() < 0 || line.salePriceCents() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "库存须 ≥0 且售价须 >0（条码 " + line.barCode() + "）");
            }
            if (linkMapper.byBarCode(line.barCode().trim()).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "条码未挂接到我方 SKU，先建档/挂接再上架：" + line.barCode());
            }
            products.add(new RestockLine(null, line.barCode().trim(), line.stockNum(), line.salePriceCents()));
            canonical.add(line.barCode().trim() + ":" + line.stockNum() + ":" + line.salePriceCents());
        }
        canonical.sort(String::compareTo);
        String requestId = "rs-" + sha256(deviceId + "|" + command.floorNum() + "|" + String.join(",", canonical))
                .substring(0, 40);
        RestockSubmitResult result = restockClient.submitRestock(new RestockSubmitCommand(
                requestId, device.getIdentifier(), command.floorNum(), null, null, products));
        RestockProgress progress = restockClient.restockProgress(device.getIdentifier(), result.historyNo());
        auditService.appendLog(operatorId, "JIANGYI_RESTOCK_SUBMIT", "JIANGYI_DEVICE", deviceId,
                "floorNum=" + command.floorNum() + ",lines=" + products.size()
                        + ",historyNo=" + result.historyNo() + ",requestId=" + requestId);
        log.info("jiangyi restock submitted deviceId={} floor={} lines={} historyNo={} stage={}",
                deviceId, command.floorNum(), products.size(), result.historyNo(), progress.stage());
        return new SubmitResult(result.historyNo(), progress, result.cabinetCaptureProducts());
    }

    /** §7.12 轮询识别参数下发进度（ready=true 才可正常购物）。 */
    public RestockProgress progress(String deviceId, String historyNo) {
        JiangyiDevice device = requireJiangyiDevice(deviceId);
        return restockClient.restockProgress(device.getIdentifier(), historyNo);
    }

    private JiangyiDevice requireJiangyiDevice(String deviceId) {
        JiangyiDevice device = deviceDirectory.findDevice(deviceId);
        if (device == null || device.getIdentifier() == null || device.getIdentifier().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "非将邑柜或未登记 identifier：" + deviceId);
        }
        return device;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 补货完成后自动把该柜现行库存同步为将邑该层最终库存（§7.11）。失败只告警不阻断补货完成。 */
    public void syncAfterRestock(String deviceId) {
        if (!autoRestockSync) {
            return;
        }
        try {
            if (!deviceDirectory.isJiangyi(deviceId)) {
                return;
            }
            Preview preview = preview(deviceId);
            if (preview.lines().isEmpty()) {
                return;
            }
            List<SubmitLine> lines = preview.lines().stream()
                    .map(l -> new SubmitLine(l.barCode(), l.stockNum(), l.salePriceCents()))
                    .toList();
            submit(0L, deviceId, new SubmitCommand(1, lines, true));
        } catch (Exception e) {
            log.warn("jiangyi auto restock sync skipped deviceId={}: {}", deviceId, e.getMessage());
        }
    }
}