package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.SkuSceneLimit;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuSceneLimitMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 编辑库存容量的场景限额校验（2026-10-22 用户反馈：限额 4 配置后编辑容量仍可填 12/8）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkuSceneLimitServiceTest {

    @Mock SkuSceneLimitMapper limitRepository;
    @Mock SkuCatalogMapper skuCatalogRepository;
    @Mock DeviceSkuInventoryMapper inventoryRepository;

    @InjectMocks SkuSceneLimitService service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 需 TableInfo 缓存（M01 门禁口径）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SkuSceneLimit.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SkuCatalog.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), DeviceSkuInventory.class);
    }

    private SkuSceneLimit limit(int maxQty) {
        SkuSceneLimit l = new SkuSceneLimit();
        l.setSkuId("100013");
        l.setSceneType("OFFICE");
        l.setMaxQty(maxQty);
        return l;
    }

    @Test
    void capacityOverLimit_rejectedWithReadableMessage() {
        when(limitRepository.selectOne(any())).thenReturn(limit(4));
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("100013");
        sku.setSkuName("雪碧柠檬味汽水330ml");
        when(skuCatalogRepository.findById("100013")).thenReturn(Optional.of(sku));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assertCapacityWithinSceneLimit("OFFICE", "100013", 12));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void capacityAtLimit_allowed() {
        when(limitRepository.selectOne(any())).thenReturn(limit(4));
        service.assertCapacityWithinSceneLimit("OFFICE", "100013", 4);
    }

    @Test
    void noLimitEntry_noRestriction() {
        when(limitRepository.selectOne(any())).thenReturn(null);
        service.assertCapacityWithinSceneLimit("OFFICE", "100013", 99);
    }

    @Test
    void blankScene_noRestriction() {
        service.assertCapacityWithinSceneLimit("", "100013", 99);
    }

    // ── 换场景预检（2026-10-23 用户拍板「预检拒绝」） ──────────────────────────

    private DeviceSkuInventory inventory(String skuId, int capacity) {
        DeviceSkuInventory inv = new DeviceSkuInventory();
        inv.setDeviceId("EG943322621391");
        inv.setSkuId(skuId);
        inv.setQuantity(1);
        inv.setCapacity(capacity);
        return inv;
    }

    private SkuSceneLimit limitFor(String skuId, String scene, int maxQty) {
        SkuSceneLimit l = new SkuSceneLimit();
        l.setSkuId(skuId);
        l.setSceneType(scene);
        l.setMaxQty(maxQty);
        return l;
    }

    @Test
    void sceneSwitch_overflow_rejected_listingAllOffenders() {
        when(inventoryRepository.selectList(any())).thenReturn(List.of(
                inventory("100013", 8), inventory("100014", 6), inventory("100015", 1)));
        when(limitRepository.selectList(any())).thenReturn(List.of(
                limitFor("100013", "CAMPUS", 4), limitFor("100014", "CAMPUS", 6)));
        when(skuCatalogRepository.findById("100013")).thenReturn(Optional.of(
                newSku("100013", "雪碧柠檬味汽水330ml")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assertSceneSwitchAllowed("EG943322621391", "CAMPUS"));
        assertEquals(400, ex.getStatusCode().value());
        // 一次性列出全部超限商品：超限的 100013 在列；恰好等于上限的 100014 与未配置限额的 100015 不在列
        String msg = ex.getReason();
        assertTrue(msg.contains("雪碧柠檬味汽水330ml"), "超限商品应被列出：" + msg);
        assertTrue(msg.contains("上限 4"), "应带上限数值：" + msg);
        assertTrue(msg.contains("当前 8"), "应带当前容量：" + msg);
        // 恰好等于上限（100014）与未配置限额（100015）不算超限——两者名称未 mock，超限会以 skuId 兜底显示
        assertTrue(!msg.contains("100014") && !msg.contains("100015"),
                "等于上限/未配限额的 SKU 不应被列出：" + msg);
    }

    @Test
    void sceneSwitch_withinLimit_allowed() {
        when(inventoryRepository.selectList(any())).thenReturn(List.of(inventory("100013", 4)));
        when(limitRepository.selectList(any())).thenReturn(List.of(limitFor("100013", "CAMPUS", 4)));
        assertDoesNotThrow(() -> service.assertSceneSwitchAllowed("EG943322621391", "CAMPUS"));
    }

    @Test
    void sceneSwitch_unknownScene_rejectedFailClosed() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assertSceneSwitchAllowed("EG943322621391", "MOON"));
        assertEquals(400, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("未知投放场景"));
    }

    @Test
    void sceneSwitch_noInventory_allowed() {
        when(inventoryRepository.selectList(any())).thenReturn(List.of());
        assertDoesNotThrow(() -> service.assertSceneSwitchAllowed("EG943322621391", "CAMPUS"));
    }

    @Test
    void sceneSwitch_blankScene_clearing_allowed() {
        assertDoesNotThrow(() -> service.assertSceneSwitchAllowed("EG943322621391", " "));
    }

    private SkuCatalog newSku(String skuId, String name) {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId(skuId);
        sku.setSkuName(name);
        return sku;
    }
}
