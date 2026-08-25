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

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class DevOpsHubService {

    private static final Logger log = LoggerFactory.getLogger(DevOpsHubService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration JENKINS_TIMEOUT = Duration.ofSeconds(15);
    private static final String SONAR_SCAN_LOCK = "devops:sonar:scan";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            // Jenkins CSRF：crumb 必须与会话 Cookie 一起提交
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
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

    @Value("${aicabinet.devops.jenkins-url:http://localhost:19081}")
    private String jenkinsUrl;

    @Value("${aicabinet.devops.jenkins-health-url:http://localhost:19081}")
    private String jenkinsHealthUrl;

    @Value("${aicabinet.devops.jenkins-api-url:}")
    private String jenkinsApiUrl;

    @Value("${aicabinet.devops.jenkins-user:admin}")
    private String jenkinsUser;

    @Value("${aicabinet.devops.jenkins-password:changeme}")
    private String jenkinsPassword;

    @Value("${aicabinet.devops.sonar-jenkins-job:ai-cabinet-sonar-dev-local}")
    private String sonarJenkinsJob;

    @Value("${aicabinet.devops.sonar-url:http://localhost:19002}")
    private String sonarUrl;

    @Value("${aicabinet.devops.sonar-health-url:http://localhost:19002}")
    private String sonarHealthUrl;

    @Value("${aicabinet.devops.github-url:https://github.com}")
    private String githubUrl;

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
        tools.add(tool("jenkins", "Jenkins", "本地发布流水线", jenkinsUrl, null, jenkinsHealthUrl, "/login"));
        tools.add(tool("sonarqube", "SonarQube", "代码质量与覆盖率", sonarUrl, null, sonarHealthUrl, "/api/system/status"));
        tools.add(tool("github", "GitHub", "源码与 Pull Request", githubUrl, null, null, null));
        return new DevOpsHubDto(tools, githubUrl, grafanaEmbedPath);
    }

    /**
     * 通过 Jenkins 任务排队一次 Sonar 扫描（异步，立即返回）。
     */
    public DevOpsSonarScanDto triggerSonarScan(Long operatorId) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DevOps hub disabled");
        }
        String job = blankToDefault(sonarJenkinsJob, "ai-cabinet-sonar-dev-local");
        if (!distributedLockService.tryLock(SONAR_SCAN_LOCK, 90, 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sonar scan already queued; retry later");
        }
        try {
            String apiBase = resolveJenkinsApiBase();
            Crumb crumb = fetchCrumb(apiBase);
            String queueUrl = enqueueJenkinsBuild(apiBase, job, crumb);
            String dashboard = trimSlash(sonarUrl) + "/dashboard?id=ai-cabinet-dev";
            String jobUi = trimSlash(jenkinsUrl) + "/job/" + urlEncodePath(job) + "/";
            log.info("DevOps Sonar scan queued operatorId={} job={} queueUrl={} traceId=n/a",
                    operatorId, job, queueUrl);
            return new DevOpsSonarScanDto(
                    true,
                    job,
                    queueUrl,
                    jobUi,
                    dashboard,
                    "Queued Jenkins job '" + job + "'. Check SonarQube when finished.");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("DevOps Sonar scan trigger failed operatorId={} job={}", operatorId, job, ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to trigger Jenkins scan: " + safeMessage(ex));
        } finally {
            distributedLockService.unlock(SONAR_SCAN_LOCK);
        }
    }

    private String resolveJenkinsApiBase() {
        if (jenkinsApiUrl != null && !jenkinsApiUrl.isBlank()) {
            return trimSlash(jenkinsApiUrl);
        }
        if (jenkinsHealthUrl != null && !jenkinsHealthUrl.isBlank()) {
            return trimSlash(jenkinsHealthUrl);
        }
        return trimSlash(jenkinsUrl);
    }

    private Crumb fetchCrumb(String apiBase) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/crumbIssuer/api/json"))
                .timeout(JENKINS_TIMEOUT)
                .header("Authorization", basicAuthHeader())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jenkins auth failed; check DEVOPS_JENKINS_USER/PASSWORD");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Jenkins crumb HTTP " + response.statusCode());
        }
        String body = response.body() == null ? "" : response.body();
        String field = jsonString(body, "crumbRequestField");
        String crumb = jsonString(body, "crumb");
        if (field.isBlank() || crumb.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid Jenkins crumb response");
        }
        return new Crumb(field, crumb);
    }

    private String enqueueJenkinsBuild(String apiBase, String job, Crumb crumb) throws Exception {
        String path = apiBase + "/job/" + urlEncodePath(job)
                + "/buildWithParameters?USE_LOCAL_MOUNT=true";
        HttpRequest request = HttpRequest.newBuilder(URI.create(path))
                .timeout(JENKINS_TIMEOUT)
                .header("Authorization", basicAuthHeader())
                .header(crumb.field(), crumb.value())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code == 404) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Jenkins job not found: " + job + " (is devops profile up?)");
        }
        if (code == 401 || code == 403) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jenkins rejected build (auth/CSRF)");
        }
        // Jenkins 成功排队通常返回 201，Location 指向 queue item
        if (code != 201 && code != 200 && code != 302) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Jenkins trigger failed HTTP " + code);
        }
        String location = response.headers().firstValue("Location").orElse("");
        if (!location.isBlank()) {
            return location;
        }
        return trimSlash(jenkinsUrl) + "/job/" + urlEncodePath(job) + "/";
    }

    private String basicAuthHeader() {
        String raw = blankToDefault(jenkinsUser, "admin") + ":" + (jenkinsPassword == null ? "" : jenkinsPassword);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
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
            String normalized = trimSlash(baseUrl);
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

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String blankToDefault(String value, String def) {
        return value == null || value.isBlank() ? def : value.trim();
    }

    private static String urlEncodePath(String job) {
        return URLEncoder.encode(job, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String safeMessage(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return msg.length() > 180 ? msg.substring(0, 180) + "…" : msg;
    }

    /** 极简 JSON 字符串字段提取（避免为 DevOps 引入额外依赖）。 */
    private static String jsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            return "";
        }
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) {
            return "";
        }
        int startQuote = json.indexOf('"', colon + 1);
        if (startQuote < 0) {
            return "";
        }
        int endQuote = startQuote + 1;
        while (endQuote < json.length()) {
            char c = json.charAt(endQuote);
            if (c == '"' && json.charAt(endQuote - 1) != '\\') {
                break;
            }
            endQuote++;
        }
        if (endQuote >= json.length()) {
            return "";
        }
        return json.substring(startQuote + 1, endQuote);
    }

    private record Crumb(String field, String value) {}
}
