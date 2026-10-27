package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.dto.JiangyiGatherDtos.LearnableProduct;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.support.SkuPriceBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 跨柜一键上架（学习库 → 多柜）：把一个<b>已在厂商可学习名单里</b>的将邑商品挂到多台柜机的识别映射上，
 * 按需顺带写柜级价与铺货件数，逐柜回显结果。
 *
 * <p><b>识别名的来源是厂商，不信前端</b>：classId 是 classes.txt 按识别名排序的行号（H-27），
 * 唯一可用的对位锚就是识别名。前端只传 {@code jiangyiProductId}，服务端从 §4.4.2
 * {@code learnableProducts} 取该商品的 {@code textName}——不会被人为改错识别名把钱挂到别的类目上。
 * 厂商读面不可用即 502：这是资金相关写操作，宁可不上架。</p>
 *
 * <p>🔴 <b>这份名单不能证明「已学成」</b>（2026-10-27 实测：商品在列时厂商模型数与学习中数同时为 0），
 * 所以钱不会从这里进：真正拦错扣款的是 {@link JiangyiShelfWriter} 的「该柜必须已复核 +
 * 该识别名在柜上已有映射行」fail-closed 门。类名/接口名里的 learned 只指「在厂商名单里」。</p>
 *
 * <p><b>逐柜部分成功</b>：单柜写入在 {@link JiangyiShelfWriter} 的独立事务里，一台失败/跳过不影响
 * 其余柜；本类只做前置校验（挂接、SKU 在售、限价、权限）后按柜汇总。前置校验失败则整批不写。</p>
 *
 * <p>价格与库存是可选动作，各自还有独立权限点：填价要 {@code ops:pricing:edit}，填件数要
 * {@code ops:replenishment:edit}（与「单柜定价」「补货/库存」页同一把钥匙），在写任何一台柜之前
 * 先校验，避免改了一半才 403。</p>
 */
