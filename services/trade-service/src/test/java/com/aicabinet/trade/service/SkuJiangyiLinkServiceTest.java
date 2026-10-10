package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiStdSku;
import com.aicabinet.trade.dto.JiangyiGatherDtos.TrainedProduct;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品库挂接（CB-023 范围 B）单测：唯一性 409 / barCode 一致性 / RETIRED 复活 /
 * pullTextName 按条码回填 / createInJiangyi 失败不落半挂。
 */
@ExtendWith(MockitoExtension.class)
class SkuJiangyiLinkServiceTest {

    private static final String SKU_ID = "SKU-WATER-001";

    @Mock private JiangyiSkuJiangyiLinkMapper linkMapper;
    @Mock private SkuCatalogMapper skuCatalogMapper;
    @Mock private JiangyiGatherClient jiangyiGatherClient;

    private SkuJiangyiLinkService service() {
        return new SkuJiangyiLinkService(linkMapper, skuCatalogMapper, jiangyiGatherClient);
    }

    private SkuCatalog sku(String barcode) {
        SkuCatalog s = new SkuCatalog();
        s.setSkuId(SKU_ID);
        s.setSkuName("农夫山泉 550ml");
        s.setBarcode(barcode);
        return s;
    }

    @Test
    void bind_conflictWhenProductAlreadyLinkedToOtherSku() {
        JiangyiSkuJiangyiLink existing = new JiangyiSkuJiangyiLink();
        existing.setId(9L);
        existing.setSkuId("SKU-OTHER");
        existing.setJiangyiProductId("88");
        existing.setSyncStatus("BOUND");
        when(linkMapper.byProduct("88")).thenReturn(Optional.of(existing));
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku("6901234567890"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().bind(SKU_ID, 88L, "农夫山泉", "6901234567890"));
        assertEquals(409, e.getStatusCode().value());
        verify(linkMapper, never()).insert(any(JiangyiSkuJiangyiLink.class));
    }

