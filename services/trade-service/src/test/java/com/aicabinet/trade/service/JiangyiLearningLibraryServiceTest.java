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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 商品学习库聚合单测：覆盖按识别名匹配 + 复核戳判定、厂商读面失败降级、挂接 SKU 回填、
 * 学习凭据展示。厂商契约（§4.4.2/§4.4.4 字段）由 JiangyiGatherClient 自身测试与真机
 * 台账 覆盖，此处只钉组装的业务语义。
 */
@ExtendWith(MockitoExtension.class)
class JiangyiLearningLibraryServiceTest {

    private static final String DEV_A = "100000000001";
    private static final String DEV_B = "100000000002";

    @Mock private JiangyiGatherClient jiangyiGatherClient;
    @Mock private JiangyiDeviceMapper deviceMapper;
    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private JiangyiClassMappingMapper mappingMapper;
    @Mock private JiangyiSkuJiangyiLinkMapper linkMapper;
    @Mock private JiangyiTrainingTicketMapper ticketMapper;
    @Mock private SkuCatalogMapper skuMapper;

    private JiangyiLearningLibraryService service() {
        return new JiangyiLearningLibraryService(jiangyiGatherClient, deviceMapper,
                deviceInfoMapper, mappingMapper, linkMapper, ticketMapper, skuMapper);
    }

    private JiangyiDevice device(String deviceId, String classesVersion, String verifiedVersion) {
        JiangyiDevice d = new JiangyiDevice();
        d.setDeviceId(deviceId);
        d.setStatus("BOUND");
        d.setIdentifier("CQYB" + deviceId);
        d.setClassesVersion(classesVersion);
        d.setMappingVerifiedVersion(verifiedVersion);
        d.setLastWsOnlineAt(Instant.parse("2026-10-26T12:05:22Z"));
        return d;
    }

    private DeviceInfo info(String deviceId, String name) {
        DeviceInfo i = new DeviceInfo();
        i.setDeviceId(deviceId);
        i.setDeviceName(name);
        i.setOnlineStatus("ONLINE");
        return i;
    }

    private JiangyiClassMapping activeMapping(String deviceId, int classId, String textName) {
        JiangyiClassMapping m = new JiangyiClassMapping();
        m.setDeviceId(deviceId);
        m.setClassId(classId);
        m.setTextName(textName);
        m.setStatus("ACTIVE");
        m.setSkuId("SKU-1");
        return m;
    }

    private JiangyiSkuJiangyiLink link(String skuId, String productId, String textName) {
        JiangyiSkuJiangyiLink l = new JiangyiSkuJiangyiLink();
        l.setSkuId(skuId);
        l.setJiangyiProductId(productId);
        l.setJiangyiTextName(textName);
        l.setSyncStatus("BOUND");
        return l;
    }

    private SkuCatalog sku(String skuId, String name) {
        SkuCatalog s = new SkuCatalog();
        s.setSkuId(skuId);
        s.setSkuName(name);
        return s;
    }

    @Test
    void view_matchesCoverageByTextNameAndCarriesPerDeviceVerifiedFlag() {
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(
                new JiangyiModelFile(5247L, "hysxr", "hysxr_76_1", "http://m", "76",
                        "http://t/69.txt", 69)));
        when(jiangyiGatherClient.learnableProducts()).thenReturn(List.of(
                new LearnableProduct(41166L, "雪碧330ml", "xl_330", "6954767430386")));
        when(deviceMapper.listBound()).thenReturn(List.of(
                device(DEV_A, "v69", "v69"), device(DEV_B, "v70", "v69")));
        when(deviceInfoMapper.findByDeviceIdIn(any())).thenReturn(List.of(
                info(DEV_A, "办公室柜机"), info(DEV_B, "二号柜")));
        when(mappingMapper.listActiveAll()).thenReturn(List.of(
                activeMapping(DEV_A, 29, "xl_330"), activeMapping(DEV_B, 29, "xl_330")));
        when(linkMapper.listBoundAll()).thenReturn(List.of(link("SKU-1", "41166", "xl_330")));
        when(ticketMapper.listRecent(20)).thenReturn(List.of());
        when(skuMapper.findBySkuIds(any())).thenReturn(List.of(sku("SKU-1", "雪碧 330ml 罐")));

