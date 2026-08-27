package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DevOpsHubDto;
import com.aicabinet.common.dto.DevOpsSonarScanDto;
import com.aicabinet.common.dto.DevOpsToolDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class DevOpsHubService {

    private static final Logger log = LoggerFactory.getLogger(DevOpsHubService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration GITHUB_TIMEOUT = Duration.ofSeconds(20);
    private static final String SONAR_SCAN_LOCK = "devops:sonar:scan";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final DistributedLockService distributedLockService;

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

    @Value("${aicabinet.devops.github-url:https://github.com}")
    private String githubUrl;

    @Value("${aicabinet.devops.github-repo:chanwenxiang/ai-cabient}")
    private String githubRepo;

    @Value("${aicabinet.devops.github-token:}")
    private String githubToken;

    @Value("${aicabinet.devops.github-actions-workflow:sonar.yml}")
    private String githubActionsWorkflow;

    @Value("${aicabinet.devops.github-actions-ref:dev}")
    private String githubActionsRef;

    @Value("${aicabinet.devops.github-actions-url:}")
    private String githubActionsUrl;

    @Value("${aicabinet.devops.sonar-url:http://localhost:19002}")
    private String sonarUrl;

    @Value("${aicabinet.devops.sonar-health-url:http://localhost:19002}")
    private String sonarHealthUrl;

    @Value("${aicabinet.devops.grafana-embed-path:/devops/grafana/d/ai-cabinet-overview/ai-cabinet-overview?orgId=1&kiosk=tv&theme=light}")
    private String grafanaEmbedPath;

    public DevOpsHubService(DistributedLockService distributedLockService) {
        this.distributedLockService = distributedLockService;
    }

    public DevOpsHubDto getHub() {
        if (!enabled) {
            return new DevOpsHubDto(List.of(), githubUrl, null);
        }
        List<DevOpsToolDto> tools = new ArrayList<>();
        tools.add(tool("grafana", "Grafana", "业务指标与告警看板", grafanaUrl, grafanaEmbedPath, grafanaHealthUrl, "/api/health"));
        tools.add(tool("prometheus", "Prometheus", "指标采集与查询", prometheusUrl, null, prometheusHealthUrl, "/-/ready"));
        tools.add(tool("sonarqube", "SonarQube", "代码质量与覆盖率", sonarUrl, null, sonarHealthUrl, "/api/system/status"));
        tools.add(tool("github", "GitHub Actions", "CI 测试与 Sonar 扫描", resolveGithubActionsUrl(), null, null, null));
        return new DevOpsHubDto(tools, githubUrl, grafanaEmbedPath);
    }

    /**
     * 通过 GitHub Actions workflow_dispatch 排队一次 Sonar 全量扫描（异步，立即返回）。
     */
    public DevOpsSonarScanDto triggerSonarScan(Long operatorId) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DevOps hub disabled");
        }
        if (githubToken == null || githubToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub token not configured; set DEVOPS_GITHUB_TOKEN with actions:write");
        }
        String workflow = blankToDefault(githubActionsWorkflow, "sonar.yml");
        String ref = blankToDefault(githubActionsRef, "dev");
        if (!distributedLockService.tryLock(SONAR_SCAN_LOCK, 90, 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sonar scan already queued; retry later");
        }
        try {
            String actionsUrl = resolveGithubActionsUrl();
            dispatchGithubWorkflow(workflow, ref);
            String dashboard = trimSlash(sonarUrl) + "/dashboard?id=ai-cabinet-dev";
            log.info("DevOps Sonar scan queued via GitHub Actions operatorId={} workflow={} ref={} traceId=n/a",
                    operatorId, workflow, ref);
            return new DevOpsSonarScanDto(
                    true,
                    workflow,
                    actionsUrl,
                    actionsUrl,
                    dashboard,
                    "Queued GitHub Actions workflow '" + workflow + "' on ref '" + ref
                            + "'. Check Actions tab when finished.");
        } catch (Exception ex) {
            log.error("DevOps Sonar scan trigger failed operatorId={} workflow={}", operatorId, workflow, ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to trigger GitHub Actions: " + safeMessage(ex));
        } finally {
            distributedLockService.unlock(SONAR_SCAN_LOCK);
        }
    }

    private void dispatchGithubWorkflow(String workflow, String ref) throws IOException, InterruptedException {
        String repo = blankToDefault(githubRepo, "chanwenxiang/ai-cabient");
        String encodedWorkflow = URLEncoder.encode(workflow, StandardCharsets.UTF_8).replace("+", "%20");
        String path = "https://api.github.com/repos/" + repo + "/actions/workflows/" + encodedWorkflow + "/dispatches";
        String body = "{\"ref\":\"" + escapeJson(ref) + "\",\"inputs\":{\"ref\":\"" + escapeJson(ref) + "\"}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(path))
                .timeout(GITHUB_TIMEOUT)
                .header("Authorization", "Bearer " + githubToken.trim())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code == 401 || code == 403) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "GitHub auth failed; check DEVOPS_GITHUB_TOKEN (needs actions:write)");
        }
        if (code == 404) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "GitHub workflow not found: " + workflow + " in " + repo);
        }
        if (code != 204) {
            String hint = response.body() == null ? "" : response.body();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "GitHub dispatch failed HTTP " + code + (hint.isBlank() ? "" : ": " + truncate(hint, 120)));
        }
    }

    private String resolveGithubActionsUrl() {
        if (githubActionsUrl != null && !githubActionsUrl.isBlank()) {
            return githubActionsUrl.trim();
        }
        String repo = blankToDefault(githubRepo, "chanwenxiang/ai-cabient");
        String workflow = blankToDefault(githubActionsWorkflow, "sonar.yml");
        return "https://github.com/" + repo + "/actions/workflows/" + workflow;
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
        String hint;
        if (online) {
            hint = "在线";
        } else if (healthPath == null) {
            hint = "外部链接";
        } else {
            hint = "未检测到服务";
        }
        return new DevOpsToolDto(id, name, description, baseUrl, embedUrl, online, hint);
    }

    private boolean ping(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            String normalized = trimSlash(baseUrl);
            URI uri = URI.create(normalized + path);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 400;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("DevOps health check failed for {}: {}", baseUrl, ex.getMessage());
            return false;
        }
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String blankToDefault(String value, String def) {
        return value == null || value.isBlank() ? def : value.trim();
    }

    private static String safeMessage(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return msg.length() > 180 ? msg.substring(0, 180) + "…" : msg;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
