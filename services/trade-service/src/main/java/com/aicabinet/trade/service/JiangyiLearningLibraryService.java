package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.domain.JiangyiTrainingTicket;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiModelFile;
import com.aicabinet.trade.dto.JiangyiGatherDtos.LearnableProduct;
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
 * <p>本页只做三件事：把厂商侧的<b>可学习商品名单 / 模型</b>摊平成能看懂的资产表、标出每个类别
 * <b>已被哪几台柜子上架</b>、给出（可选的）学习触发入口。拍照采集在柜机摄像头前做，编排已由
 * 后台「开始采集」（{@link JiangyiGatherConsoleService}，§4.3.3–4.3.14）服务化，本页不重复驱动。</p>
 *
 * <p>🔴 <b>「学没学成」在这页只能由末列证明，不能由名单证明</b>：§4.4.2 那张表是
 * <b>采集成档 + 审核通过</b>的可学习名单（文档流程图把它排在「开始学习」之前），2026-10-27 实测
 * 商品 41175 在列时厂商模型数与学习中数<b>同时为 0</b>。判据只有两条本地硬事实——
 * 该识别名在某柜有 {@code ACTIVE} 映射行（{@code coverage} / {@code mappedDeviceIds}），
 * 且那台柜的映射已按当前 classes 版本复核（{@code mappingVerified}）。名单只回答「厂商肯不肯学」。</p>
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
        List<LearnableProduct> learnable = List.of();
        List<String> vendorErrors = new ArrayList<>();
        try {
            models = jiangyiGatherClient.modelFiles();
        } catch (RuntimeException e) {
            vendorErrors.add("读取将邑模型列表失败：" + rootMessage(e));
        }
        try {
            learnable = jiangyiGatherClient.learnableProducts();
        } catch (RuntimeException e) {
            vendorErrors.add("读取将邑可学习商品名单失败：" + rootMessage(e));
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
        Set<String> vendorListedIds = new LinkedHashSet<>();
        Map<String, String> vendorTextNameById = new HashMap<>();
        learnable.forEach(p -> {
            vendorListedIds.add(String.valueOf(p.id()));
            if (notBlank(p.textName())) {
                vendorTextNameById.put(String.valueOf(p.id()), p.textName().trim());
            }
        });

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

        List<LearnableProductRow> products = learnable.stream()
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
                    return new LearnableProductRow(
                            t.id(), t.name(), textName, t.barCode(),
                            link == null ? null : link.getSkuId(),
                            link == null ? null : skuName(skuById, link.getSkuId()),
                            coverage);
                })
                .toList();

        List<LearnableSku> learnableSkus = links.stream()
                .map(l -> {
                    String productId = l.getJiangyiProductId() == null
                            ? null : l.getJiangyiProductId().trim();
                    // 识别名以厂商名单里的当前值为准，名单外（或未回）才退回挂接快照
                    String textName = firstNonBlank(
                            productId == null ? null : vendorTextNameById.get(productId),
                            l.getJiangyiTextName());
                    List<String> mappedDeviceIds = textName == null
                            ? List.of()
                            : activeByTextName.getOrDefault(textName, List.of()).stream()
                                    .map(JiangyiClassMapping::getDeviceId).distinct().sorted().toList();
                    return new LearnableSku(
                            l.getSkuId(),
                            skuName(skuById, l.getSkuId()),
                            l.getJiangyiProductId(),
                            l.getJiangyiName(),
                            l.getJiangyiTextName(),
                            l.getSyncStatus(),
                            productId != null && vendorListedIds.contains(productId),
                            mappedDeviceIds);
                })
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
                        .map(m -> new LearningModel(m.id(), m.modelName(), m.customerModelName(),
                                m.quantity(), m.industrialControlModel(), m.modelTextUrl(), m.modelUrl()))
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

    private static String firstNonBlank(String preferred, String fallback) {
        if (notBlank(preferred)) {
            return preferred.trim();
        }
        return notBlank(fallback) ? fallback.trim() : null;
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
                                      List<LearnableProductRow> learnableProducts,
                                      List<LearnableSku> learnableSkus,
                                      List<LearningCabinet> cabinets,
                                      List<TrainingAttempt> recentTrainings,
                                      String vendorError) {}

    /**
     * 厂商模型（§4.4.4）：quantity = classes 行数（重训后编号平移的规模）。
     * customerModelName = 厂商侧「模型初命名」，我方提交的 modelName 被厂商改写时只在这列留痕。
     */
    public record LearningModel(long id, String modelName, String customerModelName, int quantity,
                                String industrialControlModel, String modelTextUrl, String modelUrl) {}

    /**
     * 厂商可学习名单（§4.4.2）的一行 + 我方挂接 SKU + 各柜覆盖。
     *
     * <p>🔴 在列 = 采集成档且审核通过，<b>不等于已学成</b>；能不能识别只看 {@code coverage}
     * （该识别名在某柜有 ACTIVE 挂牌且那台柜已复核）。</p>
     */
    public record LearnableProductRow(long jiangyiProductId, String name, String textName, String barCode,
                                      String skuId, String skuName, List<ProductCoverage> coverage) {}

    /** 某个厂商名单类别在某台柜机上的 ACTIVE 挂牌（classId 只对该柜当前模型成立）。 */
    public record ProductCoverage(String deviceId, String deviceName, int classId,
                                  boolean mappingVerified) {}

    /**
     * 可发起学习的我方 SKU。必须有将邑挂接——票据要按厂商商品 id 记账，没挂接就绑不上学习对象。
     *
     * <p>{@code inVendorList} = 已在厂商可学习名单里（<b>不表示已学成</b>）；
     * {@code mappedDeviceIds} = 该识别名已在哪些柜上有 ACTIVE 挂牌，这才是「已进识别链路」。</p>
     */
    public record LearnableSku(String skuId, String skuName, String jiangyiProductId,
                               String jiangyiName, String textName, String syncStatus,
                               boolean inVendorList, List<String> mappedDeviceIds) {}

    public record LearningCabinet(String deviceId, String deviceName, String identifier,
                                  String classesVersion, boolean mappingVerified,
                                  int activeMappingCount, boolean gatherLocked,
                                  String onlineStatus, Instant lastWsOnlineAt) {}

    /** 学习凭据（我方发起的才有；厂商 App 发起的不会出现在这里）。 */
    public record TrainingAttempt(String deviceId, String deviceName, String skuId, String skuName,
                                  String modelName, String status, Instant createdAt,
                                  Instant finishedAt) {}
}