    @Test
    void bind_barCodeMismatchIsRejectedEvenThoughBarCodeIsNotUniqueKey() {
        when(linkMapper.byProduct("88")).thenReturn(Optional.empty());
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku("6901234567890"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().bind(SKU_ID, 88L, "农夫山泉", "6900000000009"));
        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getReason().contains("条形码不一致"));
        verify(linkMapper, never()).insert(any(JiangyiSkuJiangyiLink.class));
    }

    @Test
    void bind_barCodeAbsentOnEitherSideDoesNotBlock() {
        when(linkMapper.byProduct("88")).thenReturn(Optional.empty());
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku(null));

        JiangyiSkuJiangyiLink link = service().bind(SKU_ID, 88L, "农夫山泉", null);

        assertEquals("BOUND", link.getSyncStatus());
        ArgumentCaptor<JiangyiSkuJiangyiLink> captor = ArgumentCaptor.forClass(JiangyiSkuJiangyiLink.class);
        verify(linkMapper).insert(captor.capture());
        assertEquals(SKU_ID, captor.getValue().getSkuId());
        assertEquals("88", captor.getValue().getJiangyiProductId());
    }

    @Test
    void bind_retiredLinkIsRevokedNotDuplicated() {
        JiangyiSkuJiangyiLink retired = new JiangyiSkuJiangyiLink();
        retired.setId(9L);
        retired.setSkuId("SKU-OLD");
        retired.setJiangyiProductId("88");
        retired.setSyncStatus("RETIRED");
        when(linkMapper.byProduct("88")).thenReturn(Optional.of(retired));
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku("6901234567890"));

        JiangyiSkuJiangyiLink link = service().bind(SKU_ID, 88L, "农夫山泉", "6901234567890");

        assertEquals(9L, link.getId());
        assertEquals(SKU_ID, link.getSkuId());
        assertEquals("BOUND", link.getSyncStatus());
        verify(linkMapper).updateById(link);
        verify(linkMapper, never()).insert(any(JiangyiSkuJiangyiLink.class));
    }

    @Test
    void createInJiangyi_clientFailureMeansNoLinkRow() {
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku("6901234567890"));
        when(jiangyiGatherClient.productCreate(any())).thenThrow(new IllegalStateException("将邑 500"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().createInJiangyi(SKU_ID, new SkuJiangyiLinkService.ProductCreateSpec(
                        "农夫山泉 550ml", "550ml", 238L, "农夫山泉", "1.5", "2.0",
                        "http://img/1.png", null, "bottle", "6901234567890")));
        assertEquals(502, e.getStatusCode().value());
        verify(linkMapper, never()).insert(any(JiangyiSkuJiangyiLink.class));
    }

    @Test
    void createInJiangyi_successFallsLinkAsCreatedWithDefaultColorZero() {
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku("6901234567890"));
        when(jiangyiGatherClient.productCreate(any())).thenReturn(123L);

        JiangyiSkuJiangyiLink link = service().createInJiangyi(SKU_ID,
                new SkuJiangyiLinkService.ProductCreateSpec(
                        "农夫山泉 550ml", "550ml", 238L, "农夫山泉", "1.5", "2.0",
                        "http://img/1.png", null, "bottle", "6901234567890"));

        assertEquals("CREATED", link.getSyncStatus());
        assertEquals("123", link.getJiangyiProductId());
        verify(linkMapper).insert(link);
    }

    @Test
    void pullTextName_withoutOwnBarcodeIsRejected() {
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku(null));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pullTextName(SKU_ID));
        assertEquals(409, e.getStatusCode().value());
    }

    @Test
    void pullTextName_fillsTextNameByBarCodeFromTrainedProducts() {
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku("6901234567890"));
        when(jiangyiGatherClient.trainedProducts()).thenReturn(List.of(
                new TrainedProduct(1L, "农夫山泉 550ml", "农夫山泉 550ml", "6901234567890"),
                new TrainedProduct(2L, "可口可乐", "可口可乐 330ml", "6901111111111")));
        JiangyiSkuJiangyiLink link = new JiangyiSkuJiangyiLink();
        link.setId(9L);
        link.setSkuId(SKU_ID);
        link.setJiangyiProductId("123");
        when(linkMapper.listBySku(SKU_ID)).thenReturn(List.of(link));

        int updated = service().pullTextName(SKU_ID);

        assertEquals(1, updated);
        assertEquals("农夫山泉 550ml", link.getJiangyiTextName());
        verify(linkMapper).updateById(link);
    }

    @Test
    void pullTextName_noTrainedMatchIs404() {
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku("6901234567890"));
        when(jiangyiGatherClient.trainedProducts()).thenReturn(List.of());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pullTextName(SKU_ID));
        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    void search_marksLinkedProducts() {
        when(jiangyiGatherClient.stdSkuList("农夫山泉", null)).thenReturn(List.of(
                new JiangyiStdSku(88L, "农夫山泉 550ml", "6901234567890", "550ml",
                        "农夫山泉", "bottle", "http://img/1.png", "STD-001"),
                new JiangyiStdSku(99L, "可口可乐 330ml", "6901111111111", "330ml",
                        "可口可乐", "bottle", "http://img/2.png", "STD-002")));
        when(linkMapper.listBoundProductIds(anyList())).thenReturn(List.of("88"));

        List<SkuJiangyiLinkService.StdSkuView> views = service().searchJiangyiProducts("农夫山泉", null);

        assertEquals(2, views.size());
        assertTrue(views.get(0).linked());
        assertFalse(views.get(1).linked());
    }

    @Test
    void unbind_missingLinkIs404AndExistingGoesRetired() {
        when(linkMapper.selectById(404L)).thenReturn(null);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().unbind(404L));
        assertEquals(404, e.getStatusCode().value());

        JiangyiSkuJiangyiLink link = new JiangyiSkuJiangyiLink();
        link.setId(9L);
        link.setSkuId(SKU_ID);
        link.setSyncStatus("BOUND");
        when(linkMapper.selectById(9L)).thenReturn(link);
        service().unbind(9L);
        assertEquals("RETIRED", link.getSyncStatus());
        verify(linkMapper).updateById(link);
    }

    @Test
    void bind_unknownSkuIs404() {
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(null);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().bind(SKU_ID, 88L, "x", null));
        assertEquals(404, e.getStatusCode().value());
    }
}
