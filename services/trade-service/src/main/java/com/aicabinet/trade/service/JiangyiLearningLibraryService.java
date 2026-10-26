package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.domain.JiangyiTrainingTicket;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiModelFile;
import com.aicabinet.trade.dto.JiangyiGatherDtos.TrainedProduct;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.aicabinet.trade.mapper.JiangyiTrainingTicketMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商品学习库（租户级资产总览）。
 *
 * <p>🔴 <b>学习不是某台柜子的属性</b>：§4.4.3 {@code commitTrainingTenantOne} 请求体只有
 * modelName + 回调，没有 deviceId ⇒ 训出来的是商户（租户）模型，一次学习学成的类别对全部柜机可用。
 * 但「能不能卖」仍是逐柜的：classId 是 classes.txt 按识别名排序的行号，任何一次重训都会整体平移
 * （台账 H-27），所以挂接（{@code jiangyi_class_mapping}）、复核戳、库存、价格都留在柜机维度。</p>
 *
 * <p>本页只做三件事：把厂商侧的<b>已学商品 / 模型</b>摊平成能看懂的资产表、标出每个已学类别
 * <b>已被哪几台柜子上架</b>、给出（可选的）学习触发入口。拍照采集本身在柜机摄像头前、由厂商
 * 商户 App 编排——我方的 §4.3.3–4.3.14 采集编排尚未接入（台账 G-5），本页不假装能驱动采集。</p>
 *
 * <p>厂商读面失败只降级为空列表 + {@code vendorError}，柜机/挂接/凭据（本地库）照常展示——
 * 学习库页面的价值有一半在本地数据上，不能因为厂商 503 就整页白屏。</p>
 */