        var view = service().view();

        assertEquals(1, view.learnableProducts().size());
        var product = view.learnableProducts().get(0);
        assertEquals("SKU-1", product.skuId());
        assertEquals("雪碧 330ml 罐", product.skuName());
        assertEquals(2, product.coverage().size());
        assertEquals("hysxr_76_1", view.models().get(0).customerModelName(),
                "厂商初命名必须透传——我方提交的模型名可能被厂商改写");
        var coverA = product.coverage().stream()
                .filter(c -> DEV_A.equals(c.deviceId())).findFirst().orElseThrow();
        var coverB = product.coverage().stream()
                .filter(c -> DEV_B.equals(c.deviceId())).findFirst().orElseThrow();
        assertEquals("办公室柜机", coverA.deviceName());
        assertEquals(29, coverA.classId());
        assertTrue(coverA.mappingVerified());
        assertFalse(coverB.mappingVerified(), "classes_version 与复核戳不等 ⇒ 未复核");
        assertNull(view.vendorError());
    }

    @Test
    void view_vendorFailureDegradesButKeepsLocalCabinets() {
        when(jiangyiGatherClient.modelFiles()).thenThrow(new IllegalStateException("将邑 login2 失败"));
        when(jiangyiGatherClient.learnableProducts()).thenThrow(new IllegalStateException("将邑 503"));
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A, "v69", null)));
        when(deviceInfoMapper.findByDeviceIdIn(any())).thenReturn(List.of(info(DEV_A, "办公室柜机")));
        when(mappingMapper.listActiveAll()).thenReturn(List.of());
        when(linkMapper.listBoundAll()).thenReturn(List.of());
        when(ticketMapper.listRecent(20)).thenReturn(List.of());

        var view = service().view();

        assertNotNull(view.vendorError());
        assertTrue(view.vendorError().contains("读取将邑模型列表失败：将邑 login2 失败"));
        assertTrue(view.vendorError().contains("读取将邑可学习商品名单失败：将邑 503"));
        assertTrue(view.models().isEmpty());
        assertTrue(view.learnableProducts().isEmpty());
        assertEquals(1, view.cabinets().size(), "厂商挂了也必须能看到柜机");
        assertEquals("办公室柜机", view.cabinets().get(0).deviceName());
        assertFalse(view.cabinets().get(0).mappingVerified());
    }

    @Test
    void view_separatesVendorListMembershipFromRecognitionChain() {
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of());
        when(jiangyiGatherClient.learnableProducts()).thenReturn(List.of(
                new LearnableProduct(41166L, "雪碧330ml", "xl_330", null)));
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A, null, null)));
        when(deviceInfoMapper.findByDeviceIdIn(any())).thenReturn(List.of(info(DEV_A, "办公室柜机")));
        when(mappingMapper.listActiveAll()).thenReturn(List.of(activeMapping(DEV_A, 29, "xl_330")));
        when(linkMapper.listBoundAll()).thenReturn(List.of(
                link("SKU-1", "41166", "xl_330"),
                link("SKU-2", "41175", "ksf_310")));
        when(ticketMapper.listRecent(20)).thenReturn(List.of());
        when(skuMapper.findBySkuIds(any())).thenReturn(List.of(
                sku("SKU-1", "雪碧 330ml 罐"), sku("SKU-2", "康师傅冰红茶 310ml")));

        var view = service().view();

        var listed = view.learnableSkus().stream()
                .filter(s -> "SKU-1".equals(s.skuId())).findFirst().orElseThrow();
        var absent = view.learnableSkus().stream()
                .filter(s -> "SKU-2".equals(s.skuId())).findFirst().orElseThrow();
        assertTrue(listed.inVendorList());
        assertFalse(absent.inVendorList(), "不在厂商可学习名单里的商品不能标在列");
        assertEquals("康师傅冰红茶 310ml", absent.skuName());
        // 🔴 「在名单里」≠「已学成」：已进识别链路只认 ACTIVE 映射落在哪几台柜
        assertEquals(List.of(DEV_A), listed.mappedDeviceIds());
        assertTrue(absent.mappedDeviceIds().isEmpty());
        // 名单行与挂接同一 productId ⇒ 反查出我方 SKU
        assertEquals("SKU-1", view.learnableProducts().get(0).skuId());
    }

    @Test
    void view_mapsDeviceIdsFromVendorTextNameWhenLinkSnapshotIsStale() {
        // 厂商重训后 classes 行的识别名可能换写：名单里的当前值优先于挂接快照
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of());
        when(jiangyiGatherClient.learnableProducts()).thenReturn(List.of(
                new LearnableProduct(41166L, "雪碧330ml", "kkkl_xb_qsnmw_pttz_330ml", null)));
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A, null, null)));
        when(deviceInfoMapper.findByDeviceIdIn(any())).thenReturn(List.of(info(DEV_A, "办公室柜机")));
        when(mappingMapper.listActiveAll()).thenReturn(
                List.of(activeMapping(DEV_A, 29, "kkkl_xb_qsnmw_pttz_330ml")));
        when(linkMapper.listBoundAll()).thenReturn(List.of(link("SKU-1", "41166", "xl_330")));
        when(ticketMapper.listRecent(20)).thenReturn(List.of());
        when(skuMapper.findBySkuIds(any())).thenReturn(List.of(sku("SKU-1", "雪碧 330ml 罐")));

        var view = service().view();

        assertEquals(List.of(DEV_A), view.learnableSkus().get(0).mappedDeviceIds(),
                "按厂商名单里的识别名对位，不按挂接快照");
    }

    @Test
    void view_productWithoutTextNameHasNoCoverage() {
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of());
        when(jiangyiGatherClient.learnableProducts()).thenReturn(List.of(
                new LearnableProduct(41166L, "无识别名商品", null, null)));
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A, "v69", "v69")));
        when(deviceInfoMapper.findByDeviceIdIn(any())).thenReturn(List.of(info(DEV_A, "办公室柜机")));
        when(mappingMapper.listActiveAll()).thenReturn(List.of(activeMapping(DEV_A, 29, "xl_330")));
        when(linkMapper.listBoundAll()).thenReturn(List.of());
        when(ticketMapper.listRecent(20)).thenReturn(List.of());
        when(skuMapper.findBySkuIds(any())).thenReturn(List.of());

        var view = service().view();

        assertTrue(view.learnableProducts().get(0).coverage().isEmpty(),
                "识别名为空不能按 textName 误命中别的映射");
        assertNull(view.learnableProducts().get(0).skuId());
    }

    @Test
    void view_recentTrainingsCarryDeviceAndSkuNames() {
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of());
        when(jiangyiGatherClient.learnableProducts()).thenReturn(List.of());
        when(deviceMapper.listBound()).thenReturn(List.of(device(DEV_A, "v69", "v69")));
        when(deviceInfoMapper.findByDeviceIdIn(any())).thenReturn(List.of(info(DEV_A, "办公室柜机")));
        when(mappingMapper.listActiveAll()).thenReturn(List.of());
        when(linkMapper.listBoundAll()).thenReturn(List.of());
        JiangyiTrainingTicket ticket = new JiangyiTrainingTicket();
        ticket.setDeviceId(DEV_A);
        ticket.setSkuId("SKU-1");
        ticket.setModelName("hysxr");
        ticket.setStatus("FINISHED");
        ticket.setCreatedAt(Instant.parse("2026-10-26T10:00:00Z"));
        ticket.setFinishedAt(Instant.parse("2026-10-26T10:20:00Z"));
        when(ticketMapper.listRecent(20)).thenReturn(List.of(ticket));
        when(skuMapper.findBySkuIds(any())).thenReturn(List.of(sku("SKU-1", "雪碧 330ml 罐")));

        var view = service().view();

        assertEquals(1, view.recentTrainings().size());
        var attempt = view.recentTrainings().get(0);
        assertEquals("办公室柜机", attempt.deviceName());
        assertEquals("雪碧 330ml 罐", attempt.skuName());
        assertEquals("hysxr", attempt.modelName());
        assertEquals("FINISHED", attempt.status());
        assertEquals(Instant.parse("2026-10-26T10:20:00Z"), attempt.finishedAt());
    }
}
