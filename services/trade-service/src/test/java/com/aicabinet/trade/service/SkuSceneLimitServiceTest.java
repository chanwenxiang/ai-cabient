package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.SkuSceneLimit;
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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 编辑库存容量的场景限额校验（2026-10-22 用户反馈：限额 4 配置后编辑容量仍可填 12/8）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkuSceneLimitServiceTest {

    @Mock SkuSceneLimitMapper limitRepository;
    @Mock SkuCatalogMapper skuCatalogRepository;

    @InjectMocks SkuSceneLimitService service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 需 TableInfo 缓存（M01 门禁口径）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SkuSceneLimit.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SkuCatalog.class);
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
}
