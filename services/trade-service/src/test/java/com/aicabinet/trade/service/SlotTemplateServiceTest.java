package com.aicabinet.trade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.aicabinet.trade.domain.DeviceSkuPrice;
import com.aicabinet.trade.domain.DeviceSkuPriceId;
import com.aicabinet.trade.domain.DeviceSlot;
import com.aicabinet.trade.domain.DeviceSlotId;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.SlotTemplate;
import com.aicabinet.trade.domain.SlotTemplateLine;
import com.aicabinet.trade.mapper.DeviceSkuPriceMapper;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SlotTemplateLineMapper;
import com.aicabinet.trade.mapper.SlotTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link SlotTemplateService} 钉住测试（C3 商品模板）。
 *
 * <p>钉四件事：①空绑定设备不可存模板（防空模板）；②套用时缺失货道补骨架（骨架字段
 * 从模板行来）；③价格 upsert（缺失创建/存在更新）；④目录缺失 SKU 跳过不炸整个套用
 * （对齐 BUG-015 防御：模板引用的 SKU 可能已被删除）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlotTemplateServiceTest {

    @Mock SlotTemplateMapper templateMapper;
    @Mock SlotTemplateLineMapper lineMapper;
    @Mock DeviceSlotMapper slotMapper;
    @Mock DeviceSkuPriceMapper priceMapper;
    @Mock SkuCatalogMapper skuCatalogMapper;
    @Mock com.aicabinet.trade.mapper.DeviceInfoMapper deviceMapper;
    @Mock com.aicabinet.trade.mapper.SkuSceneLimitMapper skuSceneLimitMapper;

    private SlotTemplateService service;

    @BeforeEach
    void setUp() {
        service = new SlotTemplateService(templateMapper, lineMapper, slotMapper, priceMapper,
                skuCatalogMapper, deviceMapper,
                new SkuSceneLimitService(skuSceneLimitMapper, skuCatalogMapper));
        // 场景限额（CB-049 扩展）：测试柜无场景 → limitQtyBySku 返回空 Map（不限制）
        lenient().when(deviceMapper.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        lenient().when(templateMapper.selectCount(any())).thenReturn(0L);
        lenient().when(templateMapper.insert(any(SlotTemplate.class))).thenAnswer(inv -> {
            inv.getArgument(0, SlotTemplate.class).setTemplateId(9L);
            return 1;
        });
        lenient().when(lineMapper.insert(any(SlotTemplateLine.class))).thenReturn(1);
        SkuCatalog knownSku = new SkuCatalog();
        knownSku.setSkuId("SKU-A");
        lenient().when(skuCatalogMapper.findById("SKU-A")).thenReturn(Optional.of(knownSku));
        lenient().when(skuCatalogMapper.findById("SKU-GONE")).thenReturn(Optional.empty());
        lenient().when(slotMapper.findById(any(DeviceSlotId.class))).thenReturn(Optional.empty());
        lenient().when(priceMapper.findByDeviceIdAndSkuId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(slotMapper.save(any(DeviceSlot.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(priceMapper.save(any(DeviceSkuPrice.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void saveFromDevice_emptyBoundDevice_rejected() {
        when(slotMapper.selectList(any())).thenReturn(List.of(
                slot("A1", null, 8, 500)));

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.saveFromDevice(9L, "T1", "D1"));
        verify(templateMapper, never()).insert(any(SlotTemplate.class));
    }

    @Test
    void apply_missingSlot_createsSkeletonFromTemplateLine() {
        SlotTemplate template = new SlotTemplate();
        template.setTemplateId(9L);
        template.setTemplateName("T1");
        when(templateMapper.selectById(9L)).thenReturn(template);
        when(lineMapper.findByTemplateId(9L)).thenReturn(List.of(
                line("C3", 3, 3, "SKU-A", 500, 8)));
        when(slotMapper.findById(new DeviceSlotId("D2", "C3"))).thenReturn(Optional.empty());

        int applied = service.apply(9L, 9L, "D2");

        assertEquals(1, applied);
        ArgumentCaptor<DeviceSlot> captor = ArgumentCaptor.forClass(DeviceSlot.class);
        verify(slotMapper).save(captor.capture());
        DeviceSlot saved = captor.getValue();
        assertEquals("D2", saved.getDeviceId());
        assertEquals("C3", saved.getSlotCode());
        assertEquals("SKU-A", saved.getAssignedSkuId());
        assertEquals(8, saved.getParLevel());
        assertEquals(2, saved.getMinLevel(), "minLevel 缺省=par/4（≥1）");
        verify(priceMapper).save(argThat(p ->
                "D2".equals(p.getDeviceId()) && "SKU-A".equals(p.getSkuId()) && p.getPriceCents() == 500));
    }

    @Test
    void apply_priceExists_updatesNotDuplicates() {
        SlotTemplate template = new SlotTemplate();
        template.setTemplateId(9L);
        when(templateMapper.selectById(9L)).thenReturn(template);
        when(lineMapper.findByTemplateId(9L)).thenReturn(List.of(
                line("A1", 1, 1, "SKU-A", 600, 8)));
        DeviceSlot existing = slot("A1", "SKU-A", 8, 500);
        existing.setDeviceId("D2");
        when(slotMapper.findById(new DeviceSlotId("D2", "A1"))).thenReturn(Optional.of(existing));
        DeviceSkuPrice existingPrice = new DeviceSkuPrice();
        existingPrice.setDeviceId("D2");
        existingPrice.setSkuId("SKU-A");
        existingPrice.setPriceCents(500);
        when(priceMapper.findByDeviceIdAndSkuId("D2", "SKU-A")).thenReturn(Optional.of(existingPrice));

        int applied = service.apply(9L, 9L, "D2");

        assertEquals(1, applied);
        verify(priceMapper).save(argThat(p ->
                p.getPriceCents() == 600 && "D2".equals(p.getDeviceId())));
    }

    @Test
    void apply_missingSkuInCatalog_skipsLineButContinues() {
        SlotTemplate template = new SlotTemplate();
        template.setTemplateId(9L);
        when(templateMapper.selectById(9L)).thenReturn(template);
        when(lineMapper.findByTemplateId(9L)).thenReturn(List.of(
                line("A1", 1, 1, "SKU-GONE", 500, 8),
                line("A2", 1, 2, "SKU-A", 500, 8)));

        int applied = service.apply(9L, 9L, "D2");

        assertEquals(1, applied, "目录缺失 SKU 的行跳过，其余行继续生效");
        verify(slotMapper).save(argThat(s -> "A2".equals(s.getSlotCode())));
    }

    private static DeviceSlot slot(String code, String sku, int par, int priceCents) {
        DeviceSlot slot = new DeviceSlot();
        slot.setDeviceId("D1");
        slot.setSlotCode(code);
        slot.setAssignedSkuId(sku);
        slot.setParLevel(par);
        return slot;
    }

    private static SlotTemplateLine line(String code, int row, int col, String sku, long price, int par) {
        SlotTemplateLine line = new SlotTemplateLine();
        line.setSlotCode(code);
        line.setRowNo(row);
        line.setColNo(col);
        line.setSkuId(sku);
        line.setPriceCents(price);
        line.setParLevel(par);
        return line;
    }
}
