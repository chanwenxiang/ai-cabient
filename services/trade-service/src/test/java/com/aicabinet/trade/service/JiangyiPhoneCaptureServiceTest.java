package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiRestockClient;
import com.aicabinet.trade.dto.JiangyiRestockDtos.PhoneCapturePolicy;
import com.aicabinet.trade.dto.JiangyiRestockDtos.ProductState;
import com.aicabinet.trade.dto.JiangyiRestockDtos.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 手机采集编排（§7.7/§7.8/§7.9）。
 *
 * <p>钉四件事：① 张数按 shotCount 校验；② 只有 NEED_PHONE/REJECTED 可提交；
 * ③ 8 张按顺序先上传再统一提交；④ 提交结果交给状态服务落快照。</p>
 */
@ExtendWith(MockitoExtension.class)
class JiangyiPhoneCaptureServiceTest {

    @Mock private JiangyiRestockClient client;
    @Mock private JiangyiProductStateService productStateService;

    private JiangyiPhoneCaptureService service;

    @BeforeEach
    void setUp() {
        service = new JiangyiPhoneCaptureService(client, productStateService);
    }

    private static JiangyiPhoneCaptureService.Photo photo(String name) {
        return new JiangyiPhoneCaptureService.Photo(
                name.getBytes(StandardCharsets.UTF_8), name + ".jpg", "image/jpeg");
    }

    @Test
    void rejectsWhenShotCountMismatch() {
        when(client.phoneCapturePolicy()).thenReturn(new PhoneCapturePolicy(8, List.of("正面", "左侧")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.submit("690123", List.of(photo("1"), photo("2"))));

        assertTrue(e.getMessage().contains("8"));
        verify(client, never()).lookup("690123");
        verify(client, never()).uploadImage(anyBytes(), eq("1.jpg"), eq("image/jpeg"));
    }

    @Test
    void rejectsReadyProduct() {
        when(client.phoneCapturePolicy()).thenReturn(new PhoneCapturePolicy(1, List.of("正面")));
        when(client.lookup("690123")).thenReturn(new ProductState("690123",
                State.READY, "RESTOCK", 10L, "可乐", null));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.submit("690123", List.of(photo("1"))));
        assertTrue(e.getMessage().contains("免采集"));
        verify(client, never()).uploadImage(anyBytes(), eq("1.jpg"), eq("image/jpeg"));
    }

    @Test
    void rejectsUnbuiltProduct() {
        when(client.phoneCapturePolicy()).thenReturn(new PhoneCapturePolicy(1, List.of("正面")));
        when(client.lookup("690123")).thenReturn(new ProductState("690123",
                State.NEW, "CREATE_PRODUCT", null, null, null));

        assertThrows(IllegalStateException.class,
                () -> service.submit("690123", List.of(photo("1"))));
        verify(client, never()).uploadImage(anyBytes(), eq("1.jpg"), eq("image/jpeg"));
    }

    @Test
    void uploadsInOrderThenSubmitsVendorUrls() {
        when(client.phoneCapturePolicy()).thenReturn(new PhoneCapturePolicy(3, List.of("a", "b", "c")));
        when(client.lookup("690123")).thenReturn(new ProductState("690123",
                State.NEED_PHONE, "PHONE_CAPTURE", 39788L, "可乐", null));
        when(client.uploadImage(anyBytes(), eq("1.jpg"), eq("image/jpeg"))).thenReturn("https://oss/1.jpg");
        when(client.uploadImage(anyBytes(), eq("2.jpg"), eq("image/jpeg"))).thenReturn("https://oss/2.jpg");
        when(client.uploadImage(anyBytes(), eq("3.jpg"), eq("image/jpeg"))).thenReturn("https://oss/3.jpg");
        when(productStateService.submitPhoneCapture(eq(39788L), anyList())).thenReturn(Optional.empty());

        service.submit("690123", List.of(photo("1"), photo("2"), photo("3")));

        verify(client, times(3)).uploadImage(anyBytes(), anyString(), eq("image/jpeg"));
        verify(productStateService).submitPhoneCapture(eq(39788L),
                eq(List.of("https://oss/1.jpg", "https://oss/2.jpg", "https://oss/3.jpg")));
    }

    @Test
    void rejectedProductCanResubmit() {
        when(client.phoneCapturePolicy()).thenReturn(new PhoneCapturePolicy(1, List.of("a")));
        when(client.lookup("690123")).thenReturn(new ProductState("690123",
                State.REJECTED, "PHONE_CAPTURE", 39788L, "可乐", "背景不干净"));
        when(client.uploadImage(anyBytes(), anyString(), eq("image/jpeg"))).thenReturn("https://oss/1.jpg");
        when(productStateService.submitPhoneCapture(eq(39788L), anyList())).thenReturn(Optional.empty());

        service.submit("690123", List.of(photo("1")));

        verify(productStateService).submitPhoneCapture(eq(39788L), eq(List.of("https://oss/1.jpg")));
    }

    @Test
    void policyIsReadFromVendor() {
        when(client.phoneCapturePolicy()).thenReturn(new PhoneCapturePolicy(16, List.of("x")));
        assertEquals(16, service.policy().shotCount());
    }

    private static byte[] anyBytes() {
        return org.mockito.ArgumentMatchers.any(byte[].class);
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
