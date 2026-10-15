package com.aicabinet.trade.auth;

import com.aicabinet.trade.service.DeviceHttpCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 设备面一机一密拦截器单测（审计 A1/P0-2）：401 缺头/凭据错、403 路径绑定不符、放行一致。 */
@ExtendWith(MockitoExtension.class)
class DeviceHttpCredentialInterceptorTest {

    @Mock
    private DeviceHttpCredentialService credentialService;

    private DeviceHttpCredentialInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new DeviceHttpCredentialInterceptor(credentialService);
    }

    @Test
    void missingHeadersIs401() throws Exception {
        HttpServletRequest request = request("/internal/v1/devices/123456789012/ota/check",
                null, null);
        HttpServletResponse response = response();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(401);
    }

    @Test
    void badCredentialIs401() throws Exception {
        HttpServletRequest request = request("/internal/v1/devices/123456789012/ota/check",
                "123456789012", "wrong");
        when(credentialService.verify("123456789012", "wrong")).thenReturn(false);
        HttpServletResponse response = response();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(401);
    }

    @Test
    void deviceIdMismatchIs403DeviceMismatch() throws Exception {
        // 凭据正确但指向别的设备：P1-5 同语义绑定防线
        HttpServletRequest request = request("/internal/v1/devices/999999999999/ota/check",
                "123456789012", "good");
        when(credentialService.verify("123456789012", "good")).thenReturn(true);
        HttpServletResponse response = response();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    void matchingCredentialAndPathPasses() throws Exception {
        HttpServletRequest request = request("/internal/v1/devices/123456789012/ota/check",
                "123456789012", "good");
        when(credentialService.verify("123456789012", "good")).thenReturn(true);
        HttpServletResponse response = response();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void pathDeviceIdExtraction() {
        assertThat(DeviceHttpCredentialInterceptor.pathDeviceId(
                "/internal/v1/devices/123456789012/ota/check")).isEqualTo("123456789012");
        assertThat(DeviceHttpCredentialInterceptor.pathDeviceId(
                "/internal/v1/devices/123456789012/screen-content")).isEqualTo("123456789012");
        assertThat(DeviceHttpCredentialInterceptor.pathDeviceId("/internal/v1/demo/context")).isNull();
    }

    private static HttpServletRequest request(String uri, String deviceId, String secret) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        // preHandle 对缺头短路（头读完才读 URI），部分用例不会消费全部桩 ⇒ lenient
        org.mockito.Mockito.lenient().when(request.getRequestURI()).thenReturn(uri);
        org.mockito.Mockito.lenient().when(request.getHeader(DeviceHttpCredentialInterceptor.DEVICE_ID_HEADER))
                .thenReturn(deviceId);
        org.mockito.Mockito.lenient().when(request.getHeader(DeviceHttpCredentialInterceptor.DEVICE_SECRET_HEADER))
                .thenReturn(secret);
        return request;
    }

    private static HttpServletResponse response() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        // 仅 403 分支会写 body；lenient 避免其余用例 UnnecessaryStubbing
        org.mockito.Mockito.lenient().when(response.getWriter())
                .thenReturn(new PrintWriter(new StringWriter()));
        return response;
    }
}
