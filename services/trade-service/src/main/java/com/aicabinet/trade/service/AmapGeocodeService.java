package com.aicabinet.trade.service;

import com.aicabinet.common.dto.GeocodeResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;

@Service
@Getter
@Setter
public class AmapGeocodeService {

    private final String amapWebKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public AmapGeocodeService(
            @Value("${aicabinet.amap.web-key:}") String amapWebKey,
            ObjectMapper objectMapper) {
        this.amapWebKey = amapWebKey == null ? "" : amapWebKey.trim();
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return !amapWebKey.isBlank();
    }

    public GeocodeResponse geocode(String address) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未配置高德 Web Key（AMAP_WEB_KEY），无法解析地址");
        }
        String addr = address == null ? "" : address.trim();
        if (addr.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "地址不能为空");
        }
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString("https://restapi.amap.com/v3/geocode/geo")
                    .queryParam("key", amapWebKey)
                    .queryParam("address", addr)
                    .build()
                    .encode()
                    .toUri();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "高德地理编码服务异常");
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!"1".equals(root.path("status").asText())) {
                String info = root.path("info").asText("地理编码失败");
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, info);
            }
            JsonNode geos = root.path("geocodes");
            if (!geos.isArray() || geos.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未找到该地址对应的坐标");
            }
            JsonNode first = geos.get(0);
            String location = first.path("location").asText("");
            String[] parts = location.split(",");
            if (parts.length != 2) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "地理编码返回坐标无效");
            }
            double lng = Double.parseDouble(parts[0]);
            double lat = Double.parseDouble(parts[1]);
            String formatted = first.path("formatted_address").asText(addr);
            if (formatted.isBlank() || "[]".equals(formatted)) {
                formatted = addr;
            }
            return new GeocodeResponse(lng, lat, formatted);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "地理编码请求失败：" + e.getMessage());
        }
    }
}
