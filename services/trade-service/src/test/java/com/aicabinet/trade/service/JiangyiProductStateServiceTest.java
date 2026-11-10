package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiRestockClient;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.dto.JiangyiRestockDtos.ProductState;
import com.aicabinet.trade.dto.JiangyiRestockDtos.State;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第七章商品状态落库（V16 §7.2/§7.5/§7.6/§7.10）。
 *
 * <p>钉三件事：① 状态/nextAction/rejectCause 落到挂接行；② **绝不回写
 * `jiangyi_product_id`**（该列混装 thirdPid 与 productId 两个命名空间，台账 §12B）；
 * ③ 7.10 批量按 100 分片。</p>
 */
@ExtendWith(MockitoExtension.class)
class JiangyiProductStateServiceTest {

    @Mock private JiangyiRestockClient client;
    @Mock private JiangyiSkuJiangyiLinkMapper linkMapper;

    private JiangyiProductStateService service;

    @BeforeEach
    void setUp() {
        service = new JiangyiProductStateService(client, linkMapper);
    }

    @Test
    void checkPersistsStateWithoutTouchingProductId() {
        JiangyiSkuJiangyiLink row = new JiangyiSkuJiangyiLink();
        row.setId(1L);
        row.setSkuId("SKU-1");
        row.setJiangyiProductId("100016");
        row.setBarCode("690123");
        when(client.lookup("690123")).thenReturn(new ProductState("690123",
                State.CABINET_PENDING, "RESTOCK_THEN_CABINET_CAPTURE", 39788L, "可乐", null));
        when(linkMapper.byBarCode("690123")).thenReturn(Optional.of(row));

        service.check("690123");

        ArgumentCaptor<JiangyiSkuJiangyiLink> captor = ArgumentCaptor.forClass(JiangyiSkuJiangyiLink.class);
        verify(linkMapper).updateById(captor.capture());
        JiangyiSkuJiangyiLink saved = captor.getValue();
        assertEquals("CABINET_PENDING", saved.getJiangyiState());
        assertEquals("RESTOCK_THEN_CABINET_CAPTURE", saved.getNextAction());
        assertEquals("100016", saved.getJiangyiProductId(), "不得回写 productId（命名空间混装）");
        assertTrue(saved.getStateCheckedAt() != null);
    }

    @Test
    void rejectCauseIsPersisted() {
        JiangyiSkuJiangyiLink row = new JiangyiSkuJiangyiLink();
        row.setId(2L);
        when(client.lookup("690124")).thenReturn(new ProductState("690124",
                State.REJECTED, "PHONE_CAPTURE", 7L, "雪碧", "背景不干净，请重拍"));
        when(linkMapper.byBarCode("690124")).thenReturn(Optional.of(row));

        service.check("690124");

        ArgumentCaptor<JiangyiSkuJiangyiLink> captor = ArgumentCaptor.forClass(JiangyiSkuJiangyiLink.class);
        verify(linkMapper).updateById(captor.capture());
        assertEquals("REJECTED", captor.getValue().getJiangyiState());
        assertEquals("背景不干净，请重拍", captor.getValue().getRejectCause());
    }

    /**
     * 2026-11-10 数据卫生：审核通过/状态前进后厂商不再返回 rejectCause，而 updateById 跳过
     * null 字段 ⇒ 上一轮的旧「重新拍摄」会永久残留（实测冰红茶 CABINET_PENDING 行），
     * 必须走 mapper 的显式清列 UPDATE。
     */
    @Test
    void clearsStaleRejectCauseWhenVendorReturnsNone() {
        JiangyiSkuJiangyiLink row = new JiangyiSkuJiangyiLink();
        row.setId(41L);
        row.setRejectCause("重新拍摄");
        when(client.lookup("6921294307016")).thenReturn(new ProductState("6921294307016",
                State.CABINET_PENDING, "RESTOCK_THEN_CABINET_CAPTURE", 41175L, "康师傅冰红茶", null));
        when(linkMapper.byBarCode("6921294307016")).thenReturn(Optional.of(row));

        service.check("6921294307016");

        ArgumentCaptor<JiangyiSkuJiangyiLink> captor = ArgumentCaptor.forClass(JiangyiSkuJiangyiLink.class);
        verify(linkMapper).updateById(captor.capture());
        assertNull(captor.getValue().getRejectCause());
        verify(linkMapper).clearRejectCause(41L);
    }