@Service
public class JiangyiShelfService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiShelfService.class);

    /** 一次最多上架这么多台柜：逐柜要拉映射/改价/铺货，过多会让请求超时且难回滚确认。 */
    private static final int MAX_BATCH_DEVICES = 20;

    private static final String PERM_PRICING_EDIT = "ops:pricing:edit";
    private static final String PERM_REPLENISHMENT_EDIT = "ops:replenishment:edit";

    private final JiangyiGatherClient jiangyiGatherClient;
    private final JiangyiSkuJiangyiLinkMapper linkMapper;
    private final SkuCatalogMapper skuMapper;
    private final DeviceInfoMapper deviceInfoMapper;
    private final JiangyiShelfWriter shelfWriter;
    private final PermissionService permissionService;

    public JiangyiShelfService(JiangyiGatherClient jiangyiGatherClient,
                               JiangyiSkuJiangyiLinkMapper linkMapper,
                               SkuCatalogMapper skuMapper,
                               DeviceInfoMapper deviceInfoMapper,
                               JiangyiShelfWriter shelfWriter,
                               PermissionService permissionService) {
        this.jiangyiGatherClient = jiangyiGatherClient;
        this.linkMapper = linkMapper;
        this.skuMapper = skuMapper;
        this.deviceInfoMapper = deviceInfoMapper;
        this.shelfWriter = shelfWriter;
        this.permissionService = permissionService;
    }

    public ShelfResult shelve(Long operatorId, String jiangyiProductId, List<String> deviceIds,
                              Integer priceCents, Integer quantity) {
        String productId = jiangyiProductId == null ? "" : jiangyiProductId.trim();
        if (productId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "将邑商品 id 不能为空");
        }
        List<String> devices = normalizeDevices(deviceIds);
        if (priceCents != null) {
            if (priceCents <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "柜级价必须大于 0 分");
            }
        }
        if (quantity != null && quantity < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "铺货件数必须 ≥ 1（留空表示不铺）");
        }

        List<LearnableProduct> learnable;
        try {
            learnable = jiangyiGatherClient.learnableProducts();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "读取将邑可学习商品名单失败，无法核对识别名：" + rootMessage(e));
        }
        LearnableProduct product = learnable.stream()
                .filter(t -> String.valueOf(t.id()).equals(productId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "将邑可学习商品名单里没有 id=" + productId + "（采集未成档或厂商侧已删）"));
        String textName = product.textName() == null ? "" : product.textName().trim();
        if (textName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该商品在将邑侧没有识别名（classes 未对位），无法与柜机类目挂钩");
        }

        JiangyiSkuJiangyiLink link = linkMapper.byProduct(productId)
                .filter(l -> !"RETIRED".equals(l.getSyncStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "该将邑商品未挂我方 SKU，请先到商品挂接页绑定"));
        SkuCatalog sku = skuMapper.selectById(link.getSkuId());
        if (sku == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "挂接指向的 SKU 不存在：" + link.getSkuId());
        }
        if (!"ACTIVE".equals(sku.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "SKU " + sku.getSkuId() + " 非在售状态（" + sku.getStatus() + "），不能上架");
        }
        if (priceCents != null) {
            SkuPriceBounds.validatePrice(sku, priceCents);
            permissionService.requirePermission(operatorId, PERM_PRICING_EDIT);
        }
        if (quantity != null) {
            permissionService.requirePermission(operatorId, PERM_REPLENISHMENT_EDIT);
        }

        Map<String, String> deviceNames = deviceInfoMapper.findByDeviceIdIn(devices).stream()
                .collect(Collectors.toMap(DeviceInfo::getDeviceId, DeviceInfo::getDeviceName,
                        (a, b) -> a));
        List<ShelfDeviceResult> results = new ArrayList<>(devices.size());
        for (String deviceId : devices) {
            String deviceName = deviceNames.get(deviceId);
            try {
                JiangyiShelfWriter.WriterOutcome outcome =
                        shelfWriter.shelveOne(operatorId, deviceId, sku.getSkuId(), textName,
                                priceCents, quantity);
                results.add(new ShelfDeviceResult(deviceId, deviceName, outcome.status(),
                        outcome.classId(), outcome.priceCents(), outcome.quantity(),
                        String.join("；", outcome.notes())));
            } catch (ResponseStatusException e) {
                results.add(failed(deviceId, deviceName, e.getReason()));
            } catch (RuntimeException e) {
                log.warn("jiangyi shelf failed deviceId={} skuId={} textName={} err={}",
                        deviceId, sku.getSkuId(), textName, e.getMessage());
                results.add(failed(deviceId, deviceName, rootMessage(e)));
            }
        }
        long shelved = results.stream()
                .filter(r -> !"SKIPPED".equals(r.status()) && !"FAILED".equals(r.status()))
                .count();
        log.info("jiangyi shelf batch skuId={} textName={} devices={} shelved={} priceCents={} quantity={}",
                sku.getSkuId(), textName, devices.size(), shelved, priceCents, quantity);
        return new ShelfResult(productId, sku.getSkuId(), sku.getSkuName(), textName,
                devices.size(), List.copyOf(results));
    }

    private static List<String> normalizeDevices(List<String> deviceIds) {
        List<String> devices = deviceIds == null ? List.of() : new ArrayList<>(
                new LinkedHashSet<>(deviceIds.stream()
                        .filter(d -> d != null && !d.isBlank())
                        .map(String::trim)
                        .toList()));
        if (devices.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一台柜机");
        }
        if (devices.size() > MAX_BATCH_DEVICES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "一次最多上架 " + MAX_BATCH_DEVICES + " 台柜机，请分批提交");
        }
        return devices;
    }

    private static ShelfDeviceResult failed(String deviceId, String deviceName, String message) {
        return new ShelfDeviceResult(deviceId, deviceName, "FAILED", null, null, null,
                message == null ? "未知错误" : message);
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    /**
     * 上架结果：{@code textName} 是厂商给的识别名（柜机映射的对位锚），{@code devices} 逐柜回显，
     * status = SHELVED / ALREADY（本来就在售）/ SKIPPED（前置条件不满足，未写库）/ FAILED（写入出错）。
     */
    public record ShelfResult(String jiangyiProductId, String skuId, String skuName, String textName,
                              int requestedDevices, List<ShelfDeviceResult> devices) {}

    public record ShelfDeviceResult(String deviceId, String deviceName, String status, Integer classId,
                                    Integer priceCents, Integer quantity, String message) {}
}
