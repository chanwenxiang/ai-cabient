package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.SkuQuantityDto;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.DeviceNameSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 开发/演示环境：业务数据以数据库为准；缺失时自动补齐，避免硬编码 mock 与真实库脱节。
 */
@Service
public class DemoDataService {
    private static final String SKU_NOODLE_001 = "SKU-NOODLE-001";
    private static final String SKU_SNACK_001 = "SKU-SNACK-001";
    private static final String SKU_WATER_001 = "SKU-WATER-001";
    private static final String SKU_MILK_001 = "SKU-MILK-001";
    private static final String SKU_DEMO_001 = "SKU-DEMO-001";
    private static final String SKU_SODA_001 = "SKU-SODA-001";


    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);

    /**
     * 演示柜编号<strong>不写死</strong>：复用库里已有合格柜机，否则
     * {@link DeviceIdService#allocateRandomDeviceId()} 发 12 位号。
     * 历史常量已删除；调用方请读 {@link DemoContext#deviceId()} / {@link DemoContext#warehouseId()}。
     */
    public static final long DEMO_CONSUMER_USER_ID = 10001L;
    public static final String DEMO_CONSUMER_PHONE = "13800138000";
    /**
     * 演示消费者的显示名。
     *
     * <p>2026-09-23：原为「测试用户」，会随 V2 的种子落到任何环境（含生产），上线时还得再改
     * ⇒ 改成拟真名。柜机名同理由 {@link DeviceNameSupport#DEMO_DEVICE_NAME} 提供。
     * 历史行由 {@code V286__rename_demo_fixtures.sql} 一次性改写。
     */
    public static final String DEMO_CONSUMER_NAME = "陈晓";
    /** 遗留孤儿前缀，永不选作演示柜。 */
    private static final String LEGACY_ORPHAN_PREFIX = "CAB-";
    private static final String DEMO_MERCHANT_DISPLAY_NAME = "默认演示商户";

    private final SecurityProperties securityProperties;
    private final SkuCatalogMapper skuCatalogRepository;
    private final DeviceInfoMapper deviceInfoRepository;
    private final DeviceSkuInventoryMapper deviceSkuInventoryRepository;
    private final WarehouseMapper warehouseRepository;
    private final WarehouseInventoryMapper warehouseInventoryRepository;
    private final SkuVisionMappingMapper skuVisionMappingRepository;
    private final UserInfoMapper userInfoRepository;
    private final UserAccountMapper userAccountRepository;
    private final MerchantMapper merchantRepository;
    private final DeviceSlotService deviceSlotService;
    private final InventoryLotService inventoryLotService;
    private final DeviceIdService deviceIdService;
    private final MerchantIdService merchantIdService;
    private final WarehouseSupplierIdService warehouseSupplierIdService;
    private final SupplierMapper supplierRepository;
    private final DeviceSlotMapper deviceSlotMapper;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final DemoDataService self;

    public DemoDataService(SecurityProperties securityProperties,
                           SkuCatalogMapper skuCatalogRepository,
                           DeviceInfoMapper deviceInfoRepository,
                           DeviceSkuInventoryMapper deviceSkuInventoryRepository,
                           WarehouseMapper warehouseRepository,
                           WarehouseInventoryMapper warehouseInventoryRepository,
                           SkuVisionMappingMapper skuVisionMappingRepository,
                           UserInfoMapper userInfoRepository,
                           UserAccountMapper userAccountRepository,
                           MerchantMapper merchantRepository,
                           DeviceSlotService deviceSlotService,
                           InventoryLotService inventoryLotService,
                           DeviceIdService deviceIdService,
                           MerchantIdService merchantIdService,
                           WarehouseSupplierIdService warehouseSupplierIdService,
                           SupplierMapper supplierRepository,
                           DeviceSlotMapper deviceSlotMapper,
                           @Lazy DemoDataService self) {
        this.securityProperties = securityProperties;
        this.skuCatalogRepository = skuCatalogRepository;
        this.deviceInfoRepository = deviceInfoRepository;
        this.deviceSkuInventoryRepository = deviceSkuInventoryRepository;
        this.warehouseRepository = warehouseRepository;
        this.warehouseInventoryRepository = warehouseInventoryRepository;
        this.skuVisionMappingRepository = skuVisionMappingRepository;
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.merchantRepository = merchantRepository;
        this.deviceSlotService = deviceSlotService;
        this.inventoryLotService = inventoryLotService;
        this.deviceIdService = deviceIdService;
        this.merchantIdService = merchantIdService;
        this.warehouseSupplierIdService = warehouseSupplierIdService;
        this.supplierRepository = supplierRepository;
        this.deviceSlotMapper = deviceSlotMapper;
        this.self = self;
    }

    @Transactional
    public DemoContext ensureDemoData() {
        if (!securityProperties.mockEnabled()) {
            String deviceId = resolveExistingDemoDeviceId().orElse("");
            String warehouseId = resolveExistingWarehouseId().orElse("");
            return buildContext(deviceId, warehouseId);
        }
        ensureSkus();
        String deviceId = ensureDevice();
        ensureDeviceInventory(deviceId);
        deviceSlotService.ensureDefaultSlots(deviceId);
        bindInventorySkuToSlots(deviceId);
        String warehouseId = ensureWarehouse();
        ensureSupplier();
        ensureVisionMappings();
        ensureConsumerUser();
        DemoContext ctx = buildContext(deviceId, warehouseId);
        log.info("demo data ensured device={} warehouse={} skus={} fallbackSku={} warehouseLots={}",
                ctx.deviceId(), ctx.warehouseId(), ctx.skuCount(), ctx.fallbackSkuId(), ctx.warehouseLotCount());
        return ctx;
    }

    @Transactional(readOnly = true)
    public DemoContext getContext() {
        return buildContext(
                resolveExistingDemoDeviceId().orElse(""),
                resolveExistingWarehouseId().orElse(""));
    }

    /**
     * 识别兜底：取柜内首个有可售库存、可视觉结算的 SKU（与真实业务一致，不再写死 SKU-DEMO-001）。
     */
    @Transactional(readOnly = true)
    public String resolveFallbackSku(String deviceId) {
        String targetDevice = deviceId != null && !deviceId.isBlank()
                ? deviceId.trim()
                : resolveExistingDemoDeviceId().orElse("");
        if (targetDevice.isBlank()) {
            return skuCatalogRepository.findAll().stream()
                    .filter(this::isChargeableSkuEntity)
                    .map(SkuCatalog::getSkuId)
                    .findFirst()
                    .orElse("");
        }
        Optional<String> fromInventory = deviceSlotService.inventorySnapshot(targetDevice).stream()
                .map(SkuQuantityDto::skuId)
                .filter(this::isChargeableSku)
                .findFirst();
        if (fromInventory.isPresent()) {
            return fromInventory.get();
        }
        return skuCatalogRepository.findAll().stream()
                .filter(this::isChargeableSkuEntity)
                .map(SkuCatalog::getSkuId)
                .findFirst()
                .orElse("");
    }

    private boolean isChargeableSku(String skuId) {
        return skuCatalogRepository.findById(skuId).map(this::isChargeableSkuEntity).orElse(false);
    }

    private boolean isChargeableSkuEntity(SkuCatalog sku) {
        return sku.isVisionEnabled() && "ACTIVE".equalsIgnoreCase(sku.getStatus());
    }

    private DemoContext buildContext(String deviceId, String warehouseId) {
        String id = deviceId != null ? deviceId : "";
        String wh = warehouseId != null ? warehouseId : "";
        String fallback = self.resolveFallbackSku(id.isBlank() ? null : id);
        long skuCount = skuCatalogRepository.count();
        long invLines = id.isBlank() ? 0L : deviceSkuInventoryRepository.findByIdDeviceId(id).size();
        long warehouseLots = wh.isBlank()
                ? 0L
                : warehouseInventoryRepository.findByWarehouseIdOrderByExpiryDateAsc(wh).size();
        return new DemoContext(
                id,
                DEMO_CONSUMER_PHONE,
                DEMO_CONSUMER_USER_ID,
                fallback,
                skuCount,
                invLines,
                warehouseLots,
                wh
        );
    }

    private void ensureSkus() {
        for (DemoSkuSeed seed : DEMO_SKUS) {
            if (skuCatalogRepository.findById(seed.skuId()).isPresent()) {
                // 已存在则保留运营改价/改图/下架等，避免每次启动用种子覆盖
                continue;
            }
            SkuCatalog sku = new SkuCatalog();
            sku.setSkuId(seed.skuId());
            sku.setSkuCode(skuCatalogRepository.nextSkuCode());
            sku.setSkuName(seed.name());
            sku.setPriceCents(seed.priceCents());
            sku.setWeightGrams(seed.weightGrams());
            sku.setVisionEnabled(true);
            sku.setImageUrl(seed.imageUrl());
            sku.setDescription(seed.description());
            sku.setCategory(seed.category());
            sku.setBarcode(seed.barcode());
            sku.setUnit("件");
            sku.setStatus("ACTIVE");
            sku.setShelfLifeDays(seed.shelfLifeDays());
            sku.setNearExpiryDays(seed.nearExpiryDays());
            sku.setBlockSaleDaysBeforeExpiry(seed.blockSaleDays());
            sku.setStorageType("AMBIENT");
            sku.setMinChargeConfidence(seed.minChargeConfidence());
            sku.setPurchaseCostCents(seed.purchaseCostCents());
            skuCatalogRepository.save(sku);
        }
    }

    /**
     * 选已有合格柜，或系统发号新建。返回最终演示用 deviceId。
     */
    private String ensureDevice() {
        Optional<DeviceInfo> existing = pickExistingDemoDevice();
        if (existing.isPresent()) {
            DeviceInfo device = existing.get();
            // 不写死经纬度/地址：点位由运营建档；仅修复损坏显示名
            if (DeviceNameSupport.isCorrupted(device.getDeviceName())
                    || device.getDeviceName() == null
                    || device.getDeviceName().isBlank()) {
                device.setDeviceName(DeviceNameSupport.DEMO_DEVICE_NAME);
                deviceInfoRepository.save(device);
            }
            return device.getDeviceId();
        }

        String deviceId = deviceIdService.allocateRandomDeviceId();
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(DeviceNameSupport.DEMO_DEVICE_NAME);
        device.setDeviceType("AI_CABINET_V1");
        device.setOnlineStatus("OFFLINE");
        // 地址/坐标留空，投放前由运营补录（与竞品「点位主数据」一致）
        device.setMerchantId(ensureDemoMerchantId());
        device.setLifecycleStatus("DEPLOYED");
        deviceInfoRepository.save(device);
        log.info("demo device allocated deviceId={} merchantId={}", deviceId, device.getMerchantId());
        return deviceId;
    }

    /**
     * 演示商户：复用库内已有 ACTIVE 商户，否则系统发 12 位号新建。
     * 不写死 {@code MCH-*}。
     */
    private String ensureDemoMerchantId() {
        Optional<Merchant> existing = merchantRepository.findAll().stream()
                .filter(m -> m.getMerchantId() != null && !m.getMerchantId().isBlank())
                .filter(m -> m.getStatus() == null || "ACTIVE".equalsIgnoreCase(m.getStatus()))
                .sorted((a, b) -> {
                    // 优先已有标准 12 位号，其次任意稳定排序
                    int std = Boolean.compare(
                            MerchantIdService.isStandardMerchantId(b.getMerchantId()),
                            MerchantIdService.isStandardMerchantId(a.getMerchantId()));
                    if (std != 0) {
                        return std;
                    }
                    return a.getMerchantId().compareTo(b.getMerchantId());
                })
                .findFirst();
        if (existing.isPresent()) {
            return existing.get().getMerchantId();
        }
        String merchantId = merchantIdService.allocateRandomMerchantId();
        Merchant merchant = new Merchant();
        merchant.setMerchantId(merchantId);
        merchant.setMerchantName(DEMO_MERCHANT_DISPLAY_NAME);
        merchant.setStatus("ACTIVE");
        merchant.setPlatformRateBps(1000);
        merchantRepository.save(merchant);
        log.info("demo merchant allocated merchantId={}", merchantId);
        return merchantId;
    }

    /** 只读挑选：有商户、非 CAB-*；ONLINE / DEPLOYED 优先。 */
    private Optional<String> resolveExistingDemoDeviceId() {
        return pickExistingDemoDevice().map(DeviceInfo::getDeviceId);
    }

    private Optional<DeviceInfo> pickExistingDemoDevice() {
        List<DeviceInfo> all = deviceInfoRepository.findAllOrderByDeviceIdAsc();
        return all.stream()
                .filter(d -> d.getDeviceId() != null && !d.getDeviceId().startsWith(LEGACY_ORPHAN_PREFIX))
                .filter(d -> d.getMerchantId() != null && !d.getMerchantId().isBlank())
                .min(this::compareDemoDevicePreference)
                .or(() -> all.stream()
                        .filter(d -> d.getDeviceId() != null && !d.getDeviceId().startsWith(LEGACY_ORPHAN_PREFIX))
                        .min(this::compareDemoDevicePreference));
    }

    private int compareDemoDevicePreference(DeviceInfo a, DeviceInfo b) {
        int online = Boolean.compare(isOnline(b), isOnline(a));
        if (online != 0) {
            return online;
        }
        int deployed = Boolean.compare(isDeployed(b), isDeployed(a));
        if (deployed != 0) {
            return deployed;
        }
        return a.getDeviceId().compareTo(b.getDeviceId());
    }

    private static boolean isOnline(DeviceInfo d) {
        return d.getOnlineStatus() != null && "ONLINE".equalsIgnoreCase(d.getOnlineStatus());
    }

    private static boolean isDeployed(DeviceInfo d) {
        return d.getLifecycleStatus() != null && "DEPLOYED".equalsIgnoreCase(d.getLifecycleStatus());
    }

    private void ensureDeviceInventory(String deviceId) {
        boolean lotLedger = inventoryLotService.deviceUsesLotLedger(deviceId);
        for (DemoInvSeed seed : DEMO_INVENTORY) {
            DeviceSkuInventoryId id = new DeviceSkuInventoryId(deviceId, seed.skuId());
            var existing = deviceSkuInventoryRepository.findById(id);
            if (existing.isPresent()) {
                // 已有行：不覆盖 quantity/capacity/lowThreshold；有批次账本时只同步可售汇总
                if (lotLedger) {
                    inventoryLotService.syncAggregateInventory(deviceId, seed.skuId());
                }
                continue;
            }
            DeviceSkuInventory inv = new DeviceSkuInventory();
            inv.setId(id);
            inv.setQuantity(lotLedger ? 0 : seed.quantity());
            inv.setCapacity(seed.capacity());
            inv.setLowThreshold(seed.lowThreshold());
            deviceSkuInventoryRepository.save(inv);
            if (lotLedger) {
                inventoryLotService.syncAggregateInventory(deviceId, seed.skuId());
            }
        }
    }

    private String ensureWarehouse() {
        Optional<String> existing = resolveExistingWarehouseId();
        if (existing.isPresent()) {
            seedWarehouseLotsIfEmpty(existing.get());
            return existing.get();
        }
        String warehouseId = warehouseSupplierIdService.allocateWarehouseId();
        Warehouse wh = new Warehouse();
        wh.setWarehouseId(warehouseId);
        wh.setWarehouseName("演示中心仓");
        // 地址不写死城市；运营后续用 AddressPicker 补
        wh.setStatus("ACTIVE");
        warehouseRepository.save(wh);
        log.info("demo warehouse allocated warehouseId={}", warehouseId);
        seedWarehouseLotsIfEmpty(warehouseId);
        return warehouseId;
    }

    /** S1 台子补齐：演示供应商（采购/应付链路依赖；WipePlatform 会清掉，ensure 时重建）。 */
    private void ensureSupplier() {
        boolean exists = supplierRepository.findAll().stream()
                .anyMatch(s -> s.getSupplierId() != null && !s.getSupplierId().isBlank());
        if (exists) {
            return;
        }
        Supplier supplier = new Supplier();
        supplier.setSupplierId(warehouseSupplierIdService.allocateSupplierId());
        supplier.setSupplierName("演示供应商");
        supplier.setContactName("演示联系人");
        supplier.setContactPhone("13800000001");
        supplier.setStatus("ACTIVE");
        supplier.setPaymentTermsDays(30);
        supplierRepository.insert(supplier);
        log.info("demo supplier allocated supplierId={}", supplier.getSupplierId());
    }

    /**
     * S1 台子补齐：把有库存的 SKU 依序绑到空货道（A1、A2…）。
     * 模板默认空陈列（lessons #214），但订单行的货道号来自下单时的 slot 绑定——
     * 不绑的话运营订单列表「货道」列永远「暂无」，且购物流程无法按货道出库。
     * 🔴 必须 1 SKU=1 货道：结算回填 slot 的前提是「SKU 唯一绑定某货道」
     *（SettlementOrderSupport.inferSlotBySku 对多货道 SKU 置 null）。
     * 只填空货道、不动已有绑定；库存为 0 的 SKU 不绑（避免可售假象）。
     */
    private void bindInventorySkuToSlots(String deviceId) {
        List<DeviceSlot> slots = deviceSlotMapper.findByIdDeviceIdOrderByRowNoAscColNoAsc(deviceId);
        java.util.LinkedHashMap<String, Integer> qtyBySku = new java.util.LinkedHashMap<>();
        deviceSkuInventoryRepository.findByIdDeviceId(deviceId).forEach(inv ->
                qtyBySku.put(inv.getSkuId(), inv.getQuantity()));
        java.util.Set<String> usedSkus = new java.util.HashSet<>();
        for (DeviceSlot slot : slots) {
            if (slot.getAssignedSkuId() != null && !slot.getAssignedSkuId().isBlank()) {
                usedSkus.add(slot.getAssignedSkuId());
                continue;
            }
            String skuId = nextAvailableSku(qtyBySku, usedSkus);
            if (skuId == null) {
                break;
            }
            slot.setAssignedSkuId(skuId);
            usedSkus.add(skuId);
            deviceSlotMapper.save(slot);
            log.info("demo slot bound device={} slot={} sku={}", deviceId, slot.getSlotCode(), skuId);
        }
    }

    /** 取下一个有库存且未被占用（1 SKU=1 货道）的 SKU。 */
    private String nextAvailableSku(java.util.LinkedHashMap<String, Integer> qtyBySku,
                                    java.util.Set<String> usedSkus) {
        for (java.util.Map.Entry<String, Integer> e : qtyBySku.entrySet()) {
            if (!usedSkus.contains(e.getKey()) && e.getValue() != null && e.getValue() > 0) {
                return e.getKey();
            }
        }
        return null;
    }

    private Optional<String> resolveExistingWarehouseId() {
        return warehouseRepository.findAll().stream()
                .filter(w -> w.getWarehouseId() != null && !w.getWarehouseId().isBlank())
                .filter(w -> w.getStatus() == null || "ACTIVE".equalsIgnoreCase(w.getStatus()))
                .map(Warehouse::getWarehouseId)
                .min((a, b) -> {
                    int std = Boolean.compare(
                            WarehouseSupplierIdService.isStandardId(b),
                            WarehouseSupplierIdService.isStandardId(a));
                    return std != 0 ? std : a.compareTo(b);
                });
    }

    private void seedWarehouseLotsIfEmpty(String warehouseId) {
        LocalDate today = LocalDate.now();
        for (DemoWhSeed seed : DEMO_WAREHOUSE_LOTS) {
            if (warehouseInventoryRepository
                    .findByWarehouseIdAndSkuIdAndBatchNo(warehouseId, seed.skuId(), seed.batchNo())
                    .isEmpty()) {
                WarehouseInventory lot = new WarehouseInventory();
                lot.setWarehouseId(warehouseId);
                lot.setSkuId(seed.skuId());
                lot.setBatchNo(seed.batchNo());
                lot.setProductionDate(today.minusDays(seed.productionDaysAgo()));
                lot.setExpiryDate(today.plusDays(seed.expiryDaysAhead()));
                lot.setQuantity(seed.quantity());
                warehouseInventoryRepository.save(lot);
            }
        }
    }

    private void ensureVisionMappings() {
        if (skuVisionMappingRepository.count() > 0) {
            return;
        }
        for (DemoVisionSeed seed : DEMO_VISION_MAPPINGS) {
            SkuVisionMapping mapping = new SkuVisionMapping();
            mapping.setClassName(seed.className());
            mapping.setSkuId(seed.skuId());
            mapping.setMinConfidence(seed.minConfidence());
            mapping.setMappingSource("YOLO_COCO");
            skuVisionMappingRepository.save(mapping);
        }
    }

    private void ensureConsumerUser() {
        if (!userInfoRepository.existsById(DEMO_CONSUMER_USER_ID)) {
            UserInfo user = new UserInfo();
            user.setUserId(DEMO_CONSUMER_USER_ID);
            user.setAccountType(CabinetConstants.ACCOUNT_TYPE_CONSUMER);
            user.setPhoneNumber(DEMO_CONSUMER_PHONE);
            user.setName(DEMO_CONSUMER_NAME);
            user.setVerified(true);
            userInfoRepository.save(user);
        }
        if (!userAccountRepository.existsById(DEMO_CONSUMER_USER_ID)) {
            UserAccount account = new UserAccount();
            account.setUserId(DEMO_CONSUMER_USER_ID);
            account.setBalanceCents(10000);
            userAccountRepository.save(account);
        }
    }

    public record DemoContext(
            String deviceId,
            String consumerPhone,
            long consumerUserId,
            String fallbackSkuId,
            long skuCount,
            long deviceInventoryLines,
            long warehouseLotCount,
            String warehouseId
    ) {}

    private record DemoSkuSeed(
            String skuId, String name, int priceCents, int weightGrams, int purchaseCostCents,
            String imageUrl, String description, String category, String barcode,
            int shelfLifeDays, int nearExpiryDays, int blockSaleDays,
            float minChargeConfidence
    ) {}

    private record DemoInvSeed(String skuId, int quantity, int capacity, int lowThreshold) {}

    private record DemoWhSeed(String skuId, String batchNo, int productionDaysAgo, int expiryDaysAhead, int quantity) {}

    private record DemoVisionSeed(String className, String skuId, float minConfidence) {}

    private static final List<DemoSkuSeed> DEMO_SKUS = List.of(
            new DemoSkuSeed(SKU_DEMO_001, "可口可乐 330ml", 350, 330, 190,
                    "/admin/sku-demo/cola.jpg", "经典可乐", "饮料", "6901028300018", 270, 7, 0, 0.92f),
            new DemoSkuSeed(SKU_SODA_001, "雪碧 500ml", 400, 500, 220,
                    "/admin/sku-demo/sprite.jpg", "柠檬味汽水", "饮料", "6901028300019", 270, 7, 0, 0.80f),
            new DemoSkuSeed(SKU_WATER_001, "矿泉水 550ml", 200, 550, 110,
                    "/admin/sku-demo/water.jpg", "饮用天然水", "饮料", "6901028300021", 365, 14, 0, 0.92f),
            new DemoSkuSeed(SKU_SNACK_001, "原味薯片 70g", 650, 70, 360,
                    "/admin/sku-demo/chips.jpg", "休闲零食", "零食", "6901028300022", 180, 7, 0, 0.92f),
            new DemoSkuSeed(SKU_MILK_001, "纯牛奶 250ml", 450, 250, 250,
                    "/admin/sku-demo/milk.jpg", "常温灭菌乳", "乳品", "6901028300023", 180, 5, 1, 0.92f),
            new DemoSkuSeed(SKU_NOODLE_001, "红烧牛肉面", 520, 120, 290,
                    "/admin/sku-demo/noodle.jpg", "方便食品", "方便食品", "6901028300024", 270, 7, 0, 0.92f)
    );

    private static final List<DemoInvSeed> DEMO_INVENTORY = List.of(
            new DemoInvSeed(SKU_DEMO_001, 3, 20, 5),
            new DemoInvSeed(SKU_SODA_001, 4, 20, 5),
            new DemoInvSeed(SKU_WATER_001, 8, 24, 6),
            new DemoInvSeed(SKU_SNACK_001, 2, 16, 4),
            new DemoInvSeed(SKU_MILK_001, 1, 12, 3),
            new DemoInvSeed(SKU_NOODLE_001, 5, 16, 4)
    );

    private static final List<DemoWhSeed> DEMO_WAREHOUSE_LOTS = List.of(
            new DemoWhSeed(SKU_DEMO_001, "B-WH-COLA-01", 10, 260, 80),
            new DemoWhSeed(SKU_SODA_001, "B-WH-SPRITE-01", 8, 262, 60),
            new DemoWhSeed(SKU_WATER_001, "B-WH-WATER-01", 5, 360, 100),
            new DemoWhSeed(SKU_SNACK_001, "B-WH-CHIPS-01", 15, 165, 40),
            new DemoWhSeed(SKU_MILK_001, "B-WH-MILK-01", 3, 177, 30),
            new DemoWhSeed(SKU_NOODLE_001, "B-WH-NOODLE-01", 20, 250, 50)
    );

    private static final List<DemoVisionSeed> DEMO_VISION_MAPPINGS = List.of(
            new DemoVisionSeed("bottle", SKU_DEMO_001, 0.5f),
            new DemoVisionSeed("cup", SKU_DEMO_001, 0.5f),
            new DemoVisionSeed("can", SKU_SODA_001, 0.5f),
            new DemoVisionSeed("bowl", SKU_NOODLE_001, 0.5f)
    );
}