@Service
public class JiangyiLearningLibraryService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiLearningLibraryService.class);
    private static final int RECENT_TRAINING_LIMIT = 20;

    private final JiangyiGatherClient jiangyiGatherClient;
    private final JiangyiDeviceMapper deviceMapper;
    private final DeviceInfoMapper deviceInfoMapper;
    private final JiangyiClassMappingMapper mappingMapper;
    private final JiangyiSkuJiangyiLinkMapper linkMapper;
    private final JiangyiTrainingTicketMapper ticketMapper;
    private final SkuCatalogMapper skuMapper;

    public JiangyiLearningLibraryService(JiangyiGatherClient jiangyiGatherClient,
                                         JiangyiDeviceMapper deviceMapper,
                                         DeviceInfoMapper deviceInfoMapper,
                                         JiangyiClassMappingMapper mappingMapper,
                                         JiangyiSkuJiangyiLinkMapper linkMapper,
                                         JiangyiTrainingTicketMapper ticketMapper,
                                         SkuCatalogMapper skuMapper) {
        this.jiangyiGatherClient = jiangyiGatherClient;
        this.deviceMapper = deviceMapper;
        this.deviceInfoMapper = deviceInfoMapper;
        this.mappingMapper = mappingMapper;
        this.linkMapper = linkMapper;
        this.ticketMapper = ticketMapper;
        this.skuMapper = skuMapper;
    }

    public LearningLibraryView view() {
        List<JiangyiModelFile> models = List.of();
        List<TrainedProduct> trained = List.of();
        List<String> vendorErrors = new ArrayList<>();
        try {
            models = jiangyiGatherClient.modelFiles();
        } catch (RuntimeException e) {
            vendorErrors.add("读取将邑模型列表失败：" + rootMessage(e));
        }
        try {
            trained = jiangyiGatherClient.trainedProducts();
        } catch (RuntimeException e) {
            vendorErrors.add("读取将邑已学习商品失败：" + rootMessage(e));
        }

        List<JiangyiDevice> bound = deviceMapper.listBound();
        Map<String, DeviceInfo> infoById = deviceInfoMapper
                .findByDeviceIdIn(bound.stream().map(JiangyiDevice::getDeviceId).toList())
                .stream()
                .collect(Collectors.toMap(DeviceInfo::getDeviceId, Function.identity(), (a, b) -> a));
        List<JiangyiClassMapping> activeMappings = mappingMapper.listActiveAll();
        List<JiangyiSkuJiangyiLink> links = linkMapper.listBoundAll();
        List<JiangyiTrainingTicket> recentTickets = ticketMapper.listRecent(RECENT_TRAINING_LIMIT);

        Set<String> skuIds = new LinkedHashSet<>();
        links.forEach(l -> addIfPresent(skuIds, l.getSkuId()));
        recentTickets.forEach(t -> addIfPresent(skuIds, t.getSkuId()));
        Map<String, SkuCatalog> skuById = skuMapper.findBySkuIds(skuIds).stream()
                .collect(Collectors.toMap(SkuCatalog::getSkuId, Function.identity(), (a, b) -> a));

        Map<String, DeviceInfo> info = infoById;
        Map<String, List<JiangyiClassMapping>> activeByTextName = activeMappings.stream()
                .filter(m -> notBlank(m.getTextName()))
                .collect(Collectors.groupingBy(m -> m.getTextName().trim()));
        Map<String, Long> activeCountByDevice = activeMappings.stream()
                .collect(Collectors.groupingBy(JiangyiClassMapping::getDeviceId, Collectors.counting()));
        Map<String, Boolean> verifiedByDevice = bound.stream()
                .collect(Collectors.toMap(JiangyiDevice::getDeviceId,
                        JiangyiLearningLibraryService::isMappingVerified, (a, b) -> a));
        Map<String, JiangyiSkuJiangyiLink> linkByProductId = new HashMap<>();
        links.forEach(l -> {
            if (notBlank(l.getJiangyiProductId())) {
                linkByProductId.putIfAbsent(l.getJiangyiProductId().trim(), l);
            }
        });
        Set<String> trainedProductIds = trained.stream()
                .map(t -> String.valueOf(t.id()))
                .collect(Collectors.toSet());

        List<LearningCabinet> cabinets = bound.stream()
                .map(d -> new LearningCabinet(
                        d.getDeviceId(),
                        info.containsKey(d.getDeviceId()) ? info.get(d.getDeviceId()).getDeviceName() : null,
                        d.getIdentifier(),
                        d.getClassesVersion(),
                        isMappingVerified(d),
                        activeCountByDevice.getOrDefault(d.getDeviceId(), 0L).intValue(),
                        d.getGatherLockedAt() != null,
                        info.containsKey(d.getDeviceId()) ? info.get(d.getDeviceId()).getOnlineStatus() : null,
                        d.getLastWsOnlineAt()))
                .toList();

        List<LearnedProduct> products = trained.stream()
                .map(t -> {
                    JiangyiSkuJiangyiLink link = linkByProductId.get(String.valueOf(t.id()));
                    String textName = t.textName() == null ? null : t.textName().trim();
                    List<ProductCoverage> coverage = textName == null
                            ? List.of()
                            : activeByTextName.getOrDefault(textName, List.of()).stream()
                                    .map(m -> new ProductCoverage(
                                            m.getDeviceId(),
                                            info.containsKey(m.getDeviceId())
                                                    ? info.get(m.getDeviceId()).getDeviceName()
                                                    : null,
                                            m.getClassId(),
                                            verifiedByDevice.getOrDefault(m.getDeviceId(), false)))
                                    .toList();
                    return new LearnedProduct(
                            t.id(), t.name(), textName, t.barCode(),
                            link == null ? null : link.getSkuId(),
                            link == null ? null : skuName(skuById, link.getSkuId()),
                            coverage);
                })
                .toList();

        List<LearnableSku> learnableSkus = links.stream()
                .map(l -> new LearnableSku(
                        l.getSkuId(),
                        skuName(skuById, l.getSkuId()),
                        l.getJiangyiProductId(),
                        l.getJiangyiName(),
                        l.getJiangyiTextName(),
                        l.getSyncStatus(),
                        l.getJiangyiProductId() != null
                                && trainedProductIds.contains(l.getJiangyiProductId().trim())))
                .toList();

        List<TrainingAttempt> recentTrainings = recentTickets.stream()
                .map(t -> new TrainingAttempt(
                        t.getDeviceId(),
                        info.containsKey(t.getDeviceId()) ? info.get(t.getDeviceId()).getDeviceName() : null,
                        t.getSkuId(),
                        skuName(skuById, t.getSkuId()),
                        t.getModelName(),
                        t.getStatus(),
                        t.getCreatedAt(),
                        t.getFinishedAt()))
                .toList();

        String vendorError = vendorErrors.isEmpty() ? null : String.join("；", vendorErrors);
        if (vendorError != null) {
            log.warn("jiangyi learning-library degraded: {}", vendorError);
        }

        return new LearningLibraryView(
                models.stream()
                        .map(m -> new LearningModel(m.id(), m.modelName(), m.quantity(),
                                m.industrialControlModel(), m.modelTextUrl(), m.modelUrl()))
                        .toList(),
                products, learnableSkus, cabinets, recentTrainings, vendorError);
    }

    /** verified ⇔ 柜机 classes_version 非空且等于复核戳版本（V400；两者任一为空都不算已复核）。 */
    static boolean isMappingVerified(JiangyiDevice device) {
        String version = device.getClassesVersion();
        return notBlank(version) && version.trim().equals(device.getMappingVerifiedVersion());
    }

    private static String skuName(Map<String, SkuCatalog> skuById, String skuId) {
        SkuCatalog sku = skuId == null ? null : skuById.get(skuId);
        return sku == null ? null : sku.getSkuName();
    }

    private static void addIfPresent(Set<String> target, String value) {
        if (notBlank(value)) {
            target.add(value.trim());
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    /** 学习库视图（openapi inline schema）。 */
    public record LearningLibraryView(List<LearningModel> models,
                                      List<LearnedProduct> learnedProducts,
                                      List<LearnableSku> learnableSkus,
                                      List<LearningCabinet> cabinets,
                                      List<TrainingAttempt> recentTrainings,
                                      String vendorError) {}

    /** 厂商模型（§4.4.4）：quantity = classes 行数（重训后编号平移的规模）。 */
    public record LearningModel(long id, String modelName, int quantity,
                                String industrialControlModel, String modelTextUrl, String modelUrl) {}

    /** 已学商品（§4.4.2 trained）+ 我方挂接 SKU + 各柜覆盖。 */
    public record LearnedProduct(long jiangyiProductId, String name, String textName, String barCode,
                                 String skuId, String skuName, List<ProductCoverage> coverage) {}

    /** 某个已学类别在某台柜机上的 ACTIVE 挂牌（classId 只对该柜当前模型成立）。 */
    public record ProductCoverage(String deviceId, String deviceName, int classId,
                                  boolean mappingVerified) {}

    /** 可发起学习的我方 SKU（必须有将邑挂接，否则回调无法交叉验证会永远 PENDING）。 */
    public record LearnableSku(String skuId, String skuName, String jiangyiProductId,
                               String jiangyiName, String textName, String syncStatus,
                               boolean trained) {}

    public record LearningCabinet(String deviceId, String deviceName, String identifier,
                                  String classesVersion, boolean mappingVerified,
                                  int activeMappingCount, boolean gatherLocked,
                                  String onlineStatus, Instant lastWsOnlineAt) {}

    /** 学习凭据（我方发起的才有；厂商 App 发起的不会出现在这里）。 */
    public record TrainingAttempt(String deviceId, String deviceName, String skuId, String skuName,
                                  String modelName, String status, Instant createdAt,
                                  Instant finishedAt) {}
}
