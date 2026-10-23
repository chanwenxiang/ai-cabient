package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.dto.JiangyiGatherDtos.GatherPendingProduct;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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
        return new SkuJiangyiLinkService(linkMapper, skuCatalogMapper, jiangyiGatherClient,
                "https://public.example.com");
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
    void bind_barCodeMismatchIsLoggedButNotBlocked() {
        // 2026-10-21 拍板：69 码是罐身固有属性，同款不同包装版本条码不同，不做硬阻断
        when(linkMapper.byProduct("88")).thenReturn(Optional.empty());
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku("6901234567890"));

        JiangyiSkuJiangyiLink link = service().bind(SKU_ID, 88L, "农夫山泉", "6900000000009");

        assertEquals("BOUND", link.getSyncStatus());
        assertEquals("6900000000009", link.getBarCode());
        verify(linkMapper).insert(link);
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
    void createInJiangyi_blankMainImgFallsBackToSkuImage() {
        SkuCatalog s = sku("6901234567890");
        s.setImageUrl("http://img/sku-main.png");
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(s);
        when(jiangyiGatherClient.productCreate(any())).thenReturn(123L);

        service().createInJiangyi(SKU_ID, new SkuJiangyiLinkService.ProductCreateSpec(
                "农夫山泉 550ml", "550ml", 238L, "农夫山泉", "1.5", "2.0",
                " ", null, "bottle", "6901234567890"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> body =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(jiangyiGatherClient).productCreate(body.capture());
        assertEquals("http://img/sku-main.png", body.getValue().get("mainImg"));
    }

    @Test
    void createInJiangyi_relativeMainImgIsSentAsPublicAbsoluteUrl() {
        SkuCatalog s = sku("6901234567890");
        s.setImageUrl("/api/v2/media/sku-images/1");
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(s);
        when(jiangyiGatherClient.productCreate(any())).thenReturn(123L);

        service().createInJiangyi(SKU_ID, new SkuJiangyiLinkService.ProductCreateSpec(
                "330ml雪碧柠檬味汽水", "330ml", 4465L, "雪碧", "1.5", "3.0",
                "", null, "bottle", "6928804010145"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> body =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(jiangyiGatherClient).productCreate(body.capture());
        assertEquals("https://public.example.com/api/v2/media/sku-images/1", body.getValue().get("mainImg"));
    }

    @Test
    void createInJiangyi_noImageAnywhereIs409WithoutCallingJiangyi() {
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku("6901234567890"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().createInJiangyi(SKU_ID, new SkuJiangyiLinkService.ProductCreateSpec(
                        "农夫山泉 550ml", "550ml", 238L, "农夫山泉", "1.5", "2.0",
                        "", null, "bottle", "6901234567890")));
        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getReason().contains("商品主图"));
        verify(jiangyiGatherClient, never()).productCreate(any());
        verify(linkMapper, never()).insert(any(JiangyiSkuJiangyiLink.class));
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
    void pullTextName_multipleTextNamesUnderSameBarCodeAre409AndTouchNothing() {
        // 2026-10-22 拍板：同一条码命中多条且 textName 不同 ⇒ 不猜、不写、要求人工
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku("6901234567890"));
        when(jiangyiGatherClient.trainedProducts()).thenReturn(List.of(
                new TrainedProduct(1L, "雪碧清爽柠檬味汽水普通听装330ml",
                        "kkkl_xb_qsnmw_pttz_330ml", "6901234567890"),
                new TrainedProduct(2L, "test雪碧听装330ml", "test_xb_tz330ml", "6901234567890")));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pullTextName(SKU_ID));

        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getReason().contains("kkkl_xb_qsnmw_pttz_330ml"));
        assertTrue(e.getReason().contains("test_xb_tz330ml"));
        verify(linkMapper, never()).listBySku(any());
        verify(linkMapper, never()).updateById(any(JiangyiSkuJiangyiLink.class));
    }

    @Test
    void pullTextName_duplicateTextNamesStillFill() {
        // 同 textName 的重复学习行不算歧义（同一解码键），仍按原行为回填
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku("6901234567890"));
        when(jiangyiGatherClient.trainedProducts()).thenReturn(List.of(
                new TrainedProduct(1L, "雪碧 330ml 一版", "kkkl_xb_qsnmw_pttz_330ml", "6901234567890"),
                new TrainedProduct(2L, "雪碧 330ml 二版", "kkkl_xb_qsnmw_pttz_330ml", "6901234567890")));
        JiangyiSkuJiangyiLink link = new JiangyiSkuJiangyiLink();
        link.setId(9L);
        link.setSkuId(SKU_ID);
        when(linkMapper.listBySku(SKU_ID)).thenReturn(List.of(link));

        assertEquals(1, service().pullTextName(SKU_ID));
        assertEquals("kkkl_xb_qsnmw_pttz_330ml", link.getJiangyiTextName());
    }

    @Test
    void pullTextName_blankTextNameDoesNotWipeExistingValue() {
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(sku("6901234567890"));
        when(jiangyiGatherClient.trainedProducts()).thenReturn(List.of(
                new TrainedProduct(1L, "雪碧 330ml", null, "6901234567890")));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pullTextName(SKU_ID));

        assertEquals(404, e.getStatusCode().value());
        assertTrue(e.getReason().contains("没有 textName"));
        verify(linkMapper, never()).updateById(any(JiangyiSkuJiangyiLink.class));
    }

    @Test
    void search_marksLinkedProductsAndPutsUnlinkedFirst() {
        when(jiangyiGatherClient.stdSkuList("农夫山泉", null)).thenReturn(List.of(
                new JiangyiStdSku(88L, "农夫山泉 550ml", "6901234567890", "550ml",
                        "农夫山泉", "bottle", "http://img/1.png", "STD-001", "app"),
                new JiangyiStdSku(99L, "可口可乐 330ml", "6901111111111", "330ml",
                        "可口可乐", "bottle", "http://img/2.png", "STD-002", "app")));
        when(linkMapper.listBoundProductIds(anyList())).thenReturn(List.of("88"));

        SkuJiangyiLinkService.StdSkuSearchResult result =
                service().searchJiangyiProducts("农夫山泉", null, null, false);

        assertEquals(2, result.items().size());
        assertEquals(99L, result.items().get(0).id());
        assertFalse(result.items().get(0).linked());
        assertTrue(result.items().get(1).linked());
        assertEquals("UNKNOWN", result.items().get(0).matchLevel());
        assertEquals(0, result.hidden());
    }

    @Test
    void search_exactBarcodeFirstAndVolumeMismatchHiddenUnlessIncludeAll() {
        SkuCatalog s = sku("6928804010145");
        s.setSkuName("330ml雪碧柠檬味汽水");
        s.setSpec("330毫升");
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(s);
        when(jiangyiGatherClient.stdSkuList("雪碧", null)).thenReturn(List.of(
                new JiangyiStdSku(2014L, "雪碧", "6928804010220", "500ml",
                        "雪碧", "bottle", null, null, "manual"),
                new JiangyiStdSku(2211L, "雪碧", "6954767437583", "330ml",
                        "雪碧", "bottle", null, null, "app"),
                new JiangyiStdSku(100013L, "雪碧", "6928804010145", "330ml",
                        "雪碧", "bottle", null, null, "app")));
        when(linkMapper.listBoundProductIds(anyList())).thenReturn(List.of());

        SkuJiangyiLinkService.StdSkuSearchResult filtered =
                service().searchJiangyiProducts("雪碧", null, SKU_ID, false);

        assertEquals(2, filtered.items().size());
        assertEquals(100013L, filtered.items().get(0).id());
        assertEquals("EXACT_BARCODE", filtered.items().get(0).matchLevel());
        assertEquals(2211L, filtered.items().get(1).id());
        assertEquals("SAME_SPEC", filtered.items().get(1).matchLevel());
        assertEquals(3, filtered.total());
        assertEquals(1, filtered.hidden());

        SkuJiangyiLinkService.StdSkuSearchResult all =
                service().searchJiangyiProducts("雪碧", null, SKU_ID, true);

        assertEquals(3, all.items().size());
        assertEquals(0, all.hidden());
        assertEquals(2014L, all.items().get(2).id());
        assertEquals("SUSPECT", all.items().get(2).matchLevel());
        assertTrue(all.items().get(2).mismatchReasons().get(0).contains("500ml"));
    }

    @Test
    void search_variantWordOnlyOnOneSideIsSuspect() {
        SkuCatalog s = sku("6928804010145");
        s.setSkuName("330ml雪碧柠檬味汽水");
        s.setSpec("330毫升");
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(s);
        when(jiangyiGatherClient.stdSkuList("雪碧", null)).thenReturn(List.of(
                new JiangyiStdSku(2137L, "雪碧零卡", "6928804014686", "330ml",
                        "雪碧", "bottle", null, null, "app"),
                new JiangyiStdSku(2211L, "雪碧", "6954767437583", "330ml",
                        "雪碧", "bottle", null, null, "app")));
        when(linkMapper.listBoundProductIds(anyList())).thenReturn(List.of("2211"));

        SkuJiangyiLinkService.StdSkuSearchResult result =
                service().searchJiangyiProducts("雪碧", null, SKU_ID, true);

        assertEquals(2, result.items().size());
        // 未挂接优先：零卡虽被标 SUSPECT，仍排在已挂接的同规格之前
        assertEquals(2137L, result.items().get(0).id());
        assertEquals("SUSPECT", result.items().get(0).matchLevel());
        assertTrue(result.items().get(0).mismatchReasons().get(0).contains("零卡"));
        assertEquals("SAME_SPEC", result.items().get(1).matchLevel());
    }

    @Test
    void search_volumeUnitsAreNormalizedBeforeComparing() {
        SkuCatalog s = sku("6901234567890");
        s.setSkuName("雪碧");
        s.setSpec("1.5L");
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(s);
        when(jiangyiGatherClient.stdSkuList("雪碧", null)).thenReturn(List.of(
                new JiangyiStdSku(1L, "雪碧", null, "1500ml", "雪碧", "bottle", null, null, null)));

        SkuJiangyiLinkService.StdSkuSearchResult result =
                service().searchJiangyiProducts("雪碧", null, SKU_ID, false);

        assertEquals(0, result.hidden());
        assertEquals("SAME_SPEC", result.items().get(0).matchLevel());
        assertNull(result.items().get(0).barCodeSource());
    }

    @Test
    void search_jiangyiDirtyUnitHsIsComparedAsMillilitres() {
        // 2026-10-23 实盘：我方 310ml 罐装 vs 将邑同名 500ml 瓶装，对方 specs 写的是 "500hs"
        SkuCatalog s = sku("6921294307016");
        s.setSkuName("康师傅冰红茶（柠檬口味茶饮品）");
        s.setSpec("310ml");
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(s);
        when(jiangyiGatherClient.stdSkuList("康师傅", null)).thenReturn(List.of(
                new JiangyiStdSku(128L, "康师傅冰红茶柠檬口味茶饮品", "6922456805012", "500hs",
                        "康师傅", "bottle", null, null, "app"),
                new JiangyiStdSku(1473L, "康师傅陈皮酸梅汤", "6973870135001", "500ml",
                        "康师傅", "bottle", null, null, "app")));
        when(linkMapper.listBoundProductIds(anyList())).thenReturn(List.of());

        SkuJiangyiLinkService.StdSkuSearchResult filtered =
                service().searchJiangyiProducts("康师傅", null, SKU_ID, false);
        assertEquals(0, filtered.items().size());
        assertEquals(2, filtered.hidden());

        SkuJiangyiLinkService.StdSkuSearchResult all =
                service().searchJiangyiProducts("康师傅", null, SKU_ID, true);
        assertEquals(128L, all.items().get(0).id());
        assertEquals("SUSPECT", all.items().get(0).matchLevel());
        assertTrue(all.items().get(0).mismatchReasons().get(0).contains("500ml"));
    }

    @Test
    void search_sameNameWithUnparseableSpecIsUnverifiedNotRelated() {
        SkuCatalog s = sku("6921294307016");
        s.setSkuName("康师傅冰红茶（柠檬口味茶饮品）");
        s.setSpec("310ml");
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(s);
        when(jiangyiGatherClient.stdSkuList("康师傅", null)).thenReturn(List.of(
                new JiangyiStdSku(128L, "康师傅冰红茶柠檬口味茶饮品", "6922456805012", "1s",
                        "康师傅", "bottle", null, null, "app"),
                new JiangyiStdSku(1413L, "康师傅大红袍红茶", "6973870132925", "dt",
                        "康师傅", "bottle", null, null, "app")));
        when(linkMapper.listBoundProductIds(anyList())).thenReturn(List.of());

        SkuJiangyiLinkService.StdSkuSearchResult result =
                service().searchJiangyiProducts("康师傅", null, SKU_ID, false);

        // 同名不隐藏（隐藏会诱导运营重复新建），但降级为「同名待核对」并排在噪声之前
        assertEquals(2, result.items().size());
        assertEquals("UNVERIFIED", result.items().get(0).matchLevel());
        assertEquals("RELATED", result.items().get(1).matchLevel());
    }

    @Test
    void search_gramSpecIsNotGuessedIntoMillilitres() {
        SkuCatalog s = sku("6921294307016");
        s.setSkuName("康师傅冰红茶（柠檬口味茶饮品）");
        s.setSpec("310ml");
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(s);
        when(jiangyiGatherClient.stdSkuList("康师傅", null)).thenReturn(List.of(
                new JiangyiStdSku(140L, "康师傅红烧牛肉面桶装107g", "6920208900770", "107g",
                        "康师傅", "bowl", null, null, "manual")));
        when(linkMapper.listBoundProductIds(anyList())).thenReturn(List.of());

        SkuJiangyiLinkService.StdSkuSearchResult result =
                service().searchJiangyiProducts("康师傅", null, SKU_ID, false);

        assertEquals("RELATED", result.items().get(0).matchLevel());
        assertEquals(0, result.hidden());
    }

    @Test
    void search_withoutSkuIdDoesNotFilterOrScore() {
        when(jiangyiGatherClient.stdSkuList("雪碧", null)).thenReturn(List.of(
                new JiangyiStdSku(1L, "雪碧 500ml", "6901234567890", "500ml",
                        "雪碧", "bottle", null, null, "manual"),
                new JiangyiStdSku(2L, "雪碧零卡 330ml", "6901111111111", "330ml",
                        "雪碧", "bottle", null, null, "app")));
        when(linkMapper.listBoundProductIds(anyList())).thenReturn(List.of());

        SkuJiangyiLinkService.StdSkuSearchResult result =
                service().searchJiangyiProducts("雪碧", null, null, false);

        assertEquals(2, result.items().size());
        assertEquals(0, result.hidden());
        assertEquals("UNKNOWN", result.items().get(0).matchLevel());
    }

    @Test
    void unbind_missingLinkIs404AndExistingGoesRetired() {
        when(linkMapper.selectById(404L)).thenReturn(null);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().unbind(SKU_ID, 404L));
        assertEquals(404, e.getStatusCode().value());

        JiangyiSkuJiangyiLink link = new JiangyiSkuJiangyiLink();
        link.setId(9L);
        link.setSkuId(SKU_ID);
        link.setSyncStatus("BOUND");
        when(linkMapper.selectById(9L)).thenReturn(link);
        service().unbind(SKU_ID, 9L);
        assertEquals("RETIRED", link.getSyncStatus());
        verify(linkMapper).updateById(link);
    }

    @Test
    void unbind_linkOwnedByAnotherSkuIs404() {
        // 路径上的 skuId 必须与挂接行一致，否则任意 skuId 都能改掉别人的挂接
        JiangyiSkuJiangyiLink link = new JiangyiSkuJiangyiLink();
        link.setId(9L);
        link.setSkuId("SKU-OTHER");
        link.setSyncStatus("BOUND");
        when(linkMapper.selectById(9L)).thenReturn(link);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().unbind(SKU_ID, 9L));
        assertEquals(404, e.getStatusCode().value());
        verify(linkMapper, never()).updateById(any(JiangyiSkuJiangyiLink.class));
    }

    // ---------- deleteFromJiangyi（§4.1.6 删我方新建的商品，用于填错返工） ----------

    private JiangyiSkuJiangyiLink vendorLink(long id, String syncStatus, String productId) {
        JiangyiSkuJiangyiLink l = new JiangyiSkuJiangyiLink();
        l.setId(id);
        l.setSkuId(SKU_ID);
        l.setSyncStatus(syncStatus);
        l.setJiangyiProductId(productId);
        return l;
    }

    @Test
    void deleteFromJiangyi_createdDeletesAtVendorThenRetiresLink() {
        JiangyiSkuJiangyiLink link = vendorLink(9L, "CREATED", "41159");
        when(linkMapper.selectById(9L)).thenReturn(link);

        service().deleteFromJiangyi(SKU_ID, 9L);

        verify(jiangyiGatherClient).productDelete(41159L);
        assertEquals("RETIRED", link.getSyncStatus());
        verify(linkMapper).updateById(link);
    }

    @Test
    void deleteFromJiangyi_boundIs409AndVendorNeverCalled() {
        // BOUND 指向将邑标准库共享条目，删它会波及别的商户
        when(linkMapper.selectById(9L)).thenReturn(vendorLink(9L, "BOUND", "1018"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().deleteFromJiangyi(SKU_ID, 9L));

        assertEquals(409, e.getStatusCode().value());
        verify(jiangyiGatherClient, never()).productDelete(anyLong());
        verify(linkMapper, never()).updateById(any(JiangyiSkuJiangyiLink.class));
    }

    @Test
    void deleteFromJiangyi_vendorFailureKeepsLinkActive() {
        JiangyiSkuJiangyiLink link = vendorLink(9L, "CREATED", "41159");
        when(linkMapper.selectById(9L)).thenReturn(link);
        doThrow(new IllegalStateException("将邑采集 productDelete 失败 status=500"))
                .when(jiangyiGatherClient).productDelete(41159L);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().deleteFromJiangyi(SKU_ID, 9L));

        assertEquals(502, e.getStatusCode().value());
        assertEquals("CREATED", link.getSyncStatus());
        verify(linkMapper, never()).updateById(any(JiangyiSkuJiangyiLink.class));
    }

    // ---------- listPendingGather（§4.3.2 待采集：商品库搜不到的那一半） ----------

    @Test
    void pendingGather_marksWhichOfOurSkusCreatedIt() {
        when(jiangyiGatherClient.waitGatherProduct("冰红茶")).thenReturn(List.of(
                new GatherPendingProduct(41159L, "康师傅冰红茶柠檬口味茶饮品", "康师傅", "330",
                        "bottle", "https://public.example.com/api/v2/media/sku-images/2",
                        "", "ksf_ksfbhcnmkwcyp_330"),
                new GatherPendingProduct(50001L, "别的商户建的", null, null, null, null,
                        null, null)));
        JiangyiSkuJiangyiLink mine = vendorLink(2L, "CREATED", "41159");
        when(linkMapper.listByProducts(List.of("41159", "50001"))).thenReturn(List.of(mine));

        List<SkuJiangyiLinkService.PendingGatherView> views = service().listPendingGather("冰红茶");

        assertEquals(2, views.size());
        assertEquals(41159L, views.get(0).productId());
        assertEquals("330", views.get(0).specs());
        assertEquals(SKU_ID, views.get(0).linkedSkuId());
        assertEquals("CREATED", views.get(0).linkedSyncStatus());
        assertNull(views.get(1).linkedSkuId());
        assertNull(views.get(1).linkedSyncStatus());
    }

    @Test
    void pendingGather_blankNameStillQueriesVendorForFullList() {
        when(jiangyiGatherClient.waitGatherProduct(null)).thenReturn(List.of());
        when(linkMapper.listByProducts(List.of())).thenReturn(List.of());

        assertTrue(service().listPendingGather(null).isEmpty());
        verify(jiangyiGatherClient).waitGatherProduct(null);
    }

    @Test
    void bind_unknownSkuIs404() {
        when(skuCatalogMapper.selectById(SKU_ID)).thenReturn(null);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().bind(SKU_ID, 88L, "x", null));
        assertEquals(404, e.getStatusCode().value());
    }

    // ---------- resolveSkuIdByProductId（识别上报 thirdPid → SKU，CB-061 解析优先级） ----------

    private JiangyiSkuJiangyiLink link(String syncStatus, String skuId) {
        JiangyiSkuJiangyiLink l = new JiangyiSkuJiangyiLink();
        l.setSyncStatus(syncStatus);
        l.setSkuId(skuId);
        return l;
    }

    @Test
    void resolveSku_byProductHit() {
        when(linkMapper.byProduct("100013")).thenReturn(Optional.of(link("BOUND", SKU_ID)));
        assertEquals(Optional.of(SKU_ID), service().resolveSkuIdByProductId(" 100013 "));
    }

    @Test
    void resolveSku_retiredLinkIsMiss() {
        when(linkMapper.byProduct("100013")).thenReturn(Optional.of(link("RETIRED", SKU_ID)));
        assertTrue(service().resolveSkuIdByProductId("100013").isEmpty());
    }

    @Test
    void resolveSku_blankProductIdNeverTouchesMapper() {
        assertTrue(service().resolveSkuIdByProductId(null).isEmpty());
        assertTrue(service().resolveSkuIdByProductId("  ").isEmpty());
        verify(linkMapper, never()).byProduct(anyString());
    }

    @Test
    void resolveSku_linkWithoutSkuIdIsMiss() {
        when(linkMapper.byProduct("100013")).thenReturn(Optional.of(link("CREATED", "  ")));
        assertTrue(service().resolveSkuIdByProductId("100013").isEmpty());
    }
}
