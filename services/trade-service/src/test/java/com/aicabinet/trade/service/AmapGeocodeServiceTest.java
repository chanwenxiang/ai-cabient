package com.aicabinet.trade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 缺高德 Web Key 时必须明确降级，不能伪造坐标或把配置缺失冒成服务崩溃。 */
class AmapGeocodeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void isConfigured_blankNullAndWhitespaceKeyAreUnavailable() {
        assertFalse(new AmapGeocodeService(null, objectMapper).isConfigured());
        assertFalse(new AmapGeocodeService("", objectMapper).isConfigured());
        assertFalse(new AmapGeocodeService("   ", objectMapper).isConfigured());
        assertTrue(new AmapGeocodeService("  test-key  ", objectMapper).isConfigured());
    }

    @Test
    void geocode_missingKeyReturnsDiagnosticBusinessErrorWithoutCallingAmap() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new AmapGeocodeService("", objectMapper)
                        .geocode("上海市杨浦区某门店", "310100", "310110"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("地址解析服务未启用，请手动填写地址和坐标", error.getReason());
        assertFalse(error.getReason().contains("AMAP_WEB_KEY"), "用户提示不暴露环境配置键");
    }

    @Test
    void districts_missingKeyReturnsDiagnosticBusinessErrorWithoutCallingAmap() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new AmapGeocodeService(null, objectMapper).districts(""));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("行政区划服务未启用，请手动填写详细地址", error.getReason());
        assertFalse(error.getReason().contains("AMAP_WEB_KEY"), "用户提示不暴露环境配置键");
    }
}