    @Test
    void doesNotClearWhenVendorReportsRejection() {
        JiangyiSkuJiangyiLink row = new JiangyiSkuJiangyiLink();
        row.setId(42L);
        when(client.lookup("690127")).thenReturn(new ProductState("690127",
                State.REJECTED, "PHONE_CAPTURE", 8L, "加多宝", "背景杂乱"));
        when(linkMapper.byBarCode("690127")).thenReturn(Optional.of(row));

        service.check("690127");

        verify(linkMapper, never()).clearRejectCause(any());
    }

    @Test
    void noLinkRowSkipsPersist() {
        when(client.lookup("690125")).thenReturn(new ProductState("690125",
                State.NEW, "CREATE_PRODUCT", null, null, null));
        when(linkMapper.byBarCode("690125")).thenReturn(Optional.empty());

        assertTrue(service.check("690125").isEmpty());
        verify(linkMapper, never()).updateById(any(JiangyiSkuJiangyiLink.class));
    }

    @Test
    void refreshSplitsByHundred() {
        when(client.batchStatus(anyList())).thenReturn(List.of());
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            codes.add(String.valueOf(1000 + i));
        }

        service.refresh(codes);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(client, times(3)).batchStatus(captor.capture());
        assertEquals(100, captor.getAllValues().get(0).size());
        assertEquals(100, captor.getAllValues().get(1).size());
        assertEquals(50, captor.getAllValues().get(2).size());
    }

    @Test
    void emptyRefreshDoesNotCallVendor() {
        assertEquals(0, service.refresh(List.of()));
        verify(client, never()).batchStatus(anyList());
    }

    @Test
    void unknownStateIsStoredAsUnknown() {
        JiangyiSkuJiangyiLink row = new JiangyiSkuJiangyiLink();
        row.setId(3L);
        when(client.lookup("690126")).thenReturn(new ProductState("690126",
                State.UNKNOWN, null, null, null, null));
        when(linkMapper.byBarCode("690126")).thenReturn(Optional.of(row));

        service.check("690126");

        ArgumentCaptor<JiangyiSkuJiangyiLink> captor = ArgumentCaptor.forClass(JiangyiSkuJiangyiLink.class);
        verify(linkMapper).updateById(captor.capture());
        assertEquals("UNKNOWN", captor.getValue().getJiangyiState());
        assertNull(captor.getValue().getRejectCause());
    }

    /**
     * 2026-11-10 实测：柜内采集上传期间厂商 7.5 会回七态表之外的值（原始字符串必须落库，
     * 否则统一存 UNKNOWN，前端只能显示「未知状态」而排查时说不出是哪个新状态）。
     */
    @Test
    void unknownRawStateIsStoredAsRaw() {
        JiangyiSkuJiangyiLink row = new JiangyiSkuJiangyiLink();
        row.setId(4L);
        when(client.lookup("690128")).thenReturn(new ProductState("690128",
                State.UNKNOWN, "RESTOCK", null, null, null, "UPLOADING"));
        when(linkMapper.byBarCode("690128")).thenReturn(Optional.of(row));

        service.check("690128");

        ArgumentCaptor<JiangyiSkuJiangyiLink> captor = ArgumentCaptor.forClass(JiangyiSkuJiangyiLink.class);
        verify(linkMapper).updateById(captor.capture());
        assertEquals("UPLOADING", captor.getValue().getJiangyiState());
    }
}