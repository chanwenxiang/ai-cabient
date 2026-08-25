package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DevOpsHubDto;
import com.aicabinet.common.dto.DevOpsToolDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class DevOpsHubService {

    private static final Logger log = LoggerFactory.getLogger(DevOpsHubService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${aicabinet.devops.enabled:true}")
    private boolean enabled;

    @Value("${aicabinet.devops.grafana-url:http://localhost:13000}")
    private String grafanaUrl;

    @Value("${aicabinet.devops.grafana-health-url:http://localhost:13000}")
    private String grafanaHealthUrl;

    @Value("${aicabinet.devops.prometheus-url:http://localhost:9090}")
    private String prometheusUrl;

    @Value("${aicabinet.devops.prometheus-health-url:http://localhost:9090}")
    private String prometheusHealthUrl;

    @Value("${aicabinet.devops.jenkins-url:http://localhost:19081}")
    private String jenkinsUrl;

    @Value("${aicabinet.devops.jenkins-health-url:http://localhost:19081}")
    private String jenkinsHealthUrl;

    @Value("${aicabinet.devops.sonar-url:http://localhost:19002}")
    private String sonarUrl;

    @Value("${aicabinet.devops.sonar-health-url:http://localhost:19002}")
    private String sonarHealthUrl;

    @Value("${aicabinet.devops.github-url:https://github.com}")
    private String githubUrl;

    @Value("${aicabinet.devops.grafana-embed-path:/devops/grafana/d/ai-cabinet-overview/ai-cabinet-overview?orgId=1&kiosk=tv&theme=light}")
    private String grafanaEmbedPath;

    public DevOpsHubDto getHub() {
        if (!enabled) {
            return new DevOpsHubDto(List.of(), githubUrl, null);
        }
        List<DevOpsToolDto> tools = new ArrayList<>();
        tools.add(tool("grafana", "Grafana", "业务指标与告警看板", grafanaUrl, grafanaEmbedPath, grafanaHealthUrl, "/api/health"));
        tools.add(tool("prometheus", "Prometheus", "指标采集与查询", prometheusUrl, null, prometheusHealthUrl, "/-/ready"));
        tools.add(tool("jenkins", "Jenkins", "本地发布流水线", jenkinsUrl, null, jenkinsHealthUrl, "/login"));
        tools.add(tool("sonarqube", "SonarQube", "代码质量与覆盖率", sonarUrl, null, sonarHealthUrl, "/api/system/status"));
        tools.add(tool("github", "GitHub", "源码与 Pull Request", githubUrl, null, null, null));
        return new DevOpsHubDto(tools, githubUrl, grafanaEmbedPath);
    }

    private DevOpsToolDto tool(
            String id,
            String name,
            String description,
            String baseUrl,
            String embedUrl,
            String healthBaseUrl,
            String healthPath) {
        boolean online = healthPath != null && ping(healthBaseUrl, healthPath);
        String hint = online ? "在线" : (healthPath == null ? "外部链接" : "未检测到服务");
        return new DevOpsToolDto(id, name, description, baseUrl, embedUrl, online, hint);
    }

    private boolean ping(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            URI uri = URI.create(normalized + path);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 400;
        } catch (Exception ex) {
            log.debug("DevOps health check failed for {}: {}", baseUrl, ex.getMessage());
            return false;
        }
    }
}
