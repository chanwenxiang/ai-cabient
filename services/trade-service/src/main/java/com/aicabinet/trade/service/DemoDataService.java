package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SkuQuantityDto;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.DeviceNameSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);

    public static final String DEMO_DEVICE_ID = "CAB-001";
    public static final String DEMO_WAREHOUSE_ID = "WH-DEMO-001";
    public static final long DEMO_CONSUMER_USER_ID = 10001L;
    public static final String DEMO_CONSUMER_PHONE = "13800138000";

    private final SecurityProperties securityProperties;
    private final SkuCatalogMapper skuCatalogRepository;
    private final DeviceInfoMapper deviceInfoRepository;
    private final DeviceSkuInventoryMapper deviceSkuInventoryRepository;
    private final WarehouseMapper warehouseRepository;
    private final WarehouseInventoryMapper warehouseInventoryRepository;
    private final SkuVisionMappingMapper skuVisionMappingRepository;
    private final UserInfoMapper userInfoRepository;
    private final UserAccountMapper userAccountRepository;
    private final DeviceSlotService deviceSlotService;
    private final InventoryLotService inventoryLotService;

    public DemoDataService(SecurityProperties securityProperties,
                           SkuCatalogMapper skuCatalogRepository,
                           DeviceInfoMapper deviceInfoRepository,
                           DeviceSkuInventoryMapper deviceSkuInventoryRepository,
                           WarehouseMapper warehouseRepository,
                           WarehouseInventoryMapper warehouseInventoryRepository,
                           SkuVisionMappingMapper skuVisionMappingRepository,
                           UserInfoMapper userInfoRepository,
                           UserAccountMapper userAccountRepository,
                           DeviceSlotService deviceSlotService,
                           InventoryLotService inventoryLotService) {
        this.securityProperties = securityProperties;
        this.skuCatalogRepository = skuCatalogRepository;
        this.deviceInfoRepository = deviceInfoRepository;
        this.deviceSkuInventoryRepository = deviceSkuInventoryRepository;
        this.warehouseRepository = warehouseRepository;
        this.warehouseInventoryRepository = warehouseInventoryRepository;
        this.skuVisionMappingRepository = skuVisionMappingRepository;
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.deviceSlotService = deviceSlotService;
        this.inventoryLotService = inventoryLotService;
    }

    @Transactional
    public DemoContext ensureDemoData() {
        if (!securityProperties.mockEnabled()) {
            return buildContext();
        }
        ensureSkus();
        ensureDevice();
        ensureDeviceInventory();
        deviceSlotService.ensureDefaultSlots(DEMO_DEVICE_ID);
        ensureWarehouse();
        ensureVisionMappings();
        ensureConsumerUser();
        DemoContext ctx = buildContext();
        log.info("demo data ensured device={} skus={} fallbackSku={} warehouseLots={}",
                ctx.deviceId(), ctx.skuCount(), ctx.fallbackSkuId(), ctx.warehouseLotCount());
        return ctx;
    }

    @Transactional(readOnly = true)
    public DemoContext getContext() {
        return buildContext();
    }

    /**
     * 识别兜底：取柜内首个有可售库存、可视觉结算的 SKU（与真实业务一致，不再写死 SKU-DEMO-001）。
     */
    @Transactional(readOnly = true)
    public String resolveFallbackSku(String deviceId) {
        String targetDevice = deviceId != null && !deviceId.isBlank() ? deviceId.trim() : DEMO_DEVICE_ID;
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
                .orElse("SKU-DEMO-001");
    }

    private boolean isChargeableSku(String skuId) {
        return skuCatalogRepository.findById(skuId).map(this::isChargeableSkuEntity).orElse(false);
    }

    private boolean isChargeableSkuEntity(SkuCatalog sku) {
        return sku.isVisionEnabled() && "ACTIVE".equalsIgnoreCase(sku.getStatus());
    }

    private DemoContext buildContext() {
        String fallback = resolveFallbackSku(DEMO_DEVICE_ID);
        long skuCount = skuCatalogRepository.count();
        long invLines = deviceSkuInventoryRepository.findByIdDeviceId(DEMO_DEVICE_ID).size();
        long warehouseLots = warehouseInventoryRepository.findByWarehouseIdOrderByExpiryDateAsc(DEMO_WAREHOUSE_ID).size();
        return new DemoContext(
                DEMO_DEVICE_ID,
                DEMO_CONSUMER_PHONE,
                DEMO_CONSUMER_USER_ID,
                fallback,
                skuCount,
                invLines,
                warehouseLots
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

    private void ensureDevice() {
        DeviceInfo device = deviceInfoRepository.findById(DEMO_DEVICE_ID).orElse(null);
        if (device == null) {
            device = new DeviceInfo();
            device.setDeviceId(DEMO_DEVICE_ID);
            device.setDeviceName("测试柜-001");
            device.setDeviceType("AI_CABINET_V1");
            device.setOnlineStatus("OFFLINE");
            device.setLatitude(31.2304);
            device.setLongitude(121.4737);
            device.setAddress("上海市黄浦区演示点位");
            deviceInfoRepository.save(device);
            return;
        }
        String repaired = DeviceNameSupport.canonicalIfCorrupted(DEMO_DEVICE_ID, device.getDeviceName());
        boolean dirty = repaired != null;
        if (repaired != null) {
            device.setDeviceName(repaired);
        }
        if (device.getLatitude() == null || device.getLongitude() == null) {
            device.setLatitude(31.2304);
            device.setLongitude(121.4737);
            if (device.getAddress() == null || device.getAddress().isBlank()) {
                device.setAddress("上海市黄浦区演示点位");
            }
            dirty = true;
        }
        if (dirty) {
            deviceInfoRepository.save(device);
        }
    }

    private void ensureDeviceInventory() {
        boolean lotLedger = inventoryLotService.deviceUsesLotLedger(DEMO_DEVICE_ID);
        for (DemoInvSeed seed : DEMO_INVENTORY) {
            DeviceSkuInventoryId id = new DeviceSkuInventoryId(DEMO_DEVICE_ID, seed.skuId());
            var existing = deviceSkuInventoryRepository.findById(id);
            if (existing.isPresent()) {
                // 已有行：不覆盖 quantity/capacity/lowThreshold；有批次账本时只同步可售汇总
                if (lotLedger) {
                    inventoryLotService.syncAggregateInventory(DEMO_DEVICE_ID, seed.skuId());
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
                inventoryLotService.syncAggregateInventory(DEMO_DEVICE_ID, seed.skuId());
            }
        }
    }

    private void ensureWarehouse() {
        if (!warehouseRepository.existsById(DEMO_WAREHOUSE_ID)) {
            Warehouse wh = new Warehouse();
            wh.setWarehouseId(DEMO_WAREHOUSE_ID);
            wh.setWarehouseName("演示中心仓");
            wh.setAddress("上海市浦东新区");
            warehouseRepository.save(wh);
        }
        LocalDate today = LocalDate.now();
        for (DemoWhSeed seed : DEMO_WAREHOUSE_LOTS) {
            if (warehouseInventoryRepository
                    .findByWarehouseIdAndSkuIdAndBatchNo(DEMO_WAREHOUSE_ID, seed.skuId(), seed.batchNo())
                    .isEmpty()) {
                WarehouseInventory lot = new WarehouseInventory();
                lot.setWarehouseId(DEMO_WAREHOUSE_ID);
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
            user.setPhoneNumber(DEMO_CONSUMER_PHONE);
            user.setName("测试用户");
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
            long warehouseLotCount
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
            new DemoSkuSeed("SKU-DEMO-001", "可口可乐 330ml", 350, 330, 190,
                    "/admin/sku-demo/cola.jpg", "经典可乐", "饮料", "6901028300018", 270, 7, 0, 0.92f),
            new DemoSkuSeed("SKU-SODA-001", "雪碧 500ml", 400, 500, 220,
                    "/admin/sku-demo/sprite.jpg", "柠檬味汽水", "饮料", "6901028300019", 270, 7, 0, 0.80f),
            new DemoSkuSeed("SKU-WATER-001", "矿泉水 550ml", 200, 550, 110,
                    "/admin/sku-demo/water.jpg", "饮用天然水", "饮料", "6901028300021", 365, 14, 0, 0.92f),
            new DemoSkuSeed("SKU-SNACK-001", "原味薯片 70g", 650, 70, 360,
                    "/admin/sku-demo/chips.jpg", "休闲零食", "零食", "6901028300022", 180, 7, 0, 0.92f),
            new DemoSkuSeed("SKU-MILK-001", "纯牛奶 250ml", 450, 250, 250,
                    "/admin/sku-demo/milk.jpg", "常温灭菌乳", "乳品", "6901028300023", 180, 5, 1, 0.92f),
            new DemoSkuSeed("SKU-NOODLE-001", "红烧牛肉面", 520, 120, 290,
                    "/admin/sku-demo/noodle.jpg", "方便食品", "方便食品", "6901028300024", 270, 7, 0, 0.92f)
    );

    private static final List<DemoInvSeed> DEMO_INVENTORY = List.of(
            new DemoInvSeed("SKU-DEMO-001", 3, 20, 5),
            new DemoInvSeed("SKU-SODA-001", 4, 20, 5),
            new DemoInvSeed("SKU-WATER-001", 8, 24, 6),
            new DemoInvSeed("SKU-SNACK-001", 2, 16, 4),
            new DemoInvSeed("SKU-MILK-001", 1, 12, 3),
            new DemoInvSeed("SKU-NOODLE-001", 5, 16, 4)
    );

    private static final List<DemoWhSeed> DEMO_WAREHOUSE_LOTS = List.of(
            new DemoWhSeed("SKU-DEMO-001", "B-WH-COLA-01", 10, 260, 80),
            new DemoWhSeed("SKU-SODA-001", "B-WH-SPRITE-01", 8, 262, 60),
            new DemoWhSeed("SKU-WATER-001", "B-WH-WATER-01", 5, 360, 100),
            new DemoWhSeed("SKU-SNACK-001", "B-WH-CHIPS-01", 15, 165, 40),
            new DemoWhSeed("SKU-MILK-001", "B-WH-MILK-01", 3, 177, 30),
            new DemoWhSeed("SKU-NOODLE-001", "B-WH-NOODLE-01", 20, 250, 50)
    );

    private static final List<DemoVisionSeed> DEMO_VISION_MAPPINGS = List.of(
            new DemoVisionSeed("bottle", "SKU-DEMO-001", 0.5f),
            new DemoVisionSeed("cup", "SKU-DEMO-001", 0.5f),
            new DemoVisionSeed("can", "SKU-SODA-001", 0.5f),
            new DemoVisionSeed("bowl", "SKU-NOODLE-001", 0.5f)
    );
}
